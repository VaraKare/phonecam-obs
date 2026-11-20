#pragma once
// Direct Wi-Fi security (docs/WIFI.md): pairing codes, P-256 ECDH, HKDF/HMAC-SHA-256 handshake tags and
// AES-256-CTR + HMAC records. Header-only so tests link it directly. The platform header supplies
// randomBytes, sha256, hmac, aesCtr and the Ephemeral P-256 key: CommonCrypto/Security on macOS,
// Windows CNG (BCrypt) on Windows. Everything else here is shared, with one set of test vectors.
#include <cstdint>
#include <iterator>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

namespace phonecam::wifi {
using Bytes = std::vector<uint8_t>;

constexpr const char *codeAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
constexpr size_t codeBytes = 10, keyBytes = 32, publicKeyBytes = 65, nonceBytes = 32, tagBytes = 32, recordTagBytes = 16;
constexpr size_t clientHelloBytes = 4 + 1 + 8 + 8 + publicKeyBytes + nonceBytes;           // 118
constexpr size_t serverHelloBytes = 4 + 1 + publicKeyBytes + nonceBytes + tagBytes;          // 134
constexpr uint32_t maxRecord = 4 * 1024 * 1024 + 64;

inline Bytes bytes(const std::string &text) { return Bytes(text.begin(), text.end()); }
template <class... Parts> Bytes join(const Parts &...parts) {
    Bytes out; (out.insert(out.end(), std::begin(parts), std::end(parts)), ...); return out;
}
inline void putU64(Bytes &out, uint64_t v) { for (int i = 7; i >= 0; --i) out.push_back(uint8_t(v >> (8 * i))); }
inline void putU32(Bytes &out, uint32_t v) { for (int i = 3; i >= 0; --i) out.push_back(uint8_t(v >> (8 * i))); }
}

#ifdef _WIN32
#include "wifi_crypto_win.hpp"
#else
#include "wifi_crypto_apple.hpp"
#endif

namespace phonecam::wifi {

// RFC 5869 HKDF-Expand with SHA-256.
inline Bytes hkdfExpand(const Bytes &prk, const Bytes &info, size_t length) {
    Bytes out, block;
    for (uint8_t counter = 1; out.size() < length; ++counter) {
        Bytes input = block; input.insert(input.end(), info.begin(), info.end()); input.push_back(counter);
        block = hmac(prk, input);
        out.insert(out.end(), block.begin(), block.end());
    }
    out.resize(length);
    return out;
}
inline bool equalConstantTime(const Bytes &a, const Bytes &b) {
    if (a.size() != b.size()) return false;
    uint8_t diff = 0; for (size_t i = 0; i < a.size(); ++i) diff |= uint8_t(a[i] ^ b[i]);
    return diff == 0;
}

// 10 bytes ⇄ 16 characters (5 bits each, most significant first).
inline std::string encodeCode(const Bytes &secret) {
    if (secret.size() != codeBytes) throw std::invalid_argument("pairing secret must be 10 bytes");
    std::string out; uint32_t buffer = 0; int bits = 0;
    for (uint8_t b : secret) {
        buffer = (buffer << 8) | b; bits += 8;
        while (bits >= 5) { out += codeAlphabet[(buffer >> (bits - 5)) & 31]; bits -= 5; }
    }
    return out;
}
// Accepts any case, spaces and dashes; I/L read as 1 and O as 0. Returns false for anything else.
inline bool decodeCode(const std::string &text, Bytes &secret) {
    std::string clean;
    for (char c : text) {
        if (c == ' ' || c == '-') continue;
        char u = (c >= 'a' && c <= 'z') ? char(c - 32) : c;
        if (u == 'I' || u == 'L') u = '1';
        if (u == 'O') u = '0';
        if (!std::strchr(codeAlphabet, u) || u == 0) return false;
        clean += u;
    }
    if (clean.size() != 16) return false;
    Bytes out; uint32_t buffer = 0; int bits = 0;
    for (char c : clean) {
        buffer = (buffer << 5) | uint32_t(std::strchr(codeAlphabet, c) - codeAlphabet); bits += 5;
        if (bits >= 8) { out.push_back(uint8_t(buffer >> (bits - 8))); bits -= 8; }
    }
    secret = out;
    return true;
}
inline std::string formatCode(const std::string &code) {
    std::string out;
    for (size_t i = 0; i < code.size(); ++i) { if (i && i % 4 == 0) out += '-'; out += code[i]; }
    return out;
}
inline Bytes codeKey(const Bytes &secret) { return sha256(join(bytes("PhoneCam pairing code v1"), secret)); }

struct SessionKeys { Bytes encryption, authentication, longTerm; };
// Handshake values from docs/WIFI.md. t1 = ClientHello || ServerHello without tagM.
struct Handshake {
    Bytes prk, t1, tagM, tagP;
    Handshake(const Bytes &key, const Bytes &z, Bytes transcript) : prk(hmac(key, z)), t1(std::move(transcript)) {
        tagM = hmac(prk, join(bytes("pcam mac"), sha256(t1)));
        tagP = hmac(prk, join(bytes("pcam phone"), sha256(join(t1, tagM))));
    }
    SessionKeys keys() const {
        Bytes h = sha256(join(t1, tagM, tagP));
        Bytes material = hkdfExpand(prk, join(bytes("pcam keys"), h), 64);
        return {Bytes(material.begin(), material.begin() + 32), Bytes(material.begin() + 32, material.end()),
                hkdfExpand(prk, join(bytes("pcam pairing"), h), 32)};
    }
};

inline Bytes recordTag(const Bytes &authKey, uint64_t record, const Bytes &ciphertext) {
    Bytes input; putU64(input, record); putU32(input, uint32_t(ciphertext.size()));
    input.insert(input.end(), ciphertext.begin(), ciphertext.end());
    Bytes tag = hmac(authKey, input); tag.resize(recordTagBytes);
    return tag;
}
// length || ciphertext || tag, as the phone sends it.
inline Bytes sealRecord(const SessionKeys &k, uint64_t record, const Bytes &plain) {
    Bytes cipher = aesCtr(k.encryption, record, plain.data(), plain.size());
    Bytes out; putU32(out, uint32_t(cipher.size()));
    out.insert(out.end(), cipher.begin(), cipher.end());
    Bytes tag = recordTag(k.authentication, record, cipher);
    out.insert(out.end(), tag.begin(), tag.end());
    return out;
}
// Checks the tag before decrypting; throws on any mismatch.
inline Bytes openRecord(const SessionKeys &k, uint64_t record, const Bytes &cipher, const Bytes &tag) {
    if (!equalConstantTime(recordTag(k.authentication, record, cipher), tag)) throw std::runtime_error("Wi-Fi record failed its integrity check");
    return aesCtr(k.encryption, record, cipher.data(), cipher.size());
}
}
