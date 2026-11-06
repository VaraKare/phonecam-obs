#pragma once
#include <CoreVideo/CoreVideo.h>
#include <atomic>
#include <functional>
#include <string>

namespace phonecam {
using Output = std::function<void(CVPixelBufferRef, uint64_t, uint64_t)>;
// Each run owns its socket and synchronous hardware decoder. Destruction follows run exit.
void receive(std::atomic<bool> &stop, const std::string &configPath, const Output &output);
std::string defaultConfigPath();
}
