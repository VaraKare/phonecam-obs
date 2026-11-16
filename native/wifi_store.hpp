#pragma once
// The pairings file, wifi.json: {"macId":"<16 hex>","phones":[{"id":"<16 hex>","name":"...","key":"<64 hex>"}]}.
// macOS reads and writes it with Foundation; Windows uses readStore/writeStore here, a small JSON reader
// and writer for exactly this shape (unknown fields are ignored). Tested in wifi_test.cpp.
#include "wifi_crypto.hpp"
#include <string>
#include <utility>
#include <vector>

namespace phonecam::wifi {
struct PairedPhone { std::string id, name; Bytes key; };
struct Pairings { std::string macId; std::vector<PairedPhone> phones; };

// Small helpers shared by both platforms' storage.
inline std::string hexString(const uint8_t *p, size_t n) {
    static const char *digits = "0123456789abcdef";
    std::string out; for (size_t i = 0; i < n; ++i) { out += digits[p[i] >> 4]; out += digits[p[i] & 15]; }
    return out;
}
inline bool fromHex(const std::string &text, Bytes &out) {
    if (text.size() % 2) return false;
    out.clear();
    auto value = [](char c) { return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1; };
    for (size_t i = 0; i < text.size(); i += 2) {
        int hi = value(text[i]), lo = value(text[i + 1]);
        if (hi < 0 || lo < 0) return false;
        out.push_back(uint8_t(hi << 4 | lo));
    }
    return true;
}
// Printable, valid UTF-8, at most 64 bytes, never cutting a character; "Phone" when empty. The name arrives
// from the network outside the handshake's MAC, so invalid bytes are dropped rather than trusted: on macOS
// a non-UTF-8 name made saving the pairings abort the process (audit/FINDINGS.md AUDIT-003).
inline std::string cleanName(const std::string &raw) {
    std::string out;
    const auto *p = reinterpret_cast<const unsigned char *>(raw.data());
    const size_t n = raw.size();
    for (size_t i = 0; i < n;) {
        const unsigned char c = p[i];
        size_t length = c < 0x80 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 0;
        if (!length || i + length > n) { ++i; continue; }
        uint32_t code = length == 1 ? c : length == 2 ? (c & 0x1F) : length == 3 ? (c & 0x0F) : (c & 0x07);
        bool valid = true;
        for (size_t k = 1; k < length; ++k) {
            if ((p[i + k] & 0xC0) != 0x80) { valid = false; break; }
            code = code << 6 | (p[i + k] & 0x3F);
        }
        static const uint32_t smallest[] = {0, 0, 0x80, 0x800, 0x10000};
        valid = valid && code >= smallest[length] && code <= 0x10FFFF && !(code >= 0xD800 && code <= 0xDFFF);
        if (!valid) { ++i; continue; }
        if (length == 1 && (c < 32 || c == 127)) { ++i; continue; }
        if (out.size() + length > 64) break;
        out.append(raw, i, length);
        i += length;
    }
    return out.empty() ? "Phone" : out;
}


namespace json {
struct Value {
    enum Type { Null, Boolean, Number, String, Array, Object } type = Null;
    std::string text;
    std::vector<Value> items;
    std::vector<std::pair<std::string, Value>> fields;
    const Value *get(const std::string &key) const {
        for (const auto &field : fields) if (field.first == key) return &field.second;
        return nullptr;
    }
};
class Parser {
    const std::string &s; size_t i = 0;
    void space() { while (i < s.size() && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) ++i; }
    bool literal(const char *word) {
        size_t n = std::strlen(word);
        if (s.compare(i, n, word)) return false;
        i += n; return true;
    }
    static void utf8(std::string &out, uint32_t c) {
        if (c < 0x80) out += char(c);
        else if (c < 0x800) { out += char(0xC0 | c >> 6); out += char(0x80 | (c & 63)); }
        else if (c < 0x10000) { out += char(0xE0 | c >> 12); out += char(0x80 | (c >> 6 & 63)); out += char(0x80 | (c & 63)); }
        else { out += char(0xF0 | c >> 18); out += char(0x80 | (c >> 12 & 63)); out += char(0x80 | (c >> 6 & 63)); out += char(0x80 | (c & 63)); }
    }
    bool hex4(uint32_t &value) {
        if (i + 4 > s.size()) return false;
        value = 0;
        for (int k = 0; k < 4; ++k) {
            char c = s[i++]; value <<= 4;
            if (c >= '0' && c <= '9') value |= uint32_t(c - '0');
            else if (c >= 'a' && c <= 'f') value |= uint32_t(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') value |= uint32_t(c - 'A' + 10);
            else return false;
        }
        return true;
    }
    bool string(std::string &out) {
        if (i >= s.size() || s[i] != '"') return false;
        ++i;
        while (i < s.size()) {
            char c = s[i++];
            if (c == '"') return true;
            if (uint8_t(c) < 0x20) return false;
            if (c != '\\') { out += c; continue; }
            if (i >= s.size()) return false;
            switch (char e = s[i++]) {
                case '"': case '\\': case '/': out += e; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'n': out += '\n'; break;
                case 'r': out += '\r'; break;
                case 't': out += '\t'; break;
                case 'u': {
                    uint32_t c1;
                    if (!hex4(c1)) return false;
                    if (c1 >= 0xD800 && c1 < 0xDC00) {   // surrogate pair
                        uint32_t c2;
                        if (i + 2 > s.size() || s[i] != '\\' || s[i + 1] != 'u') return false;
                        i += 2;
                        if (!hex4(c2) || c2 < 0xDC00 || c2 >= 0xE000) return false;
                        c1 = 0x10000 + ((c1 - 0xD800) << 10) + (c2 - 0xDC00);
                    } else if (c1 >= 0xDC00 && c1 < 0xE000) return false;
                    utf8(out, c1);
                    break;
                }
                default: return false;
            }
        }
        return false;
    }
public:
    explicit Parser(const std::string &text) : s(text) {}
    bool value(Value &out, int depth = 0) {
        if (depth > 16) return false;
        space();
        if (i >= s.size()) return false;
        char c = s[i];
        if (c == '"') { out.type = Value::String; return string(out.text); }
        if (c == '{') {
            out.type = Value::Object; ++i; space();
            if (i < s.size() && s[i] == '}') { ++i; return true; }
            while (true) {
                std::string key; Value item;
                space();
                if (!string(key)) return false;
                space();
                if (i >= s.size() || s[i++] != ':') return false;
                if (!value(item, depth + 1)) return false;
                out.fields.emplace_back(std::move(key), std::move(item));
                space();
                if (i < s.size() && s[i] == ',') { ++i; continue; }
                if (i < s.size() && s[i] == '}') { ++i; return true; }
                return false;
            }
        }
        if (c == '[') {
            out.type = Value::Array; ++i; space();
            if (i < s.size() && s[i] == ']') { ++i; return true; }
            while (true) {
                Value item;
                if (!value(item, depth + 1)) return false;
                out.items.push_back(std::move(item));
                space();
                if (i < s.size() && s[i] == ',') { ++i; continue; }
                if (i < s.size() && s[i] == ']') { ++i; return true; }
                return false;
            }
        }
        if (literal("true") || literal("false")) { out.type = Value::Boolean; return true; }
        if (literal("null")) { out.type = Value::Null; return true; }
        size_t start = i;
        while (i < s.size() && (std::strchr("+-.eE", s[i]) || (s[i] >= '0' && s[i] <= '9'))) ++i;
        out.type = Value::Number;
        return i > start;
    }
    bool finished() { space(); return i == s.size(); }
};
inline std::string quote(const std::string &text) {
    static const char *digits = "0123456789abcdef";
    std::string out = "\"";
    for (unsigned char c : text) {
        if (c == '"' || c == '\\') { out += '\\'; out += char(c); }
        else if (c < 0x20 || c == 0x7F) { out += "\\u00"; out += digits[c >> 4]; out += digits[c & 15]; }
        else out += char(c);
    }
    return out + "\"";
}
}

// Fills value from the file text; entries that are not well formed are skipped, as on the Mac.
// Returns false if the text is not JSON at all. A missing or invalid macId is left empty.
inline bool readStore(const std::string &text, Pairings &value) {
    value = {};
    json::Value root;
    json::Parser parser(text);
    if (!parser.value(root) || !parser.finished() || root.type != json::Value::Object) return false;
    if (const auto *mac = root.get("macId"); mac && mac->type == json::Value::String && mac->text.size() == 16) value.macId = mac->text;
    if (const auto *phones = root.get("phones"); phones && phones->type == json::Value::Array) {
        for (const auto &item : phones->items) {
            if (item.type != json::Value::Object) continue;
            const auto *id = item.get("id"), *name = item.get("name"), *key = item.get("key");
            if (!id || !name || !key || id->type != json::Value::String || name->type != json::Value::String || key->type != json::Value::String) continue;
            PairedPhone phone{id->text, cleanName(name->text), {}};
            if (phone.id.size() == 16 && fromHex(key->text, phone.key) && phone.key.size() == keyBytes) value.phones.push_back(phone);
        }
    }
    return true;
}
inline std::string writeStore(const Pairings &value) {
    std::string out = "{\"macId\":" + json::quote(value.macId) + ",\"phones\":[";
    for (size_t i = 0; i < value.phones.size(); ++i) {
        const auto &p = value.phones[i];
        out += std::string(i ? "," : "") + "{\"id\":" + json::quote(p.id) + ",\"name\":" + json::quote(p.name) +
               ",\"key\":" + json::quote(hexString(p.key.data(), p.key.size())) + "}";
    }
    return out + "]}";
}
}
