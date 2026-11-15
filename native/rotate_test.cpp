#include "rotate.hpp"
#include <cassert>
#include <cstdio>
#include <vector>

template <int E>
static bool check(int width, int height, int turns, bool mirror) {
    const int srcStride = width * E + 5;
    std::vector<uint8_t> src(size_t(srcStride) * height);
    for (size_t i = 0; i < src.size(); ++i) src[i] = uint8_t(i * 131 + 7);
    const int outWidth = (turns & 1) ? height : width, outHeight = (turns & 1) ? width : height;
    const int dstStride = outWidth * E + 3;
    std::vector<uint8_t> got(size_t(dstStride) * outHeight, 0), want(got.size(), 0);
    // Two row ranges, as the Windows receiver splits a frame across threads.
    const int split = outHeight / 3;
    phonecam::rotatePlane<E>(src.data(), srcStride, width, height, got.data(), dstStride, turns, mirror, 0, split);
    phonecam::rotatePlane<E>(src.data(), srcStride, width, height, got.data(), dstStride, turns, mirror, split, -1);
    for (int y = 0; y < outHeight; ++y) for (int x = 0; x < outWidth; ++x) {
        const int rx = mirror ? outWidth - 1 - x : x;
        int sx, sy;
        switch (turns) {
            case 0: sx = rx; sy = y; break;
            case 1: sx = y; sy = height - 1 - rx; break;
            case 2: sx = width - 1 - rx; sy = height - 1 - y; break;
            default: sx = width - 1 - y; sy = rx; break;
        }
        memcpy(&want[size_t(y) * dstStride + size_t(x) * E], &src[size_t(sy) * srcStride + size_t(sx) * E], E);
    }
    return got == want;
}
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
    // The tiled version against a pixel-by-pixel reference: odd sizes, padded strides, partial tiles.
    const int sizes[][2] = {{1, 1}, {7, 3}, {65, 33}, {130, 97}, {200, 64}};
    for (auto size : sizes) for (int turns = 0; turns < 4; ++turns) for (int mirror = 0; mirror < 2; ++mirror) {
        if (!check<1>(size[0], size[1], turns, mirror) || !check<2>(size[0], size[1], turns, mirror)) {
            printf("FAIL: %dx%d turns %d mirror %d\n", size[0], size[1], turns, mirror); return 1;
        }
    }
    puts("PASS: CPU rotation and mirror (Y and NV12 chroma, tiled = reference)");
}
