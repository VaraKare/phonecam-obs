#pragma once
// Automatic phone discovery, pairing and USB forwarding through Android's ADB tool.
// Parsing is header-only and pure so it can be tested under sanitizers without ADB.
#include <cstdlib>
#include <string>
#include <vector>
#include "receiver.hpp"

namespace phonecam {
constexpr const char *pairingUri = "content://dev.phonecam.obs.pairing/token";
constexpr const char *socketName = "localabstract:phonecam-live-v1";

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
    return list;
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

struct AdbResult { int status = -1; std::string output; };
// Runs ADB without a shell; only stdin/stdout/stderr are inherited. Kills it after timeoutMs.
AdbResult runAdb(const std::string &adb, const std::vector<std::string> &args, int timeoutMs = 5000);
// Configured path, then the Android SDK, Homebrew and PATH locations. Empty if none is executable.
std::string findAdb(const std::string &configured);

using Status = std::function<void(const std::string &)>;
// Finds the phone, pairs, forwards and receives until stop; reconnects after unplugging,
// USB resets and new sessions. Removes its forward on exit. Never starts the phone camera:
// the user's Start on the phone does that.
void autoReceive(std::atomic<bool> &stop, const std::function<std::string()> &adbSetting,
                 const Status &status, const Output &output);
}
