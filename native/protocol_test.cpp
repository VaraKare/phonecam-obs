#include "protocol.hpp"
#include <cassert>
#include <cstdio>
template<class F> void rejects(F f) { bool rejected = false; try { f(); } catch (...) { rejected = true; } assert(rejected); }
int main() {
    uint8_t h[32] = {0x50,0x43,0x41,0x4d,0,1,0,2,0,0,0,6,0,0,0,1};
    assert(phonecam::header(h).length == 6);
    h[5] = 2; rejects([&]{ phonecam::header(h); }); h[5] = 1;
    h[8] = 255; rejects([&]{ phonecam::header(h); }); h[8] = 0;
    h[15] = 2; rejects([&]{ phonecam::header(h); }); h[15] = 1;
    h[24] = 128; rejects([&]{ phonecam::header(h); }); h[24] = 0;
    h[7] = 1; rejects([&]{ phonecam::header(h); });
    uint8_t encoded[] = {0,0,0,1,0x67,3,0,0,1,0x68,4};
    auto nals = phonecam::nals(encoded, sizeof(encoded)); assert(nals.size() == 2 && nals[0].size() == 2);
    auto packet = phonecam::lengthPrefixed(nals); assert(packet.size() == 12 && packet[3] == 2 && packet[10] == 0x68);
    rejects([&]{ phonecam::nals(encoded + 4, 2); });
    uint8_t empty[] = {0,0,1,0,0,1,5}; rejects([&]{ phonecam::nals(empty, sizeof(empty)); });
    puts("PASS: protocol bounds, ordering fields, NAL framing");
}
