#pragma once
// The PCAM v3 receive loop shared by macOS and Windows: reads messages from any transport (USB socket or
// decrypted Wi-Fi records), checks order and keyframes, and hands configuration and frames to the
// platform decoder. Portable C++17; the decoder supplies configure(body) and frame(body, header).
#include "protocol.hpp"
#include <atomic>
#include <chrono>
#include <cstdio>
#include <functional>
#include <stdexcept>
#include <vector>

namespace phonecam {
// The forward exists but the phone closed it before any video: no session started yet (or a stale key).
struct NotStreaming : std::runtime_error { using std::runtime_error::runtime_error; };
// The other end closed the connection (as opposed to a timeout or bad data).
struct Closed : std::runtime_error { using std::runtime_error::runtime_error; };
// Reads exactly n bytes within the given seconds, or throws (Closed when the peer hung up).
using Reader = std::function<void(uint8_t *, size_t, int)>;
using LogSink = void (*)(const char *);

template <class Decoder>
void decodeMessages(std::atomic<bool> &stop, const Reader &read, Decoder &decoder, LogSink log, const char *machine) {
    bool configured = false, waitingForKey = true, seen = false;
    uint64_t lastSequence = 0, lastPts = 0;
    // Decode + rotate + hand-off time per frame. Over 33 ms means the computer, not the link, is the
    // bottleneck at 30 fps; logged once a second only when it happens (bounded telemetry).
    auto windowStart = std::chrono::steady_clock::now();
    unsigned windowFrames = 0, slowFrames = 0; double worstMs = 0;
    bool warmedUp = false;  // the first frame includes decoder start-up; not counted
    while (!stop) {
        uint8_t bytes[32];
        if (!configured) {
            // ADB accepts locally, then closes at once when the phone app is not listening.
            try { read(bytes, sizeof(bytes), 15); }
            catch (const Closed &) { throw NotStreaming("Waiting for Start live on the phone"); }
        } else {
            try { read(bytes, sizeof(bytes), seen ? 3 : 15); }
            catch (const Closed &) { throw std::runtime_error("the phone stopped sending (Stop tapped, app left, cable unplugged or Wi-Fi lost)"); }
        }
        Header h = header(bytes);
        std::vector<uint8_t> body(h.length); read(body.data(), body.size(), 3);
        if (h.type == 1) {
            if (configured) throw std::runtime_error("Unexpected reconfiguration; restart live capture");
            decoder.configure(body); configured = true;
        } else {
            if (!configured || (seen && (h.sequence <= lastSequence || h.pts <= lastPts))) throw std::runtime_error("Invalid frame order/timing");
            if (seen && h.sequence != lastSequence + 1) waitingForKey = true;
            seen = true; lastSequence = h.sequence; lastPts = h.pts;
            if (waitingForKey && !(h.flags & 1)) continue;
            waitingForKey = false;
            auto began = std::chrono::steady_clock::now();
            decoder.frame(body, h);
            auto now = std::chrono::steady_clock::now();
            double ms = std::chrono::duration<double, std::milli>(now - began).count();
            if (!warmedUp) { warmedUp = true; windowStart = now; continue; }
            ++windowFrames; if (ms > 33.3) ++slowFrames; if (ms > worstMs) worstMs = ms;
            if (now - windowStart >= std::chrono::seconds(1)) {
                if (log && slowFrames) {
                    char line[160];
                    snprintf(line, sizeof(line), "%s decoding fell behind: %u of %u frames over 33 ms in the last second (slowest %.0f ms)",
                             machine, slowFrames, windowFrames, worstMs);
                    log(line);
                }
                windowStart = now; windowFrames = slowFrames = 0; worstMs = 0;
            }
        }
    }
}
}
