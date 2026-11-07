#pragma once
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace phonecam {
constexpr uint32_t maxPayload = 4 * 1024 * 1024;
inline uint32_t be32(const uint8_t *p) { return uint32_t(p[0]) << 24 | uint32_t(p[1]) << 16 | uint32_t(p[2]) << 8 | p[3]; }
inline uint64_t be64(const uint8_t *p) { return uint64_t(be32(p)) << 32 | be32(p + 4); }
struct Header { uint16_t type; uint32_t length, flags; uint64_t sequence, pts; };
constexpr uint16_t version = 2;
// v2 frame flags: bit 0 keyframe; bits 1-2 clockwise quarter turns for upright display.
inline Header header(const uint8_t *p) {
    if (be32(p) != 0x5043414d) throw std::runtime_error("Invalid PCAM magic");
    if (p[4] != 0 || p[5] != version)
        throw std::runtime_error("Phone app and OBS plugin use different PCAM versions; install the matching app and plugin");
    if (p[6] != 0 || (p[7] != 1 && p[7] != 2)) throw std::runtime_error("Invalid PCAM type");
    Header h{p[7], be32(p + 8), be32(p + 12), be64(p + 16), be64(p + 24)};
    if (!h.length || h.length > maxPayload || h.flags > 7 || h.pts > INT64_MAX ||
        (h.type == 1 && (h.length < 20 || h.length > 65536 || h.flags || h.sequence || h.pts)))
        throw std::runtime_error("Invalid PCAM length/flags/timestamp");
    return h;
}
// Annex-B start codes delimit NAL units. Enforce limits before passing any data to VT.
inline std::vector<std::vector<uint8_t>> nals(const uint8_t *p, size_t length) {
    if (length > maxPayload) throw std::runtime_error("NAL payload too large");
    auto prefix = [&](size_t i) -> size_t {
        if (i + 3 <= length && !p[i] && !p[i + 1] && p[i + 2] == 1) return 3;
        if (i + 4 <= length && !p[i] && !p[i + 1] && !p[i + 2] && p[i + 3] == 1) return 4;
        return 0;
    };
    std::vector<std::vector<uint8_t>> result;
    size_t start = prefix(0), i = start;
    if (!start) throw std::runtime_error("Expected Annex-B encoder output");
    while (true) {
        if (i == length || prefix(i)) {
            size_t end = i;
            while (end > start && !p[end - 1]) --end;
            if (end <= start || result.size() >= 256) throw std::runtime_error("Empty/excess NAL units");
            result.emplace_back(p + start, p + end);
            if (i == length) break;
            start = i + prefix(i); i = start;
        } else ++i;
    }
    return result;
}
inline std::vector<uint8_t> lengthPrefixed(const std::vector<std::vector<uint8_t>> &units) {
    std::vector<uint8_t> out;
    for (const auto &n : units) {
        uint32_t size = uint32_t(n.size());
        out.insert(out.end(), {uint8_t(size >> 24), uint8_t(size >> 16), uint8_t(size >> 8), uint8_t(size)});
        out.insert(out.end(), n.begin(), n.end());
    }
    return out;
}
}
