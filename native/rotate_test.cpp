#include "rotate.hpp"
#include <cassert>
#include <cstdio>
// 3x2 picture:  a b c      clockwise:  d a     mirror only:  c b a
//               d e f                  e b                   f e d
//                                      f c
int main() {
    using phonecam::rotatePlane;
    const uint8_t src[6] = {'a', 'b', 'c', 'd', 'e', 'f'};
    uint8_t out[6];
    rotatePlane<1>(src, 3, 3, 2, out, 2, 1, false); assert(!memcmp(out, "daebfc", 6));
    rotatePlane<1>(src, 3, 3, 2, out, 2, 3, false); assert(!memcmp(out, "cfbead", 6));
    rotatePlane<1>(src, 3, 3, 2, out, 3, 2, false); assert(!memcmp(out, "fedcba", 6));
    rotatePlane<1>(src, 3, 3, 2, out, 3, 0, true);  assert(!memcmp(out, "cbafed", 6));
    rotatePlane<1>(src, 3, 3, 2, out, 2, 1, true);  assert(!memcmp(out, "adbecf", 6));   // clockwise, then mirror
    // NV12 chroma: 2-byte UV pairs move together.
    const uint8_t uv[8] = {1, 2, 3, 4, 5, 6, 7, 8};   // 2x2 pairs: (12)(34) / (56)(78)
    uint8_t turned[8];
    rotatePlane<2>(uv, 4, 2, 2, turned, 4, 1, false);
    const uint8_t expect[8] = {5, 6, 1, 2, 7, 8, 3, 4};
    assert(!memcmp(turned, expect, 8));
    puts("PASS: CPU rotation and mirror (Y and NV12 chroma)");
}
