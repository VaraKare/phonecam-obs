#pragma once
// The PCAM v3 receive loop shared by macOS and Windows: reads messages from any transport (USB socket or
// decrypted Wi-Fi records), checks order and keyframes, and hands configuration and frames to the
// platform decoder. Portable C++17; the decoder supplies configure(body) and frame(body, header).
#include "protocol.hpp"
#include <algorithm>
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

// The phone's battery and heat from its latest 0.6 status, for the live summary. battery -1 and heat empty: not known
// (an older phone, or no status yet). A phone that sends "heat=" is believed; 0.6 phones put heat into "warning".
struct PhoneHealth {
    int battery = -1; bool charging = false; std::string heat;
    int soundPeak = -1;  // loudest phone sound since the last summary, % of full scale; -1: no sound received
    std::string mic, sound;  // what the phone says is switched on ("on"/"off"; empty: not reported)
    static PhoneHealth fromStatus(const std::string &text) {
        PhoneHealth out; out.heat = "normal";
        std::string warning; bool heatGiven = false;
        size_t start = 0;
        while (start < text.size()) {
            size_t end = text.find('\n', start);
            if (end == std::string::npos) end = text.size();
            std::string line = text.substr(start, end - start);
            start = end + 1;
            auto eq = line.find('=');
            if (eq == std::string::npos) continue;
            std::string key = line.substr(0, eq), value = line.substr(eq + 1, 200);
            if (key == "battery" && !value.empty() && value.size() <= 3 && value.find_first_not_of("0123456789") == std::string::npos)
                out.battery = std::stoi(value);
            else if (key == "charging") out.charging = value == "yes";
            else if ((key == "mic" || key == "sound") && (value == "on" || value == "off")) (key == "mic" ? out.mic : out.sound) = value;
            else if (key == "warning") warning = value;
            else if (key == "heat" && !value.empty() && value.size() <= 16 && value.find_first_not_of("abcdefghijklmnopqrstuvwxyz") == std::string::npos) {
                out.heat = value; heatGiven = true;
            }
        }
        if (!heatGiven && warning.find("hot") != std::string::npos) out.heat = "hot";
        else if (!heatGiven && warning.find("warm") != std::string::npos) out.heat = "warm";
        return out;
    }
};

