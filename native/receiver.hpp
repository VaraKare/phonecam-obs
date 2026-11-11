#pragma once
#include <CoreVideo/CoreVideo.h>
#include <cstdint>
#include <atomic>
#include <functional>
#include <stdexcept>
#include <string>

namespace phonecam {
using Output = std::function<void(CVPixelBufferRef, uint64_t, uint64_t)>;
struct Endpoint { int port = 0; std::string token; };
// Nothing listens on the Mac port: the ADB forward is gone (unplugged, USB reset, adb restarted).
struct ConnectionRefused : std::runtime_error { using std::runtime_error::runtime_error; };
// The forward exists but the phone closed it before any video: no session started yet (or a stale key).
struct NotStreaming : std::runtime_error { using std::runtime_error::runtime_error; };
// The other end closed the connection (as opposed to a timeout or bad data).
struct Closed : std::runtime_error { using std::runtime_error::runtime_error; };
// Reads exactly n bytes within the given seconds, or throws (Closed when the peer hung up).
using Reader = std::function<void(uint8_t *, size_t, int)>;
// Decodes PCAM v3 messages (configuration, then frames) from any transport until stop or an error.
void decodeStream(std::atomic<bool> &stop, const Reader &read, const Output &output);
// Reads tools/live.py settings (diagnostic CLI path). Validates port range and 64-hex token.
Endpoint loadEndpoint(const std::string &configPath);
// Each run owns its socket and synchronous hardware decoder. Destruction follows run exit.
void receive(std::atomic<bool> &stop, const Endpoint &endpoint, const Output &output);
std::string defaultConfigPath();
// Optional diagnostics sink (the OBS log). At most one line a second, only when decoding falls behind.
void setLog(void (*sink)(const char *));
}
