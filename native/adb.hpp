#pragma once
#include <atomic>
// Automatic phone discovery, pairing and forwarding through Android's ADB tool, over USB or
// Android's Wireless debugging (same ADB link and security, no cable).
// Parsing is header-only and pure so it can be tested under sanitizers without ADB.
#include "adb_parse.hpp"
#include "receiver.hpp"

namespace phonecam {
// timedOut: adb gave no answer in time (killed, or on Windows not even started), as opposed to an error answer.
struct AdbResult { int status = -1; std::string output; bool timedOut = false; };
// Runs ADB without a shell; only stdin/stdout/stderr are inherited. Kills it after timeoutMs.
AdbResult runAdb(const std::string &adb, const std::vector<std::string> &args, int timeoutMs = 5000);
// Configured path, then the Android SDK, Homebrew and PATH locations. Empty if none is executable.
std::string findAdb(const std::string &configured);
// Windows: makes sure an adb server is listening. If none is, starts one in adb's foreground mode
// (`adb -L tcp:PORT server nodaemon`), tied to this process so it ends with OBS. Some antivirus programs
// (seen with Avast) stop adb's own background start, which then hangs. Does nothing on macOS.
void prepareAdb(const std::string &adb, const std::atomic<bool> *stop = nullptr);
// Ends an adb server that stopped answering, so the next call starts a fresh one: `adb kill-server`, and on
// Windows our own server, or a stuck adb.exe still holding the server port, is ended directly. After USB
// unplugging, adb on Windows was seen to accept connections but answer nothing.
void restartAdb(const std::string &adb);
// Windows: keeps this plugin loaded until OBS exits, needed once a stuck thread is left running in it.
// Does nothing on macOS.
void keepLoaded();

// What the connection loop is doing, for a watchdog that reports a step that never finishes.
struct Activity {
    enum Step : int { Starting, FindingAdb, StartingAdb, ListingPhones, SearchingWifi, ReadingKey, Forwarding,
                      Linked, RemovingLink, RestartingAdb, Idle };
    std::atomic<int> step{Starting};
    std::atomic<int64_t> sinceMs{0};  // steady clock, milliseconds
    void set(Step value);
    // Steps that wait on purpose (video, pauses) are never "stuck".
    static bool waits(int value) { return value == Linked || value == Idle; }
    static const char *name(int value);
};

using Status = std::function<void(const std::string &)>;
// One-time Wireless debugging pairing (`adb pair`); returns a sentence for the status line.
std::string pairWifi(const std::string &adb, const std::string &address, const std::string &code);
// Finds the phone, pairs, forwards and receives until stop; reconnects after unplugging,
// USB resets and new sessions. Removes its forward on exit. Never starts the phone camera:
// the user's Start on the phone does that.
// After the phone link drops mid-session (unplugging), the link is removed and the phone found again, as at
// start. Two adb calls in a row without an answer restart the adb server.
void autoReceive(std::atomic<bool> &stop, const std::function<std::string()> &adbSetting,
                 const Status &status, const Output &output, Activity *activity = nullptr,
                 Channel *channel = nullptr);
}
