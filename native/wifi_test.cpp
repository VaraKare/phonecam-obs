#include "wifi_crypto.hpp"
#include "wifi_store.hpp"
#include "protocol.hpp"
#include <cassert>
#include <cstdio>
// Expected values come from an independent Python + openssl computation (see docs/WIFI.md); the phone's
// Kotlin tests check the same numbers, so both ends provably agree.
using namespace phonecam::wifi;
static Bytes hex(const char *text) {
    auto digit = [](char c) { return c <= '9' ? c - '0' : c - 'a' + 10; };
    Bytes out; for (size_t i = 0; text[i] && text[i + 1]; i += 2) out.push_back(uint8_t(digit(text[i]) << 4 | digit(text[i + 1])));
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
    SessionKeys r = h.replyKeys();  // computer → phone (0.6)
    assert(r.encryption == hex("c8390e2b28d492f13c1e771bff4eea63ad63e21dbd9e4a359fe72e6affd020e5"));
    assert(r.authentication == hex("238b86ab56ddd84d1414b1adeeb0ff07e0d865ee39ced95dbeed91f349c593ad"));
    Bytes hello = bytes(phonecam::pluginHello);
    Bytes sealedHello = sealRecord(r, 0, hello);
    assert(openRecord(r, 0, Bytes(sealedHello.begin() + 4, sealedHello.end() - 16), Bytes(sealedHello.end() - 16, sealedHello.end())) == hello);

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
    // Pairings file (Windows reads and writes it with wifi_store.hpp; the Mac with Foundation).
    Pairings stored{"0123456789abcdef", {{"fedcba9876543210", "Vivo \"V2437\" \\ \x01 \xc3\xa9", Bytes(32, 7)}}};
    Pairings loaded;
    assert(readStore(writeStore(stored), loaded) && loaded.macId == stored.macId && loaded.phones.size() == 1);
    assert(loaded.phones[0].id == "fedcba9876543210" && loaded.phones[0].key == Bytes(32, 7));
    assert(loaded.phones[0].name == "Vivo \"V2437\" \\  \xc3\xa9");                // control character dropped by cleanName
    // Foundation-style text: escaped slashes, \u escapes (including a surrogate pair), spacing, extra fields,
    // and entries that are not well formed (skipped).
    const std::string mac = "{ \"phones\" : [ {\"id\":\"1111111111111111\",\"name\":\"A\\/B \\u00e9\\ud83d\\ude00\",\"key\":\""
                            + std::string(64, 'a') + "\",\"extra\":[1,true,null,{\"x\":-2.5e3}]},"
                            "{\"id\":\"short\",\"name\":\"x\",\"key\":\"00\"}, 7], \"macId\" : \"aaaaaaaaaaaaaaaa\" }";
    assert(readStore(mac, loaded) && loaded.macId == "aaaaaaaaaaaaaaaa" && loaded.phones.size() == 1);
    assert(loaded.phones[0].name == "A/B \xc3\xa9\xf0\x9f\x98\x80" && loaded.phones[0].key == Bytes(32, 0xaa));
    assert(!readStore("{\"macId\":", loaded) && !readStore("[]", loaded) && !readStore("{} x", loaded) && !readStore("{\"a\":\"\\ud800\"}", loaded));
    assert(readStore("{}", loaded) && loaded.macId.empty() && loaded.phones.empty());
    // Phone names arrive outside the handshake MAC: only valid UTF-8 survives (AUDIT-003), never cut mid-character.
    assert(cleanName(std::string("Pixel\xff\xfe")) == "Pixel");
    assert(cleanName(std::string("\xc0\xaf" "a\xed\xa0\x80" "b\xf4\x90\x80\x80" "c\xe2\x82")) == "abc");  // overlong, surrogate, > U+10FFFF, cut
    assert(cleanName("\xf0\x9f\x98\x80 \xe0\xa4\x85") == "\xf0\x9f\x98\x80 \xe0\xa4\x85");                   // emoji, Devanagari kept
    assert(cleanName(std::string(63, 'x') + "\xc3\xa9") == std::string(63, 'x'));                          // 65 bytes: é not cut in half
    assert(cleanName("\x01\x7f") == "Phone");
    puts("PASS: Wi-Fi pairing codes, handshake tags, keys, records (shared vectors), P-256 ECDH and the pairings file");
}
