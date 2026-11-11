#pragma once
// CPU rotation and mirroring of one 8-bit image plane (Windows path; the Mac uses VTPixelRotationSession).
// Element is 1 byte for Y and I420 chroma, 2 bytes for NV12's interleaved UV. turns = clockwise quarter
// turns that make the picture upright; mirror flips left-right after rotating (same order as the Mac).
#include <cstdint>
#include <cstring>

namespace phonecam {
template <int Element>
void rotatePlane(const uint8_t *src, int srcStride, int width, int height, uint8_t *dst, int dstStride, int turns, bool mirror) {
    const int outWidth = (turns & 1) ? height : width, outHeight = (turns & 1) ? width : height;
    for (int y = 0; y < outHeight; ++y) {
        uint8_t *row = dst + size_t(y) * dstStride;
        for (int x = 0; x < outWidth; ++x) {
            const int rx = mirror ? outWidth - 1 - x : x;  // pixel of the rotated (not yet mirrored) picture
            int sx, sy;
            switch (turns & 3) {
                case 0: sx = rx; sy = y; break;
                case 1: sx = y; sy = height - 1 - rx; break;              // clockwise 90
                case 2: sx = width - 1 - rx; sy = height - 1 - y; break;
                default: sx = width - 1 - y; sy = rx; break;             // counter-clockwise 90
            }
            std::memcpy(row + size_t(x) * Element, src + size_t(sy) * srcStride + size_t(sx) * Element, Element);
        }
    }
}
}
