#include "wifi_crypto.hpp"
#include <cassert>
#include <cstdio>
// Expected values come from an independent Python + openssl computation (see docs/WIFI.md); the phone's
// Kotlin tests check the same numbers, so both ends provably agree.
using namespace phonecam::wifi;
static Bytes hex(const char *text) {
    Bytes out; for (size_t i = 0; text[i] && text[i + 1]; i += 2) { unsigned v; sscanf(text + i, "%2x", &v); out.push_back(uint8_t(v)); }
    return out;
}
int main() {
    Bytes secret; for (int i = 0; i < 10; ++i) secret.push_back(uint8_t(i));
    assert(encodeCode(secret) == "000G40R40M30E209");
    assert(formatCode("000G40R40M30E209") == "000G-40R4-0M30-E209");
    Bytes decoded;
    assert(decodeCode("000g-40r4 0m30-e2o9", decoded) && decoded == secret);   // case, dash, space, O→0
    assert(!decodeCode("000G40R40M30E20", decoded) && !decodeCode("000G40R40M30E209X", decoded) && !decodeCode("000G40R40M30E20U", decoded));
    Bytes key = codeKey(secret);
    assert(key == hex("afcbadc63d79cf9d9edf4fb8198b8746c27f375fdd84d0c74ef153e3d10ca33e"));

    Bytes z(32, 0x11), t1; for (int i = 0; i < 250; ++i) t1.push_back(uint8_t(i));
    Handshake h(key, z, t1);
    assert(h.tagM == hex("c4404f67efd6ec3b22b9a2e1e34f073a6732e2af7f22dbd90edf7d96dfa8c948"));
    assert(h.tagP == hex("070ca3bb56d1d5da42008dbeda1f30bce18138256f1937a434af9ce57da85d2c"));
    SessionKeys k = h.keys();
    assert(k.encryption == hex("a9172d591c753806e60a4c25c159d4513e75695c919562ecdb3186f46f70a19c"));
    assert(k.authentication == hex("b3e93ce8193295b2c4cbe362e80d66e22cd3cf7861eff256c3cb8653aa59dc27"));
    assert(k.longTerm == hex("3d74719c074ee80437347c9d848fa0b545a50394dfd903d43a16357519d53e8b"));

    Bytes plain = bytes("PhoneCam test record, longer than one AES block.");
    Bytes record = sealRecord(k, 5, plain);
    assert(record == hex("000000302fc5f85429eaf481ef85d104d2faf55fb1f3de553bc17cff71b5f79a66c1bda81866006979d9d35f04da4c57cc9e8be2c4d9b16d459d31e3e634e6a5718e4bd8"));
    Bytes cipher(record.begin() + 4, record.end() - 16), tag(record.end() - 16, record.end());
    assert(openRecord(k, 5, cipher, tag) == plain);
    bool rejected = false;
    try { openRecord(k, 6, cipher, tag); } catch (const std::runtime_error &) { rejected = true; }   // replay/reorder
    assert(rejected);
    rejected = false; cipher[3] ^= 1;
    try { openRecord(k, 5, cipher, tag); } catch (const std::runtime_error &) { rejected = true; }   // tampering
    assert(rejected);

    Ephemeral a, b;
    assert(a.publicKey().size() == 65 && a.shared(b.publicKey()) == b.shared(a.publicKey()));
    Bytes notOnCurve = a.publicKey(); notOnCurve[40] ^= 0x55;
    rejected = false;
    try { b.shared(notOnCurve); } catch (const std::runtime_error &) { rejected = true; }
    assert(rejected);
    puts("PASS: Wi-Fi pairing codes, handshake tags, keys, records (shared vectors) and P-256 ECDH");
}
