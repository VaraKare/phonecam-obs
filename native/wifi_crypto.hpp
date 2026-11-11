#pragma once
// Direct Wi-Fi security (docs/WIFI.md): pairing codes, P-256 ECDH, HKDF/HMAC-SHA-256 handshake tags and
// AES-256-CTR + HMAC records. Apple CommonCrypto and Security only; header-only so tests link it directly.
#include <CommonCrypto/CommonCrypto.h>
#include <CommonCrypto/CommonRandom.h>
#include <Security/Security.h>
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

inline Bytes randomBytes(size_t n) {
    Bytes out(n);
    if (CCRandomGenerateBytes(out.data(), n) != kCCSuccess) throw std::runtime_error("No secure random numbers");
    return out;
}
inline Bytes sha256(const Bytes &data) {
    Bytes out(CC_SHA256_DIGEST_LENGTH); CC_SHA256(data.data(), CC_LONG(data.size()), out.data()); return out;
}
inline Bytes hmac(const Bytes &key, const Bytes &data) {
    Bytes out(CC_SHA256_DIGEST_LENGTH);
    CCHmac(kCCHmacAlgSHA256, key.data(), key.size(), data.data(), data.size(), out.data());
    return out;
}
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

// Ephemeral P-256 key; public() is the 65-byte uncompressed point, shared() the 32-byte ECDH x coordinate.
class Ephemeral {
    SecKeyRef key = nullptr;
public:
    Ephemeral() {
        int bits = 256;
        CFNumberRef size = CFNumberCreate(nullptr, kCFNumberIntType, &bits);
        const void *keys[] = {kSecAttrKeyType, kSecAttrKeySizeInBits};
        const void *values[] = {kSecAttrKeyTypeECSECPrimeRandom, size};
        CFDictionaryRef attributes = CFDictionaryCreate(nullptr, keys, values, 2, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
        key = SecKeyCreateRandomKey(attributes, nullptr);
        CFRelease(attributes); CFRelease(size);
        if (!key) throw std::runtime_error("Could not create a session key");
    }
    ~Ephemeral() { if (key) CFRelease(key); }
    Ephemeral(const Ephemeral &) = delete; Ephemeral &operator=(const Ephemeral &) = delete;
    Bytes publicKey() const {
        SecKeyRef pub = SecKeyCopyPublicKey(key);
        CFDataRef data = pub ? SecKeyCopyExternalRepresentation(pub, nullptr) : nullptr;
        if (pub) CFRelease(pub);
        if (!data) throw std::runtime_error("Could not export the session key");
        Bytes out(CFDataGetBytePtr(data), CFDataGetBytePtr(data) + CFDataGetLength(data));
        CFRelease(data);
        if (out.size() != publicKeyBytes || out[0] != 4) throw std::runtime_error("Unexpected session key format");
        return out;
    }
    // Throws for an invalid peer point (not on the curve), so a bad key ends the handshake.
    Bytes shared(const Bytes &peer) const {
        if (peer.size() != publicKeyBytes || peer[0] != 4) throw std::runtime_error("Invalid peer session key");
        CFDataRef data = CFDataCreate(nullptr, peer.data(), CFIndex(peer.size()));
        const void *keys[] = {kSecAttrKeyType, kSecAttrKeyClass};
        const void *values[] = {kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeyClassPublic};
        CFDictionaryRef attributes = CFDictionaryCreate(nullptr, keys, values, 2, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
        SecKeyRef peerKey = SecKeyCreateWithData(data, attributes, nullptr);
        CFRelease(attributes); CFRelease(data);
        if (!peerKey) throw std::runtime_error("Invalid peer session key");
        CFDictionaryRef parameters = CFDictionaryCreate(nullptr, nullptr, nullptr, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
        CFDataRef secret = SecKeyCopyKeyExchangeResult(key, kSecKeyAlgorithmECDHKeyExchangeStandard, peerKey, parameters, nullptr);
        CFRelease(parameters); CFRelease(peerKey);
        if (!secret) throw std::runtime_error("Session key exchange failed");
        Bytes out(CFDataGetBytePtr(secret), CFDataGetBytePtr(secret) + CFDataGetLength(secret));
        CFRelease(secret);
        if (out.size() != 32) throw std::runtime_error("Unexpected shared secret size");
        return out;
    }
};

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

inline Bytes aesCtr(const Bytes &key, uint64_t record, const uint8_t *data, size_t size) {
    uint8_t iv[16] = {};
    for (int i = 0; i < 8; ++i) iv[i] = uint8_t(record >> (56 - 8 * i));
    CCCryptorRef cryptor = nullptr;
    if (CCCryptorCreateWithMode(kCCEncrypt, kCCModeCTR, kCCAlgorithmAES, ccNoPadding, iv, key.data(), key.size(),
                                nullptr, 0, 0, kCCModeOptionCTR_BE, &cryptor) != kCCSuccess) throw std::runtime_error("AES setup failed");
    Bytes out(size); size_t moved = 0;
    CCCryptorStatus status = CCCryptorUpdate(cryptor, data, size, out.data(), out.size(), &moved);
    CCCryptorRelease(cryptor);
    if (status != kCCSuccess || moved != size) throw std::runtime_error("AES failed");
    return out;
}
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
