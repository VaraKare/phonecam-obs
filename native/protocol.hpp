#pragma once
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace phonecam {
constexpr uint32_t maxPayload = 4 * 1024 * 1024;
inline uint32_t be32(const uint8_t *p) { return uint32_t(p[0]) << 24 | uint32_t(p[1]) << 16 | uint32_t(p[2]) << 8 | p[3]; }
inline uint64_t be64(const uint8_t *p) { return uint64_t(be32(p)) << 32 | be32(p + 4); }
struct Header { uint16_t type; uint32_t length, flags; uint64_t sequence, pts; };
constexpr uint16_t version = 3;
// Message types. Phone → computer: 1 configuration, 2 frame, 3 audio (0.6). Both ways after the hello (0.6):
// 4 control text ("hello", "status", commands). Older phones send only 1 and 2 and never read; older plugins
// never send a hello, so a 0.6 phone sends them only 1 and 2 (docs/USB_LIVE.md).
enum : uint16_t { Configuration = 1, Frame = 2, Audio = 3, Control = 4 };
constexpr uint32_t maxControl = 4096, maxAudio = 65536;
// v3 frame flags: bit 0 keyframe; bits 1-2 clockwise quarter turns for upright display;
// bit 3 mirror the upright picture horizontally (phone's "Mirror in OBS" switch).
inline Header header(const uint8_t *p) {
    if (be32(p) != 0x5043414d) throw std::runtime_error("Invalid PCAM magic");
    if (p[4] != 0 || p[5] != version)
        throw std::runtime_error("Phone app and OBS plugin use different PCAM versions; install the matching app and plugin");
    if (p[6] != 0 || p[7] < Configuration || p[7] > Control) throw std::runtime_error("Invalid PCAM type");
    Header h{p[7], be32(p + 8), be32(p + 12), be64(p + 16), be64(p + 24)};
    if (!h.length || h.length > maxPayload || h.flags > 15 || h.pts > INT64_MAX ||
        (h.type == Configuration && (h.length < 20 || h.length > 65536 || h.flags || h.sequence || h.pts)) ||
        (h.type == Audio && (h.length <= 8 || h.length > maxAudio || h.flags)) ||
        (h.type == Control && (h.length > maxControl || h.flags || h.sequence || h.pts)))
        throw std::runtime_error("Invalid PCAM length/flags/timestamp");
    return h;
}
inline void put32(std::vector<uint8_t> &out, uint32_t v) { for (int s = 24; s >= 0; s -= 8) out.push_back(uint8_t(v >> s)); }
inline void put64(std::vector<uint8_t> &out, uint64_t v) { put32(out, uint32_t(v >> 32)); put32(out, uint32_t(v)); }
// One whole message: the 32-byte header, then the body.
inline std::vector<uint8_t> message(uint16_t type, const std::vector<uint8_t> &body, uint64_t sequence = 0, uint64_t pts = 0) {
    std::vector<uint8_t> out{'P', 'C', 'A', 'M', 0, uint8_t(version), uint8_t(type >> 8), uint8_t(type)};
    put32(out, uint32_t(body.size())); put32(out, 0); put64(out, sequence); put64(out, pts);
    out.insert(out.end(), body.begin(), body.end());
    return out;
}
inline std::vector<uint8_t> control(const std::string &text) { return message(Control, std::vector<uint8_t>(text.begin(), text.end())); }
// What a 0.6 plugin sends right after connecting. A 0.6 phone answers with its own "hello"; older phones ignore it.
constexpr const char *pluginHello = "hello\nfeatures=control,audio,screen\n";
// Audio body: u32 sample rate, u16 channels, u16 format (1 = signed 16-bit little-endian), then the samples.
struct AudioChunk { uint32_t rate; uint16_t channels; const uint8_t *pcm; size_t bytes; };
inline AudioChunk audioChunk(const std::vector<uint8_t> &body) {
    if (body.size() <= 8) throw std::runtime_error("Invalid PCAM audio");
    AudioChunk a{be32(body.data()), uint16_t(body[4] << 8 | body[5]), body.data() + 8, body.size() - 8};
    uint16_t format = uint16_t(body[6] << 8 | body[7]);
    if (a.rate < 8000 || a.rate > 96000 || a.channels < 1 || a.channels > 2 || format != 1 || a.bytes % (2u * a.channels))
        throw std::runtime_error("Invalid PCAM audio format");
    return a;
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
