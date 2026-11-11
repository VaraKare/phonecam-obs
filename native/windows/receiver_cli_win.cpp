// Windows test receiver. No media is saved.
//   phonecam-receive --file STREAM.pcam FRAMES   decode a recorded PCAM v3 stream (CI fixtures)
//   phonecam-receive --auto FRAMES [ADB]         find, pair and forward through adb, like the OBS source
#include "adb.hpp"
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>

static std::atomic<bool> stopped{false};
int main(int argc, char **argv) {
    bool file = argc == 4 && !strcmp(argv[1], "--file"), automatic = (argc == 3 || argc == 4) && !strcmp(argv[1], "--auto");
    if (!file && !automatic) { fprintf(stderr, "Usage: phonecam-receive --file STREAM FRAMES | --auto FRAMES [ADB]\n"); return 2; }
    long requested = strtol(argv[file ? 3 : 2], nullptr, 10);
    if (requested < 1 || requested > 216000) return 2;
    uint64_t frames = 0; size_t width = 0, height = 0; double leftLuma = 0, rightLuma = 0;
    auto onFrame = [&](phonecam::Image image, uint64_t, uint64_t) {
        width = image->width; height = image->height;
        size_t quarter = width / 4; uint64_t left = 0, right = 0;
        for (size_t row = 0; row < height; ++row) {
            const uint8_t *y = image->plane[0] + row * image->stride[0];
            for (size_t col = 0; col < quarter; ++col) { left += y[col]; right += y[width - 1 - col]; }
        }
        leftLuma = quarter ? double(left) / double(quarter * height) : 0; rightLuma = quarter ? double(right) / double(quarter * height) : 0;
        if (++frames >= uint64_t(requested)) stopped = true;
    };
    try {
        if (file) {
            std::ifstream in(argv[2], std::ios::binary);
            std::vector<uint8_t> data((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
            size_t position = 0;
            phonecam::decodeStream(stopped, [&](uint8_t *p, size_t n, int) {
                if (position + n > data.size()) throw phonecam::Closed("end of recording");
                memcpy(p, data.data() + position, n); position += n;
            }, onFrame);
        } else {
            std::string adb = argc == 4 ? argv[3] : "";
            phonecam::autoReceive(stopped, [&] { return adb; }, [](const std::string &value) { fprintf(stderr, "%s\n", value.c_str()); }, onFrame);
        }
    } catch (const std::exception &error) {
        if (frames < uint64_t(requested)) { fprintf(stderr, "Receive failed after %llu frames: %s\n", (unsigned long long)frames, error.what()); }
    }
    printf("{\"decodedFrames\":%llu,\"lastWidth\":%zu,\"lastHeight\":%zu,\"leftLuma\":%.2f,\"rightLuma\":%.2f,\"decoder\":\"%s\"}\n",
           (unsigned long long)frames, width, height, leftLuma, rightLuma, phonecam::decoderName().c_str());
    return frames == uint64_t(requested) ? 0 : 1;
}
