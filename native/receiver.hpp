#pragma once
#include <CoreVideo/CoreVideo.h>
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
// Reads tools/live.py settings (diagnostic CLI path). Validates port range and 64-hex token.
Endpoint loadEndpoint(const std::string &configPath);
// Each run owns its socket and synchronous hardware decoder. Destruction follows run exit.
void receive(std::atomic<bool> &stop, const Endpoint &endpoint, const Output &output);
std::string defaultConfigPath();
// Optional diagnostics sink (the OBS log). At most one line a second, only when decoding falls behind.
void setLog(void (*sink)(const char *));
}
