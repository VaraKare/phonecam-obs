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

void autoReceive(std::atomic<bool> &stop, const std::function<std::string()> &adbSetting,
                 const Status &status, const Output &output) {
    std::string adb, serial, token, last;
    int port = 0;
    auto tokenFetched = std::chrono::steady_clock::time_point{};
    auto lastWifiSearch = std::chrono::steady_clock::time_point{};
    auto say = [&](const std::string &value) { if (value != last) { last = value; status(value); } };
    auto dropForward = [&] {
        if (port && !adb.empty() && !serial.empty()) runAdb(adb, {"-s", serial, "forward", "--remove", "tcp:" + std::to_string(port)}, 3000);
        port = 0; serial.clear(); token.clear();
    };
    while (!stop) {
        try {
            if (!port) {
                adb = findAdb(adbSetting());
                if (adb.empty()) {
                    say(adbSetting().empty() ? "Android platform-tools (adb) not found. Install it (see the README), or set the adb path in this source's properties."
                                             : "The adb path in this source's properties is not an executable file.");
                    pause(stop, 3000); continue;
                }
                auto listed = runAdb(adb, {"devices"});
                auto devices = parseDevices(listed.output);
                if (devices.ready.empty()) {
                    // A phone paired for Wireless debugging announces itself on the network; connect to it.
                    // `adb connect` only succeeds for a phone this Mac has already paired with.
                    if (std::chrono::steady_clock::now() - lastWifiSearch > std::chrono::seconds(6)) {
                        lastWifiSearch = std::chrono::steady_clock::now();
                        bool tried = false;
                        for (const auto &address : parseWirelessServices(runAdb(adb, {"mdns", "services"}).output)) {
                            runAdb(adb, {"connect", address}, 5000); tried = true;
                        }
                        if (tried) continue;
                    }
                    say(devices.unauthorized ? "Unlock the phone, tick \"Always allow from this computer\" and tap Allow on the USB debugging prompt."
                        : devices.offline ? "The phone is offline. Reconnect the USB cable, or turn Wireless debugging off and on."
                        : "Ready. Tap Start live on a phone paired over Wi-Fi, or connect one with a USB data cable (USB debugging on).");
                    pause(stop, 2000); continue;
                }
                for (const auto &candidate : devices.ready) {
                    auto found = parseToken(runAdb(adb, {"-s", candidate, "shell", "content", "query", "--uri", pairingUri}).output);
                    if (!found.empty()) { serial = candidate; token = found; break; }
                }
                if (serial.empty()) { say("Install the PhoneCam app on the phone (USB debugging connected, app not found)."); pause(stop, 3000); continue; }
                tokenFetched = std::chrono::steady_clock::now();
                port = parsePort(runAdb(adb, {"-s", serial, "forward", "tcp:0", socketName}).output);
                if (!port) { serial.clear(); token.clear(); say("Could not create the link to the phone. Reconnect the cable or Wi-Fi."); pause(stop, 2000); continue; }
            }
            say("Ready. Tap Start live on the phone.");
            bool announced = false;
            receive(stop, {port, token}, [&](Image image, uint64_t sequence, uint64_t pts) {
                if (!announced) {
                    announced = true;
                    say(std::string(isWireless(serial) ? "Live over Wi-Fi: " : "Live over USB: ") + std::to_string(imageWidth(image)) + "×" + std::to_string(imageHeight(image)) + ", " + decoderName() + ".");
                }
                output(image, sequence, pts);
            });
        } catch (const ConnectionRefused &) {
            port = 0; serial.clear(); token.clear();  // ADB already removed the forward.
            pause(stop, 300); continue;
        } catch (const NotStreaming &) {
            // Re-pair now and then so a reinstalled app's new key is picked up automatically.
            if (std::chrono::steady_clock::now() - tokenFetched > std::chrono::seconds(15)) dropForward();
        } catch (const std::exception &error) {
            if (stop) break;  // Source hidden or removed: nothing to report.
            say(std::string("Live ended: ") + error.what() + ". Tap Start live on the phone to go live again.");
        }
        pause(stop, 1000);
    }
    dropForward();
}
}
