#pragma once
// CPU rotation and mirroring of one 8-bit image plane (Windows path; the Mac uses VTPixelRotationSession).
// Element is 1 byte for Y and I420 chroma, 2 bytes for NV12's interleaved UV. turns = clockwise quarter
// turns that make the picture upright; mirror flips left-right after rotating (same order as the Mac).
//
// Every output row reads the source along a straight line: a start pointer plus a fixed step. Quarter
// turns step down source columns, so they are done in tiles that keep the touched source lines in cache
// (about twice as fast as pixel by pixel for a 4K frame on an M1).
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace phonecam {
// firstRow/lastRow limit the work to output rows [firstRow, lastRow) so a frame can be split across threads;
// lastRow < 0 means the last output row.
template <int Element>
void rotatePlane(const uint8_t *src, int srcStride, int width, int height, uint8_t *dst, int dstStride, int turns, bool mirror,
                 int firstRow = 0, int lastRow = -1) {
    using Pixel = uint8_t[Element];
    const ptrdiff_t stride = srcStride, element = Element;
    const int outWidth = (turns & 1) ? height : width;
    const int outHeight = lastRow >= 0 && lastRow < ((turns & 1) ? width : height) ? lastRow : ((turns & 1) ? width : height);
    // Source of output pixel (0, y) and the step to the next output pixel in the same row.
    const uint8_t *origin; ptrdiff_t rowStep, step;   // origin of row y = origin + y * rowStep
    switch (turns & 3) {
        case 0: origin = src + (mirror ? (width - 1) * element : 0); rowStep = stride; step = mirror ? -element : element; break;
        case 1: origin = src + (mirror ? 0 : (height - 1) * stride); rowStep = element; step = mirror ? stride : -stride; break;
        case 2: origin = src + (height - 1) * stride + (mirror ? 0 : (width - 1) * element); rowStep = -stride; step = mirror ? element : -element; break;
        default: origin = src + (width - 1) * element + (mirror ? (height - 1) * stride : 0); rowStep = -element; step = mirror ? -stride : stride; break;
    }
    auto copy = [&](int y, int from, int to) {
        uint8_t *out = dst + size_t(y) * dstStride + size_t(from) * Element;
        const uint8_t *in = origin + ptrdiff_t(y) * rowStep + ptrdiff_t(from) * step;
        if (step == element) { std::memcpy(out, in, size_t(to - from) * Element); return; }
        for (int x = from; x < to; ++x, out += Element, in += step) std::memcpy(out, in, sizeof(Pixel));
    };
    if (!(turns & 1)) {   // rows map to rows: sequential reads already
        for (int y = firstRow; y < outHeight; ++y) copy(y, 0, outWidth);
        return;
    }
    constexpr int tileRows = 32, tileColumns = 64;
    for (int top = firstRow; top < outHeight; top += tileRows) {
        const int bottom = top + tileRows < outHeight ? top + tileRows : outHeight;
        for (int left = 0; left < outWidth; left += tileColumns) {
            const int right = left + tileColumns < outWidth ? left + tileColumns : outWidth;
            for (int y = top; y < bottom; ++y) copy(y, left, right);
        }
    }
}
}
