#pragma once
// Windows primitives for wifi_crypto.hpp: Windows CNG (BCrypt, Windows 10+), built into Windows.
// Included only from wifi_crypto.hpp; same test vectors as the Mac and the phone (wifi_test.cpp).
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>  // before windows.h, for files that also use sockets
#include <windows.h>
#include <bcrypt.h>
#include <algorithm>

namespace phonecam::wifi {
namespace cng {
inline bool ok(NTSTATUS status) { return status >= 0; }
inline PUCHAR in(const uint8_t *p) { return const_cast<PUCHAR>(p); }  // BCrypt takes non-const input pointers
// Owns one BCrypt key handle.
struct Key {
    BCRYPT_KEY_HANDLE handle = nullptr;
    Key() = default;
    Key(const Key &) = delete; Key &operator=(const Key &) = delete;
    ~Key() { if (handle) BCryptDestroyKey(handle); }
};
}

inline Bytes randomBytes(size_t n) {
    Bytes out(n);
    if (n && !cng::ok(BCryptGenRandom(nullptr, out.data(), ULONG(n), BCRYPT_USE_SYSTEM_PREFERRED_RNG)))
        throw std::runtime_error("No secure random numbers");
    return out;
}
inline Bytes sha256(const Bytes &data) {
    Bytes out(32);
    if (!cng::ok(BCryptHash(BCRYPT_SHA256_ALG_HANDLE, nullptr, 0, cng::in(data.data()), ULONG(data.size()), out.data(), 32)))
        throw std::runtime_error("SHA-256 failed");
    return out;
}
inline Bytes hmac(const Bytes &key, const Bytes &data) {
    Bytes out(32);
    if (!cng::ok(BCryptHash(BCRYPT_HMAC_SHA256_ALG_HANDLE, cng::in(key.data()), ULONG(key.size()), cng::in(data.data()), ULONG(data.size()), out.data(), 32)))
        throw std::runtime_error("HMAC failed");
    return out;
}
// AES-256 in counter mode, built from AES-ECB over the counter blocks; IV = record number (big-endian
// u64) followed by a big-endian block counter starting at 0, as CommonCrypto's CTR mode counts.
inline Bytes aesCtr(const Bytes &key, uint64_t record, const uint8_t *data, size_t size) {
    cng::Key aes;
    if (!cng::ok(BCryptGenerateSymmetricKey(BCRYPT_AES_ECB_ALG_HANDLE, &aes.handle, nullptr, 0, cng::in(key.data()), ULONG(key.size()), 0)))
        throw std::runtime_error("AES setup failed");
    const size_t blocks = (size + 15) / 16;
    Bytes stream(blocks * 16);
    for (size_t b = 0; b < blocks; ++b) {
        uint8_t *counter = stream.data() + b * 16;
        for (int i = 0; i < 8; ++i) counter[i] = uint8_t(record >> (56 - 8 * i));
        for (int i = 0; i < 8; ++i) counter[8 + i] = uint8_t(uint64_t(b) >> (56 - 8 * i));
    }
    ULONG written = 0;
    if (blocks && !cng::ok(BCryptEncrypt(aes.handle, stream.data(), ULONG(stream.size()), nullptr, nullptr, 0,
                                         stream.data(), ULONG(stream.size()), &written, 0)))
        throw std::runtime_error("AES failed");
    Bytes out(size);
    for (size_t i = 0; i < size; ++i) out[i] = uint8_t(data[i] ^ stream[i]);
    return out;
}

// Ephemeral P-256 key; publicKey() is the 65-byte uncompressed point, shared() the 32-byte ECDH x coordinate.
class Ephemeral {
    cng::Key key;
public:
    Ephemeral() {
        if (!cng::ok(BCryptGenerateKeyPair(BCRYPT_ECDH_P256_ALG_HANDLE, &key.handle, 256, 0)) || !cng::ok(BCryptFinalizeKeyPair(key.handle, 0)))
            throw std::runtime_error("Could not create a session key");
    }
    Ephemeral(const Ephemeral &) = delete; Ephemeral &operator=(const Ephemeral &) = delete;
    Bytes publicKey() const {
        Bytes blob(sizeof(BCRYPT_ECCKEY_BLOB) + 64);
        ULONG size = 0;
        if (!cng::ok(BCryptExportKey(key.handle, nullptr, BCRYPT_ECCPUBLIC_BLOB, blob.data(), ULONG(blob.size()), &size, 0)) || size != blob.size())
            throw std::runtime_error("Could not export the session key");
        Bytes out{4};
        out.insert(out.end(), blob.begin() + sizeof(BCRYPT_ECCKEY_BLOB), blob.end());
        return out;
    }
    // Throws for an invalid peer point (BCrypt checks it is on the curve), so a bad key ends the handshake.
    Bytes shared(const Bytes &peer) const {
        if (peer.size() != publicKeyBytes || peer[0] != 4) throw std::runtime_error("Invalid peer session key");
        Bytes blob(sizeof(BCRYPT_ECCKEY_BLOB));
        BCRYPT_ECCKEY_BLOB header{BCRYPT_ECDH_PUBLIC_P256_MAGIC, 32};
        std::memcpy(blob.data(), &header, sizeof(header));
        blob.insert(blob.end(), peer.begin() + 1, peer.end());
        cng::Key peerKey;
        if (!cng::ok(BCryptImportKeyPair(BCRYPT_ECDH_P256_ALG_HANDLE, nullptr, BCRYPT_ECCPUBLIC_BLOB, &peerKey.handle, blob.data(), ULONG(blob.size()), 0)))
            throw std::runtime_error("Invalid peer session key");
        BCRYPT_SECRET_HANDLE secret = nullptr;
        if (!cng::ok(BCryptSecretAgreement(key.handle, peerKey.handle, &secret, 0))) throw std::runtime_error("Session key exchange failed");
        Bytes out(32);
        ULONG size = 0;
        NTSTATUS status = BCryptDeriveKey(secret, BCRYPT_KDF_RAW_SECRET, nullptr, out.data(), ULONG(out.size()), &size, 0);
        BCryptDestroySecret(secret);
        if (!cng::ok(status) || size != 32) throw std::runtime_error("Session key exchange failed");
        std::reverse(out.begin(), out.end());  // BCrypt returns the raw secret little-endian; ECDH uses big-endian x
        return out;
    }
};
}
