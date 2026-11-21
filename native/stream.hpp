#pragma once
// The PCAM v3 receive loop shared by macOS and Windows: reads messages from any transport (USB socket or
// decrypted Wi-Fi records), checks order and keyframes, and hands configuration and frames to the
// platform decoder. Portable C++17; the decoder supplies configure(body) and frame(body, header).
#include "protocol.hpp"
#include <atomic>
#include <chrono>
#include <cstdio>
#include <functional>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace phonecam {
// The forward exists but the phone closed it before any video: no session started yet (or a stale key).
struct NotStreaming : std::runtime_error { using std::runtime_error::runtime_error; };
// The other end closed the connection (as opposed to a timeout or bad data).
struct Closed : std::runtime_error { using std::runtime_error::runtime_error; };
// Reads exactly n bytes within the given seconds, or throws (Closed when the peer hung up).
using Reader = std::function<void(uint8_t *, size_t, int)>;
using LogSink = void (*)(const char *);

// The two-way part of a session (0.6): what the phone says besides video, and a way to answer it.
// One per connection kind (USB, Wi-Fi). The transport attaches a writer while a session is open; the plugin sets
// the callbacks and sends commands from any thread. Callbacks run on the receiving thread, under cbLock, so
// clearing them (the source is going away) waits for a running one to finish.
class Channel {
public:
    using Writer = std::function<void(const std::vector<uint8_t> &)>;  // sends one whole message or throws
    struct Callbacks {
        std::function<void(const std::string &)> control;              // "hello …", "status …" from the phone
        std::function<void(const AudioChunk &, uint64_t pts)> audio;    // phone microphone, phone microseconds
        std::function<void()> ended;                                    // the session closed
    };
    void setCallbacks(Callbacks value) { std::lock_guard<std::mutex> guard(cbLock); callbacks = std::move(value); }
    void attach(Writer value) { std::lock_guard<std::mutex> guard(writeLock); writer = std::move(value); phone = false; }
    void detach() {
        { std::lock_guard<std::mutex> guard(writeLock); writer = nullptr; phone = false; }
        std::lock_guard<std::mutex> guard(cbLock);
        if (callbacks.ended) callbacks.ended();
    }
    // True when a 0.6 phone is connected and the command went out.
    bool send(const std::string &text) {
        std::lock_guard<std::mutex> guard(writeLock);
        if (!writer || !phone) return false;
        try { writer(control(text)); return true; } catch (...) { return false; }
    }
    bool phoneTalks() const { return phone; }
    void received(const Header &h, const std::vector<uint8_t> &body) {
        if (h.type == Control) {
            std::string text(body.begin(), body.end());
            if (text.rfind("hello", 0) == 0) phone = true;
            std::lock_guard<std::mutex> guard(cbLock);
            if (callbacks.control) callbacks.control(text);
        } else if (h.type == Audio) {
            AudioChunk chunk = audioChunk(body);
            std::lock_guard<std::mutex> guard(cbLock);
            if (callbacks.audio) callbacks.audio(chunk, h.pts);
        }
    }
private:
    std::mutex writeLock, cbLock;
    Writer writer;
    Callbacks callbacks;
    std::atomic<bool> phone{false};
};
// Detaches on every way out of a session.
struct Attached {
    Channel *channel;
    Attached(Channel *c, Channel::Writer w) : channel(c) { if (channel) channel->attach(std::move(w)); }
    ~Attached() { if (channel) channel->detach(); }
};

// Maps phone timestamps (microseconds, the phone's monotonic clock) to this computer's clock (nanoseconds), so the
// phone microphone and the picture stay together in OBS. The offset is the smallest (arrival − phone time) seen:
// the least delayed message. It is renewed every 30 s from the last window, so clock drift and a changed route
// are followed without jumps from one late message.
class ClockMap {
public:
    uint64_t map(uint64_t phoneUs, uint64_t arrivalNs) {
        int64_t offset = int64_t(arrivalNs) - int64_t(phoneUs) * 1000;
        if (!have || offset < best) { best = offset; have = true; }
        if (!windowHave || offset < windowBest) { windowBest = offset; windowHave = true; }
        if (arrivalNs - windowStart > 30'000'000'000ull) { best = windowBest; windowHave = false; windowStart = arrivalNs; }
        return uint64_t(int64_t(phoneUs) * 1000 + best);
    }
    void reset() { have = windowHave = false; windowStart = 0; }
private:
    int64_t best = 0, windowBest = 0; bool have = false, windowHave = false; uint64_t windowStart = 0;
};

template <class Decoder>
void decodeMessages(std::atomic<bool> &stop, const Reader &read, Decoder &decoder, LogSink log, const char *machine,
                    Channel *channel = nullptr) {
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
            catch (const Closed &) { throw NotStreaming("Waiting for the record button on the phone"); }
        } else {
            try { read(bytes, sizeof(bytes), seen ? 3 : 15); }
            catch (const Closed &) { throw std::runtime_error("the phone stopped sending (Stop tapped, app left, cable unplugged or Wi-Fi lost)"); }
        }
        Header h = header(bytes);
        std::vector<uint8_t> body(h.length); read(body.data(), body.size(), 3);
        if (h.type == 1) {
            // A second configuration means the phone switched camera or quality on the same connection
            // (0.4.1 and later). The decoder starts over; frame order and timing restart with the new camera.
            if (configured && log) log("The phone switched camera or quality; restarting the decoder.");
            decoder.configure(body);
            if (configured) { seen = false; waitingForKey = true; warmedUp = false; }
            configured = true;
        } else if (h.type != Frame) {
            if (channel) channel->received(h, body);  // audio and control; ignored by a plugin without a channel
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
