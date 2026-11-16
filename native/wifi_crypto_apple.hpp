#pragma once
// Apple primitives for wifi_crypto.hpp: CommonCrypto and Security. Included only from wifi_crypto.hpp.
#include <CommonCrypto/CommonCrypto.h>
#include <CommonCrypto/CommonRandom.h>
#include <Security/Security.h>

namespace phonecam::wifi {
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
// AES-256 in counter mode; IV = record number (big-endian u64) followed by 8 zero bytes.
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

}
