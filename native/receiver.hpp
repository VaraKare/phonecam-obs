#pragma once
#include "stream.hpp"
#ifdef __APPLE__
#include <CoreVideo/CoreVideo.h>
#endif
#include <cstdint>
#include <atomic>
#include <functional>
#include <stdexcept>
#include <string>

namespace phonecam {
#ifdef __APPLE__
using Image = CVPixelBufferRef;  // hardware-decoded NV12
inline size_t imageWidth(Image image) { return CVPixelBufferGetWidth(image); }
inline size_t imageHeight(Image image) { return CVPixelBufferGetHeight(image); }
#else
// A decoded, upright picture in memory: NV12 (planes 0-1) or I420 (planes 0-2).
struct Picture { bool nv12 = true; uint32_t width = 0, height = 0; const uint8_t *plane[3] = {}; uint32_t stride[3] = {}; };
using Image = const Picture *;
inline size_t imageWidth(Image image) { return image->width; }
inline size_t imageHeight(Image image) { return image->height; }
#endif
using Output = std::function<void(Image, uint64_t, uint64_t)>;
struct Endpoint { int port = 0; std::string token; };
// Nothing listens on the Mac port: the ADB forward is gone (unplugged, USB reset, adb restarted).
struct ConnectionRefused : std::runtime_error { using std::runtime_error::runtime_error; };
// Decodes PCAM v3 messages (configuration, then frames) from any transport until stop or an error.
void decodeStream(std::atomic<bool> &stop, const Reader &read, const Output &output);
// Reads tools/live.py settings (diagnostic CLI path). Validates port range and 64-hex token.
Endpoint loadEndpoint(const std::string &configPath);
// Each run owns its socket and synchronous hardware decoder. Destruction follows run exit.
void receive(std::atomic<bool> &stop, const Endpoint &endpoint, const Output &output);
std::string defaultConfigPath();
// How the last session decodes, for the status line ("hardware decoding" or "software decoding").
std::string decoderName();
// Optional diagnostics sink (the OBS log). At most one line a second, only when decoding falls behind.
void setLog(void (*sink)(const char *));
}
