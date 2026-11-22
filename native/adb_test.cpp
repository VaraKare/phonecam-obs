#include "adb_parse.hpp"
#include "stream.hpp"
#include <cassert>
#include <cstdio>
// Header-only parsing checks under sanitizers; runAdb/autoReceive are covered by tools/test_native.py.
// Also the live summary line and the phone's battery and heat (stream.hpp), which are pure as well.
int main() {
    using namespace phonecam;
    auto d = parseDevices("* daemon not running; starting now at tcp:5037\n* daemon started successfully\nList of devices attached\nABC123\tdevice\n192.168.1.4:5555\tdevice\nXYZ\tunauthorized\nQQ\toffline\n\n");
    assert(d.ready.size() == 2 && d.ready[0] == "ABC123" && d.ready[1] == "192.168.1.4:5555" && d.unauthorized == 1 && d.offline == 1);
    assert(parseDevices("List of devices attached\r\n").ready.empty());
    assert(parseDevices("../evil\tdevice\n").ready.empty());
    std::string hex(64, 'a');
    assert(parseToken("Row: 0 token=" + hex + "\n") == hex);
    assert(parseToken("Row: 0 token=" + hex) == hex);
    assert(parseToken("No result found.\n").empty());
    assert(parseToken("Row: 0 token=" + std::string(63, 'a') + "\n").empty());
    assert(parseToken("Row: 0 token=" + hex + "b\n").empty());
    assert(parseToken("Row: 0 token=" + std::string(64, 'A') + "\n").empty());
    assert(parsePort("54321\n") == 54321 && parsePort("") == 0 && parsePort("80\n") == 0 && parsePort("error: no devices\n") == 0 && parsePort("99999\n") == 0);
    auto mixed = parseDevices("List of devices attached\nadb-10BF-xyz._adb-tls-connect._tcp\tdevice\nPHONE0SERIAL\tdevice\n");
    assert(mixed.ready.size() == 2 && mixed.ready[0] == "PHONE0SERIAL");  // cable first
    assert(isWireless("adb-10BF-xyz._adb-tls-connect._tcp") && isWireless("192.168.1.4:5555") && !isWireless("PHONE0SERIAL"));
    assert(validWifiAddress("192.168.1.20:37099") && !validWifiAddress("192.168.1.20") && !validWifiAddress("192.168.1.256:5555"));
    assert(!validWifiAddress("192.168.1:5555") && !validWifiAddress("host.local:5555") && !validWifiAddress("1.2.3.4:70000") && !validWifiAddress("1.2.3.4:-1"));
    assert(validPairingCode("123456") && !validPairingCode("12345") && !validPairingCode("12345a") && !validPairingCode("1234567"));
    auto services = parseWirelessServices("List of discovered mdns services\nadb-10BF-xyz\t_adb-tls-connect._tcp\t192.168.1.20:41231\n"
        "adb-10BF-xyz\t_adb-tls-pairing._tcp\t192.168.1.20:37099\nadb-10BF-xyz\t_adb-tls-connect._tcp.\t192.168.1.20:41231\nbad\t_adb-tls-connect._tcp\t;rm -rf\n");
    assert(services.size() == 1 && services[0].address == "192.168.1.20:41231" && services[0].name == "adb-10BF-xyz");
    assert(parseWirelessServices("bad;name\t_adb-tls-connect._tcp\t192.168.1.21:4000\n").empty());

    // AUDIT-008 A: connect only to phones that answered the key query before.
    std::vector<std::string> known = {"adb-R58M12-AbCdEf", "adb-USB0SERIAL-"};
    assert(knownDevice(known, "adb-R58M12-AbCdEf") && !knownDevice(known, "adb-R58M12-Other") && !knownDevice(known, "adb-R58M1"));
    assert(knownDevice(known, "adb-USB0SERIAL-x1y2z3") && !knownDevice(known, "adb-USB0SERIALX-q"));
    assert(!knownDevice({}, "adb-anything") && !knownDevice({"adb-"}, "adb-anything"));  // "adb-" alone is no prefix
    std::vector<WirelessService> search = {{"adb-R58M12-AbCdEf", "192.168.1.20:41231"}};
    assert(deviceEntry("adb-R58M12-AbCdEf._adb-tls-connect._tcp", {}) == "adb-R58M12-AbCdEf");
    assert(deviceEntry("192.168.1.20:41231", search) == "adb-R58M12-AbCdEf" && deviceEntry("192.168.1.30:5555", search).empty());
    assert(deviceEntry("PHONE0SERIAL", {}) == "adb-PHONE0SERIAL-" && deviceEntry("bad serial", {}).empty());
    auto file = parseKnownDevices("adb-A-1\r\n\nnot ok\nadb-A-1\nadb-B-\n");
    assert(file.size() == 2 && file[0] == "adb-A-1" && file[1] == "adb-B-");
    assert(!rememberDevice(file, "adb-A-1") && !rememberDevice(file, "has space") && rememberDevice(file, "adb-C-3") && file.size() == 3);
    std::vector<std::string> many;
    for (int i = 0; i < 20; ++i) assert(rememberDevice(many, "adb-" + std::to_string(i) + "-x"));
    assert(many.size() == maxKnownDevices && many.front() == "adb-4-x" && many.back() == "adb-19-x");  // the oldest go
    std::string listing; for (int i = 0; i < 20; ++i) listing += "adb-" + std::to_string(i) + "-x\n";
    assert(parseKnownDevices(listing).size() == maxKnownDevices && parseKnownDevices(listing).front() == "adb-4-x");

    // The phone's battery and heat from a 0.6 status, and the once-a-minute live summary.
    auto health = PhoneHealth::fromStatus("status\ncamera=Rear\nbattery=76\ncharging=yes\nmic=off\n");
    assert(health.battery == 76 && health.charging && health.heat == "normal");
    assert(PhoneHealth::fromStatus("status\nbattery=12\ncharging=no\nwarning=The phone is hot. Lower the quality\n").heat == "hot");
    assert(PhoneHealth::fromStatus("status\nwarning=The phone is getting warm.\n").heat == "warm");
    assert(PhoneHealth::fromStatus("status\nwarning=Battery 9 %. Plug the phone in.\n").heat == "normal");
    assert(PhoneHealth::fromStatus("status\nheat=severe\nwarning=hot\n").heat == "severe");
    assert(PhoneHealth::fromStatus("status\nbattery=1000\nheat=Bad Value\n").battery == -1);
    Channel channel;
    assert(channel.health().battery == -1 && channel.health().heat.empty());
    std::string status = "status\nbattery=40\ncharging=no\n";
    channel.received(Header{Control, uint32_t(status.size()), 0, 0, 0}, std::vector<uint8_t>(status.begin(), status.end()));
    assert(channel.health().battery == 40 && !channel.health().charging && channel.health().heat == "normal");
    channel.detach();
    assert(channel.health().battery == -1);  // a session that ended reports nothing

    LiveSummary minute;
    assert(minute.empty());
    for (int i = 0; i < 1790; ++i) { minute.shown(); minute.timed(i % 600 ? 12.0 : 41.5); }
    minute.gap(3); minute.skipped(); minute.skipped();
    auto line = minute.line("Mac", 60.2, health);
    assert(line == "Live summary (Mac, last 60 s): 1790 frames shown, 1 sequence gaps (3 frames missing, 2 skipped until a keyframe), "
                   "3 of 1790 over 33 ms to decode, slowest 42 ms; phone battery 76 % (charging), heat normal.");
    assert(LiveSummary().line("PC", 5, PhoneHealth()) == "Live summary (PC, last 5 s): 0 frames shown, 0 sequence gaps (0 frames missing, "
                                                         "0 skipped until a keyframe), 0 of 0 over 33 ms to decode, slowest 0 ms.");
    PhoneHealth heatOnly; heatOnly.heat = "warm";
    assert(LiveSummary().line("PC", 5, heatOnly).find("slowest 0 ms; phone heat warm.") != std::string::npos);
    puts("PASS: adb device, pairing-key and forward-port parsing; Wi-Fi addresses and USB-first order; known Wireless-debugging "
         "phones (AUDIT-008 A); phone battery and heat; live summary line");
}
