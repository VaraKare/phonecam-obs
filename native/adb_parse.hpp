#pragma once
// Pure parsing of adb output and Wi-Fi addresses. Portable (macOS and Windows) and tested under sanitizers.
#include <algorithm>
#include <cstddef>
#include <cstdlib>
#include <string>
#include <vector>

namespace phonecam {
constexpr const char *pairingUri = "content://dev.phonecam.obs.pairing/token";
constexpr const char *socketName = "localabstract:phonecam-live-v1";

// Wireless debugging serials: "adb-<id>._adb-tls-connect._tcp" (found by mDNS) or "<ip>:<port>".
inline bool isWireless(const std::string &serial) {
    if (serial.find("._adb-tls-connect._tcp") != std::string::npos || serial.find("._adb._tcp") != std::string::npos) return true;
    auto colon = serial.rfind(':');
    return colon != std::string::npos && colon + 1 < serial.size() && serial.find_first_not_of("0123456789", colon + 1) == std::string::npos;
}
inline bool validPort(const std::string &digits) {
    if (digits.empty() || digits.size() > 5 || digits.find_first_not_of("0123456789") != std::string::npos) return false;
    int port = std::atoi(digits.c_str());
    return port >= 1 && port <= 65535;
}
// "a.b.c.d:port" with each part 0..255: the address Wireless debugging shows for pairing or connecting.
inline bool validWifiAddress(const std::string &value) {
    auto colon = value.find(':');
    if (colon == std::string::npos || !validPort(value.substr(colon + 1))) return false;
    std::string ip = value.substr(0, colon); int parts = 0; size_t start = 0;
    while (start <= ip.size()) {
        size_t end = ip.find('.', start);
        if (end == std::string::npos) end = ip.size();
        std::string part = ip.substr(start, end - start);
        if (part.empty() || part.size() > 3 || part.find_first_not_of("0123456789") != std::string::npos || std::atoi(part.c_str()) > 255) return false;
        ++parts; start = end + 1;
    }
    return parts == 4;
}
inline bool validPairingCode(const std::string &code) { return code.size() == 6 && code.find_first_not_of("0123456789") == std::string::npos; }
// `adb mdns services`: phones with Wireless debugging on, as "<name> _adb-tls-connect._tcp <ip>:<port>".
struct WirelessService { std::string name, address; };
inline std::vector<WirelessService> parseWirelessServices(const std::string &text);

struct DeviceList { std::vector<std::string> ready; int unauthorized = 0, offline = 0; };

inline std::vector<std::string> lines(const std::string &text) {
    std::vector<std::string> out; size_t start = 0;
    while (start <= text.size()) {
        size_t end = text.find('\n', start);
        if (end == std::string::npos) end = text.size();
        std::string line = text.substr(start, end - start);
        if (!line.empty() && line.back() == '\r') line.pop_back();
        out.push_back(line);
        start = end + 1;
    }
    return out;
}
inline std::vector<std::string> words(const std::string &line) {
    std::vector<std::string> out; std::string word;
    for (char c : line) {
        if (c == ' ' || c == '\t') { if (!word.empty()) out.push_back(word); word.clear(); }
        else word += c;
    }
    if (!word.empty()) out.push_back(word);
    return out;
}
// `adb devices` output; ignores the banner, daemon start-up messages and blank lines.
inline DeviceList parseDevices(const std::string &text) {
    DeviceList list;
    for (const auto &line : lines(text)) {
        auto w = words(line);
        if (w.size() < 2 || w[0] == "*" || w[0] == "List") continue;
        if (w[0].size() > 256 || w[0].find_first_of("/\\") != std::string::npos) continue;
        if (w[1] == "device") list.ready.push_back(w[0]);
        else if (w[1] == "unauthorized") ++list.unauthorized;
        else if (w[1] == "offline") ++list.offline;
    }
    // The cable is faster and steadier, so a phone on USB is used before one on Wi-Fi.
    std::stable_partition(list.ready.begin(), list.ready.end(), [](const std::string &s) { return !isWireless(s); });
    return list;
}
// A Wireless-debugging instance name ("adb-<serial>-<random>") or a USB serial worth remembering: letters, digits,
// '.', '_' and '-' only, at most 100 characters. Anything else from the network is ignored.
inline bool validDeviceName(const std::string &name) {
    if (name.empty() || name.size() > 100) return false;
    for (char c : name)
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-')) return false;
    return true;
}
inline std::vector<WirelessService> parseWirelessServices(const std::string &text) {
    std::vector<WirelessService> out;
    for (const auto &line : lines(text)) {
        auto w = words(line);
        if (w.size() < 3 || w[1].rfind("_adb-tls-connect._tcp", 0) != 0 || !validWifiAddress(w[2]) || !validDeviceName(w[0])) continue;
        bool listed = std::any_of(out.begin(), out.end(), [&](const WirelessService &s) { return s.address == w[2]; });
        if (!listed && out.size() < 8) out.push_back({w[0], w[2]});
    }
    return out;
}

// AUDIT-008 A: phones that answered the pairing-key query before, so the Wireless-debugging search connects only
// to them. An entry is a service instance name ("adb-TEST42-AbCdEf"), or, for a phone seen over USB, "adb-<serial>-"
// (Android names the service "adb-<serial>-<random>"), which matches any instance name starting with it.
inline bool knownDevice(const std::vector<std::string> &known, const std::string &name) {
    for (const auto &entry : known) {
        if (entry == name) return true;
        if (entry.size() > 5 && entry.back() == '-' && name.size() > entry.size() && name.compare(0, entry.size(), entry) == 0) return true;
    }
    return false;
}
// The entry to remember for a device that answered the key query: serial "adb-X._adb-tls-connect._tcp" gives "adb-X";
// "ip:port" gives the name the last mDNS search found at that address (none if adb was told the address by hand);
// a USB serial gives "adb-<serial>-". Empty when there is nothing safe to remember.
inline std::string deviceEntry(const std::string &serial, const std::vector<WirelessService> &lastSearch) {
    for (const char *suffix : {"._adb-tls-connect._tcp", "._adb._tcp"}) {
        auto at = serial.find(suffix);
        if (at != std::string::npos) return validDeviceName(serial.substr(0, at)) ? serial.substr(0, at) : std::string();
    }
    if (isWireless(serial)) {
        for (const auto &service : lastSearch) if (service.address == serial) return service.name;
        return {};
    }
    return validDeviceName(serial) ? "adb-" + serial + "-" : std::string();
}
// The known-phones file: one entry per line, newest last, at most 16; anything not well formed is skipped.
constexpr size_t maxKnownDevices = 16;
inline std::vector<std::string> parseKnownDevices(const std::string &text) {
    std::vector<std::string> out;
    for (const auto &line : lines(text))
        if (validDeviceName(line) && std::find(out.begin(), out.end(), line) == out.end()) out.push_back(line);
    if (out.size() > maxKnownDevices) out.erase(out.begin(), out.end() - std::ptrdiff_t(maxKnownDevices));
    return out;
}
// Adds a new entry at the end; the oldest go once there are more than 16. False if it was already there (or invalid).
inline bool rememberDevice(std::vector<std::string> &known, const std::string &entry) {
    if (!validDeviceName(entry) || std::find(known.begin(), known.end(), entry) != known.end()) return false;
    known.push_back(entry);
    if (known.size() > maxKnownDevices) known.erase(known.begin(), known.end() - std::ptrdiff_t(maxKnownDevices));
    return true;
}
// `content query` output: "Row: 0 token=<64 lowercase hex>". Anything else is not paired.
inline std::string parseToken(const std::string &text) {
    auto at = text.find("token=");
    if (at == std::string::npos) return {};
    std::string token = text.substr(at + 6, 64);
    if (token.size() != 64 || token.find_first_not_of("0123456789abcdef") != std::string::npos) return {};
    size_t after = at + 6 + 64;
    if (after < text.size() && text[after] != '\n' && text[after] != '\r' && text[after] != ' ' && text[after] != ',') return {};
    return token;
}
// `adb forward tcp:0 ...` prints the allocated local port.
inline int parsePort(const std::string &text) {
    for (const auto &line : lines(text)) {
        auto w = words(line);
        if (w.size() != 1 || w[0].empty() || w[0].size() > 5 || w[0].find_first_not_of("0123456789") != std::string::npos) continue;
        int port = std::atoi(w[0].c_str());
        if (port >= 1024 && port <= 65535) return port;
    }
    return 0;
}

}
