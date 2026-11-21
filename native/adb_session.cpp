// Automatic phone connection through adb, shared by macOS and Windows: find adb and the phone, read the
// pairing key through the authorized shell, forward the camera socket, receive, and reconnect.
// Platform files provide runAdb, findAdb and receive.
#include "adb.hpp"
#include <chrono>
#include <thread>

namespace phonecam {
namespace {
void pause(std::atomic<bool> &stop, int milliseconds) {
    for (int waited = 0; waited < milliseconds && !stop; waited += 50) std::this_thread::sleep_for(std::chrono::milliseconds(50));
}
}

std::string pairWifi(const std::string &adb, const std::string &address, const std::string &code) {
    if (adb.empty()) return "Wi-Fi pairing needs Android platform-tools (adb). Install it first (see the README).";
    if (!validWifiAddress(address)) return "Wi-Fi pairing: type the IP address and port shown under \"Pair device with pairing code\", for example 192.168.1.20:37099.";
    if (!validPairingCode(code)) return "Wi-Fi pairing: type the 6-digit code shown on the phone.";
    auto result = runAdb(adb, {"pair", address, code}, 20000);
    if (result.status == 0 && result.output.find("Successfully paired") != std::string::npos)
        return "Paired over Wi-Fi. Keep Wireless debugging on; the phone connects automatically on the same Wi-Fi.";
    return "Wi-Fi pairing failed. Open \"Pair device with pairing code\" again and use the new address and code (they change each time).";
}

static int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

void Activity::set(Step value) { step = value; sinceMs = nowMs(); }
const char *Activity::name(int value) {
    switch (value) {
        case FindingAdb: return "finding adb";
        case StartingAdb: return "starting adb";
        case ListingPhones: return "listing phones (adb devices)";
        case SearchingWifi: return "looking for phones on Wi-Fi";
        case ReadingKey: return "reading the pairing key from the phone";
        case Forwarding: return "creating the USB link (adb forward)";
        case Linked: return "waiting for or receiving video";
        case RemovingLink: return "removing the USB link";
        case RestartingAdb: return "restarting adb";
        case Idle: return "waiting";
        default: return "starting";
    }
}

void autoReceive(std::atomic<bool> &stop, const std::function<std::string()> &adbSetting,
                 const Status &status, const Output &output, Activity *activity, Channel *channel) {
    std::string adb, serial, token, last;
    int port = 0, stalls = 0;
    auto tokenFetched = std::chrono::steady_clock::time_point{};
    auto lastWifiSearch = std::chrono::steady_clock::time_point{};
    auto say = [&](const std::string &value) { if (value != last) { last = value; status(value); } };
    auto doing = [&](Activity::Step step) { if (activity) activity->set(step); };
    auto wait = [&](int milliseconds) { doing(Activity::Idle); pause(stop, milliseconds); };
    auto dropForward = [&] {
        doing(Activity::RemovingLink);
        try { if (port && !adb.empty() && !serial.empty()) runAdb(adb, {"-s", serial, "forward", "--remove", "tcp:" + std::to_string(port)}, 3000); }
        catch (...) {}  // called from catch handlers and after the loop: must not throw (AUDIT-004)
        port = 0; serial.clear(); token.clear();
    };
    // An adb call without an answer. Two in a row: the server is stuck (seen on Windows after unplugging),
    // so it is ended and a fresh one started.
    auto stalled = [&](const AdbResult &result) {
        if (!result.timedOut) { stalls = 0; return false; }
        if (++stalls >= 2) {
            stalls = 0;
            say("adb stopped answering. Restarting it…");
            doing(Activity::RestartingAdb);
            restartAdb(adb);
        }
        wait(500);
        return true;
    };
    while (!stop) {
        try {
            if (!port) {
                doing(Activity::FindingAdb);
                adb = findAdb(adbSetting());
                if (adb.empty()) {
                    say(adbSetting().empty() ? "Android platform-tools (adb) not found. Install it (see the README), or set the adb path in this source's properties."
                                             : "The adb path in this source's properties is not an executable file.");
                    wait(3000); continue;
                }
                doing(Activity::StartingAdb);
                prepareAdb(adb, &stop);
                doing(Activity::ListingPhones);
                auto listed = runAdb(adb, {"devices"});
                if (stop) break;  // each adb call can take seconds: never start another after a hide (AUDIT-002 A)
                if (stalled(listed)) continue;
                auto devices = parseDevices(listed.output);
                if (devices.ready.empty()) {
                    // A phone paired for Wireless debugging announces itself on the network; connect to it.
                    // `adb connect` only succeeds for a phone this Mac has already paired with.
                    // Not while a USB phone waits for Allow or is offline: the search costs seconds per address
                    // and delayed the cable on the 17 Nov laptop (audit/FINDINGS.md AUDIT-008).
                    if (!devices.unauthorized && !devices.offline &&
                        std::chrono::steady_clock::now() - lastWifiSearch > std::chrono::seconds(6)) {
                        lastWifiSearch = std::chrono::steady_clock::now();
                        doing(Activity::SearchingWifi);
                        bool tried = false;
                        for (const auto &address : parseWirelessServices(runAdb(adb, {"mdns", "services"}).output)) {
                            if (stop) break;
                            runAdb(adb, {"connect", address}, 5000); tried = true;
                        }
                        if (tried) continue;
                    }
                    say(devices.unauthorized ? "Unlock the phone, tick \"Always allow from this computer\" and tap Allow on the USB debugging prompt."
                        : devices.offline ? "The phone is offline. Reconnect the USB cable, or turn Wireless debugging off and on."
                        : "Ready. Tap the record button on a phone paired over Wi-Fi, or connect one with a USB data cable (USB debugging on).");
                    wait(2000); continue;
                }
                doing(Activity::ReadingKey);
                bool unanswered = false;
                for (const auto &candidate : devices.ready) {
                    if (stop) break;
                    auto answer = runAdb(adb, {"-s", candidate, "shell", "content", "query", "--uri", pairingUri});
                    if (answer.timedOut) { unanswered = true; continue; }
                    auto found = parseToken(answer.output);
                    if (!found.empty()) { serial = candidate; token = found; break; }
                }
                if (stop) break;
                if (serial.empty()) {
                    if (unanswered) {
                        // One device that doesn't answer (a locked second phone, a stale Wireless-debugging entry, a
                        // hung emulator) is not adb's fault. Restarting adb would end Android Studio's or scrcpy's
                        // sessions too (AUDIT-012), so only a server-level call that also times out counts as a stall.
                        if (stalled(runAdb(adb, {"devices"}))) continue;
                        say("The phone isn't answering over USB. Unlock it, or unplug the cable and plug it back in.");
                        wait(2000); continue;
                    }
                    // A USB link that is resetting also fails the query (20 Nov); only blame the app when it is really missing.
                    bool installed = !devices.ready.empty() && !stop &&
                        runAdb(adb, {"-s", devices.ready.front(), "shell", "pm", "path", "dev.phonecam.obs"}).output.find("package:") != std::string::npos;
                    say(installed ? "Reconnecting to the phone over USB…"
                                  : "Install the PhoneCam app on the phone (USB debugging connected, app not found).");
                    wait(3000); continue;
                }
                tokenFetched = std::chrono::steady_clock::now();
                doing(Activity::Forwarding);
                auto forwarded = runAdb(adb, {"-s", serial, "forward", "tcp:0", socketName});
                if (stalled(forwarded)) { serial.clear(); token.clear(); continue; }
                port = parsePort(forwarded.output);
                if (!port) { serial.clear(); token.clear(); say("Could not create the link to the phone. Reconnect the cable or Wi-Fi."); wait(2000); continue; }
                stalls = 0;
            }
            say("Ready. Tap the record button on the phone.");
            bool announced = false;
            doing(Activity::Linked);
            receive(stop, {port, token}, [&](Image image, uint64_t sequence, uint64_t pts) {
                if (!announced) {
                    announced = true;
                    say(std::string(isWireless(serial) ? "Live over Wi-Fi: " : "Live over USB: ") + std::to_string(imageWidth(image)) + "×" + std::to_string(imageHeight(image)) + ", " + decoderName() + ".");
                }
                output(image, sequence, pts);
            }, channel);
        } catch (const ConnectionRefused &) {
            port = 0; serial.clear(); token.clear();  // ADB already removed the forward.
            wait(300); continue;
        } catch (const NotStreaming &) {
            // Re-pair now and then so a reinstalled app's new key is picked up automatically.
            if (std::chrono::steady_clock::now() - tokenFetched > std::chrono::seconds(15)) dropForward();
        } catch (const std::exception &error) {
            if (stop) break;  // Source hidden or removed: nothing to report.
            say(std::string("Live ended: ") + error.what() + ". Tap the record button on the phone to go live again.");
            // The cable may have been unplugged: find the phone and create the link again, as at start,
            // rather than retrying a link adb may have dropped or kept half-alive.
            dropForward();
        }
        wait(1000);
    }
    dropForward();
}
}