// Bounded telemetry while live: one log line a minute (docs: audit/REMEDIATION_LOG.md, v0.7). Decode time is the
// decode + rotate + hand-off time per frame on this computer; over 33 ms falls behind at 30 fps.
class LiveSummary {
public:
    void shown() { ++frames; }
    void timed(double ms) { ++timedFrames; if (ms > 33.3) ++slow; if (ms > slowest) slowest = ms; }
    void gap(uint64_t missing) { ++gaps; lost += missing; }
    void skipped() { ++waited; }
    bool empty() const { return !frames && !gaps && !waited; }
    std::string line(const char *machine, double seconds, const PhoneHealth &phone) const {
        char text[320];
        int n = snprintf(text, sizeof(text), "Live summary (%s, last %.0f s): %llu frames shown, %llu sequence gaps (%llu frames missing, "
                         "%llu skipped until a keyframe), %llu of %llu over 33 ms to decode, slowest %.0f ms", machine, seconds,
                         (unsigned long long)frames, (unsigned long long)gaps, (unsigned long long)lost, (unsigned long long)waited,
                         (unsigned long long)slow, (unsigned long long)timedFrames, slowest);
        std::string out(text, n > 0 ? std::min(size_t(n), sizeof(text) - 1) : 0);
        if (phone.battery >= 0) out += "; phone battery " + std::to_string(phone.battery) + " %" + (phone.charging ? " (charging)" : "");
        if (!phone.heat.empty()) out += (phone.battery >= 0 ? ", heat " : "; phone heat ") + phone.heat;
        if (!phone.mic.empty()) out += "; phone mic " + phone.mic;
        if (!phone.sound.empty()) out += ", phone sound " + phone.sound;
        // Tells silence (an app that doesn't allow capture, a muted phone) from an OBS mixer setting (22 Nov).
        if ((phone.mic == "on" || phone.sound == "on") && phone.soundPeak < 0) out += "; but no sound arrived from the phone";
        if (phone.soundPeak == 0) out += "; phone sound received but silent";
        else if (phone.soundPeak > 0) out += "; phone sound peak " + std::to_string(phone.soundPeak) + " %";
        return out + ".";
    }
private:
    uint64_t frames = 0, timedFrames = 0, slow = 0, gaps = 0, lost = 0, waited = 0;
    double slowest = 0;
};

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
        { std::lock_guard<std::mutex> guard(healthLock); latest = {}; }
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
    // The phone's latest health, and the loudest sound since the previous call (then reset).
    PhoneHealth health() {
        PhoneHealth out;
        { std::lock_guard<std::mutex> guard(healthLock); out = latest; }
        out.soundPeak = peak.exchange(-1);
        return out;
    }
    void received(const Header &h, const std::vector<uint8_t> &body) {
        if (h.type == Control) {
            std::string text(body.begin(), body.end());
            if (text.rfind("hello", 0) == 0) { phone = true; std::lock_guard<std::mutex> guard(healthLock); latest = {}; }
            if (text.rfind("status\n", 0) == 0) { auto next = PhoneHealth::fromStatus(text); std::lock_guard<std::mutex> guard(healthLock); latest = next; }
            std::lock_guard<std::mutex> guard(cbLock);
            if (callbacks.control) callbacks.control(text);
        } else if (h.type == Audio) {
            AudioChunk chunk = audioChunk(body);
            int loudest = 0;
            for (size_t i = 0; i + 1 < chunk.bytes; i += 2) {
                int sample = int16_t(uint16_t(chunk.pcm[i]) | uint16_t(chunk.pcm[i + 1]) << 8);
                loudest = std::max(loudest, sample < 0 ? -sample : sample);
            }
            int percent = loudest * 100 / 32767, seen = peak.load();
            while (percent > seen && !peak.compare_exchange_weak(seen, percent)) {}
            std::lock_guard<std::mutex> guard(cbLock);
            if (callbacks.audio) callbacks.audio(chunk, h.pts);
        }
    }
private:
    std::mutex writeLock, cbLock, healthLock;
    Writer writer;
    Callbacks callbacks;
    PhoneHealth latest;  // under healthLock
    std::atomic<bool> phone{false};
    std::atomic<int> peak{-1};
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
    // Once a minute while live, and once more for the part of a minute when the session ends.
    LiveSummary minute;
    bool started = false;  // a frame arrived in this session
    auto minuteStart = std::chrono::steady_clock::now();
    auto summarise = [&](std::chrono::steady_clock::time_point now) {
        if (log && !minute.empty()) {
            const double seconds = std::chrono::duration<double>(now - minuteStart).count();
            log(minute.line(machine, seconds, channel ? channel->health() : PhoneHealth()).c_str());
        }
        minute = LiveSummary(); minuteStart = now;
    };
    struct AtEnd {
        std::function<void()> run;
        ~AtEnd() { try { run(); } catch (...) {} }  // runs while an exception leaves: must not throw
    } atEnd{[&] { if (started) summarise(std::chrono::steady_clock::now()); }};
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
            if (seen && h.sequence != lastSequence + 1) { waitingForKey = true; minute.gap(h.sequence - lastSequence - 1); }
            if (!started) { started = true; minuteStart = std::chrono::steady_clock::now(); }  // from the first frame
            seen = true; lastSequence = h.sequence; lastPts = h.pts;
            if (waitingForKey && !(h.flags & 1)) { minute.skipped(); continue; }
            waitingForKey = false;
            auto began = std::chrono::steady_clock::now();
            decoder.frame(body, h);
            auto now = std::chrono::steady_clock::now();
            double ms = std::chrono::duration<double, std::milli>(now - began).count();
            minute.shown();
            const bool counted = warmedUp;
            if (counted) minute.timed(ms);
            if (now - minuteStart >= std::chrono::minutes(1)) summarise(now);
            if (!counted) { warmedUp = true; windowStart = now; continue; }
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
