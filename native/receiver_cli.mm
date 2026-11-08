#include "adb.hpp"
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <chrono>
#include <cstring>

static std::atomic<bool> stopped{false};
static void stop(int) { stopped = true; }
int main(int argc, char **argv) {
    // phonecam-receive CONFIG_JSON FRAMES      (tools/live.py prepare settings)
    // phonecam-receive --auto FRAMES [ADB]     (find, pair and forward automatically, like the OBS source)
    bool automatic = argc >= 3 && !strcmp(argv[1], "--auto");
    if (!(automatic ? (argc == 3 || argc == 4) : argc == 3)) {
        fprintf(stderr, "Usage: phonecam-receive CONFIG_JSON FRAME_COUNT | --auto FRAME_COUNT [ADB_PATH]\nNo media is saved. Requires an explicitly started phone stream.\n"); return 2;
    }
    char *end = nullptr;
    long requested = strtol(argv[2], &end, 10);
    if (!end || *end || requested < 1 || requested > 216000) return 2;
    signal(SIGINT, stop); signal(SIGTERM, stop);
    uint64_t frames = 0, firstPts = 0, lastPts = 0, firstSeq = 0, lastSeq = 0, sizeChanges = 0;
    size_t lastWidth = 0, lastHeight = 0;
    double leftLuma = 0, rightLuma = 0;  // mean Y of the outer quarters of the last frame
    auto began = std::chrono::steady_clock::now();
    try {
        auto onFrame = [&](CVPixelBufferRef image, uint64_t seq, uint64_t pts) {
            if (!frames) {
                firstPts = pts; firstSeq = seq;
                began = std::chrono::steady_clock::now();
                fprintf(stderr, "Hardware decoder active: %zux%zu NV12. No media saved.\n", CVPixelBufferGetWidth(image), CVPixelBufferGetHeight(image));
            }
            size_t width = CVPixelBufferGetWidth(image), height = CVPixelBufferGetHeight(image);
            if (frames && (width != lastWidth || height != lastHeight)) ++sizeChanges;
            lastWidth = width; lastHeight = height;
            if (!CVPixelBufferLockBaseAddress(image, kCVPixelBufferLock_ReadOnly)) {
                auto *y = static_cast<const uint8_t *>(CVPixelBufferGetBaseAddressOfPlane(image, 0));
                size_t stride = CVPixelBufferGetBytesPerRowOfPlane(image, 0), quarter = width / 4;
                uint64_t left = 0, right = 0;
                for (size_t row = 0; y && row < height; ++row)
                    for (size_t col = 0; col < quarter; ++col) { left += y[row * stride + col]; right += y[row * stride + width - 1 - col]; }
                CVPixelBufferUnlockBaseAddress(image, kCVPixelBufferLock_ReadOnly);
                leftLuma = quarter ? double(left) / double(quarter * height) : 0; rightLuma = quarter ? double(right) / double(quarter * height) : 0;
            }
            ++frames; lastPts = pts; lastSeq = seq;
            if (frames >= uint64_t(requested)) stopped = true;
        };
        if (automatic) {
            std::string adb = argc == 4 ? argv[3] : "";
            phonecam::autoReceive(stopped, [&] { return adb; }, [](const std::string &value) { fprintf(stderr, "%s\n", value.c_str()); }, onFrame);
        } else phonecam::receive(stopped, phonecam::loadEndpoint(argv[1]), onFrame);
        double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - began).count();
        printf("{\"decodedFrames\":%llu,\"firstSequence\":%llu,\"lastSequence\":%llu,\"firstPhonePtsUs\":%llu,\"lastPhonePtsUs\":%llu,\"receiveSpanSeconds\":%.6f,\"lastWidth\":%zu,\"lastHeight\":%zu,\"sizeChanges\":%llu,\"leftLuma\":%.2f,\"rightLuma\":%.2f,\"hardwareDecode\":%s,\"opticalLatency\":\"not_measured\"}\n",
            (unsigned long long)frames, (unsigned long long)firstSeq, (unsigned long long)lastSeq,
            (unsigned long long)firstPts, (unsigned long long)lastPts, seconds, lastWidth, lastHeight, (unsigned long long)sizeChanges, leftLuma, rightLuma, frames ? "true" : "false");
        return frames == uint64_t(requested) ? 0 : 1;
    } catch (const std::exception &error) { fprintf(stderr, "Receive failed after %llu frames: %s\n", (unsigned long long)frames, error.what()); return 1; }
}
