#pragma once
// Automatic phone discovery, pairing and forwarding through Android's ADB tool, over USB or
// Android's Wireless debugging (same ADB link and security, no cable).
// Parsing is header-only and pure so it can be tested under sanitizers without ADB.
#include "adb_parse.hpp"
#include "receiver.hpp"

namespace phonecam {
struct AdbResult { int status = -1; std::string output; };
// Runs ADB without a shell; only stdin/stdout/stderr are inherited. Kills it after timeoutMs.
AdbResult runAdb(const std::string &adb, const std::vector<std::string> &args, int timeoutMs = 5000);
// Configured path, then the Android SDK, Homebrew and PATH locations. Empty if none is executable.
std::string findAdb(const std::string &configured);

using Status = std::function<void(const std::string &)>;
// One-time Wireless debugging pairing (`adb pair`); returns a sentence for the status line.
std::string pairWifi(const std::string &adb, const std::string &address, const std::string &code);
// Finds the phone, pairs, forwards and receives until stop; reconnects after unplugging,
// USB resets and new sessions. Removes its forward on exit. Never starts the phone camera:
// the user's Start on the phone does that.
void autoReceive(std::atomic<bool> &stop, const std::function<std::string()> &adbSetting,
                 const Status &status, const Output &output);
}
