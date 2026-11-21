#include "protocol.hpp"
#include <cassert>
#include <string>
#include <cstdio>
template<class F> void rejects(F f) { bool rejected = false; try { f(); } catch (...) { rejected = true; } assert(rejected); }
int main() {
    uint8_t h[32] = {0x50,0x43,0x41,0x4d,0,3,0,2,0,0,0,6,0,0,0,1};
    assert(phonecam::header(h).length == 6);
    h[5] = 2; rejects([&]{ phonecam::header(h); }); h[5] = 4; rejects([&]{ phonecam::header(h); }); h[5] = 3;
    h[8] = 255; rejects([&]{ phonecam::header(h); }); h[8] = 0;
    h[15] = 7; assert(phonecam::header(h).flags >> 1 == 3);
    h[15] = 15; assert((phonecam::header(h).flags >> 3) == 1);
    h[15] = 16; rejects([&]{ phonecam::header(h); }); h[15] = 1;
    h[24] = 128; rejects([&]{ phonecam::header(h); }); h[24] = 0;
    h[7] = 1; rejects([&]{ phonecam::header(h); });
    // 0.6: audio (3) and control (4) messages, built and checked.
    auto hello = phonecam::control(phonecam::pluginHello);
    auto hh = phonecam::header(hello.data());
    assert(hh.type == phonecam::Control && hh.length == hello.size() - 32);
    auto big = phonecam::control(std::string(phonecam::maxControl + 1, 'x')); rejects([&]{ phonecam::header(big.data()); });
    std::vector<uint8_t> chunk{0, 0, 0xbb, 0x80, 0, 1, 0, 1, 1, 2, 3, 4};  // 48000 Hz, mono, s16le, 2 samples
    auto sound = phonecam::message(phonecam::Audio, chunk, 7, 1000);
    auto sh = phonecam::header(sound.data()); assert(sh.type == phonecam::Audio && sh.pts == 1000);
    auto a = phonecam::audioChunk(chunk); assert(a.rate == 48000 && a.channels == 1 && a.bytes == 4);
    chunk[7] = 2; rejects([&]{ phonecam::audioChunk(chunk); }); chunk[7] = 1;
    chunk.pop_back(); rejects([&]{ phonecam::audioChunk(chunk); });  // half a sample
    sound[15] = 1; rejects([&]{ phonecam::header(sound.data()); });  // audio has no flags
    h[7] = 5; rejects([&]{ phonecam::header(h); }); h[7] = 2;
    uint8_t encoded[] = {0,0,0,1,0x67,3,0,0,1,0x68,4};
    auto nals = phonecam::nals(encoded, sizeof(encoded)); assert(nals.size() == 2 && nals[0].size() == 2);
    auto packet = phonecam::lengthPrefixed(nals); assert(packet.size() == 12 && packet[3] == 2 && packet[10] == 0x68);
    rejects([&]{ phonecam::nals(encoded + 4, 2); });
    uint8_t empty[] = {0,0,1,0,0,1,5}; rejects([&]{ phonecam::nals(empty, sizeof(empty)); });
    puts("PASS: protocol v3 bounds, audio and control messages, rotation and mirror flags, ordering fields, NAL framing");
}
