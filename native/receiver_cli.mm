#include "receiver.hpp"
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <chrono>

static std::atomic<bool> stopped{false};
static void stop(int) { stopped = true; }
int main(int argc, char **argv) {
    if (argc != 3) { fprintf(stderr, "Usage: phonecam-receive CONFIG_JSON FRAME_COUNT\nNo media is saved. Requires an explicitly started phone stream.\n"); return 2; }
    char *end = nullptr;
    long requested = strtol(argv[2], &end, 10);
    if (!end || *end || requested < 1 || requested > 216000) return 2;
    signal(SIGINT, stop); signal(SIGTERM, stop);
    uint64_t frames = 0, firstPts = 0, lastPts = 0, firstSeq = 0, lastSeq = 0, sizeChanges = 0;
    size_t lastWidth = 0, lastHeight = 0;
    auto began = std::chrono::steady_clock::now();
    try {
        phonecam::receive(stopped, argv[1], [&](CVPixelBufferRef image, uint64_t seq, uint64_t pts) {
            if (!frames) {
                firstPts = pts; firstSeq = seq;
                began = std::chrono::steady_clock::now();
                fprintf(stderr, "Hardware decoder active: %zux%zu NV12. No media saved.\n", CVPixelBufferGetWidth(image), CVPixelBufferGetHeight(image));
            }
            size_t width = CVPixelBufferGetWidth(image), height = CVPixelBufferGetHeight(image);
            if (frames && (width != lastWidth || height != lastHeight)) ++sizeChanges;
            lastWidth = width; lastHeight = height;
            ++frames; lastPts = pts; lastSeq = seq;
            if (frames >= uint64_t(requested)) stopped = true;
        });
        double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - began).count();
        printf("{\"decodedFrames\":%llu,\"firstSequence\":%llu,\"lastSequence\":%llu,\"firstPhonePtsUs\":%llu,\"lastPhonePtsUs\":%llu,\"receiveSpanSeconds\":%.6f,\"lastWidth\":%zu,\"lastHeight\":%zu,\"sizeChanges\":%llu,\"hardwareDecode\":true,\"opticalLatency\":\"not_measured\"}\n",
            (unsigned long long)frames, (unsigned long long)firstSeq, (unsigned long long)lastSeq,
            (unsigned long long)firstPts, (unsigned long long)lastPts, seconds, lastWidth, lastHeight, (unsigned long long)sizeChanges);
        return frames == uint64_t(requested) ? 0 : 1;
    } catch (const std::exception &error) { fprintf(stderr, "Receive failed after %llu frames: %s\n", (unsigned long long)frames, error.what()); return 1; }
}
