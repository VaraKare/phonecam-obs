// Windows test receiver. No media is saved.
//   phonecam-receive --file STREAM.pcam FRAMES   decode a recorded PCAM v3 stream (CI fixtures)
//   phonecam-receive --auto FRAMES [ADB]         find, pair and forward through adb, like the OBS source
//   phonecam-receive --wifi FRAMES               direct Wi-Fi: listen, open a pairing window, print it (tests)
//   phonecam-receive --card OUT.bmp              draw the Wi-Fi pairing card for a fixed example link
#include "wifi.hpp"  // first: it brings in winsock2.h before windows.h
#include "adb.hpp"
#include <chrono>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <thread>

// 32-bit top-down BMP of a BGRA image, for looking at the pairing card.
static bool writeBmp(const char *path, const std::vector<uint8_t> &pixels, uint32_t width, uint32_t height) {
    std::ofstream out(path, std::ios::binary);
    auto u16 = [&](uint32_t v) { out.put(char(v & 255)); out.put(char(v >> 8 & 255)); };
    auto u32 = [&](uint32_t v) { u16(v & 0xffff); u16(v >> 16); };
    const uint32_t size = uint32_t(pixels.size());
    out.put('B'); out.put('M'); u32(54 + size); u32(0); u32(54);
    u32(40); u32(width); u32(uint32_t(-int32_t(height))); u16(1); u16(32); u32(0); u32(size); u32(2835); u32(2835); u32(0); u32(0);
    out.write(reinterpret_cast<const char *>(pixels.data()), std::streamsize(size));
    return bool(out);
}

static std::atomic<bool> stopped{false};
int main(int argc, char **argv) {
    if (argc == 3 && !strcmp(argv[1], "--card")) {
        phonecam::wifi::PairingWindow window{"000G-40R4-0M30-E209", "phonecam://pair?v=1&id=0123456789abcdef&c=000G40R40M30E209&p=47183&a=192.168.1.20&n=PC"};
        auto pixels = phonecam::wifi::renderPairingCard(window, 1280, 720);
        return writeBmp(argv[2], pixels, 1280, 720) ? 0 : 1;
    }
    bool file = argc == 4 && !strcmp(argv[1], "--file"), automatic = (argc == 3 || argc == 4) && !strcmp(argv[1], "--auto"),
         wifi = argc == 3 && !strcmp(argv[1], "--wifi");
    if (!file && !automatic && !wifi) { fprintf(stderr, "Usage: phonecam-receive --file STREAM FRAMES | --auto FRAMES [ADB] | --wifi FRAMES | --card OUT.bmp\n"); return 2; }
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
        } else if (wifi) {
            auto say = [](const std::string &value) { fprintf(stderr, "%s\n", value.c_str()); };
            phonecam::wifi::Server server(say, onFrame, say);
            std::thread listener([&] { server.run(stopped); });
            // With nothing paired the server must not listen until a pairing window opens (AUDIT-006).
            for (int i = 0; i < 100 && server.computerId().empty(); ++i) std::this_thread::sleep_for(std::chrono::milliseconds(20));
            std::this_thread::sleep_for(std::chrono::milliseconds(300));
            fprintf(stderr, "Listening before pairing: %d\n", server.listeningPort());
            phonecam::wifi::PairingWindow window;
            try { window = server.openPairing(); }
            catch (...) { stopped = true; listener.join(); throw; }  // never leave the server thread running
            std::string code; for (char ch : window.code) if (ch != '-') code += ch;
            printf("PAIR %d %s %s\n", server.listeningPort(), server.computerId().c_str(), code.c_str());
            fflush(stdout);
            auto limit = std::chrono::steady_clock::now() + std::chrono::seconds(120);
            while (!stopped && std::chrono::steady_clock::now() < limit) std::this_thread::sleep_for(std::chrono::milliseconds(50));
            stopped = true; listener.join();
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
