#include "adb.hpp"
#include <cassert>
#include <cstdio>
// Header-only parsing checks under sanitizers; runAdb/autoReceive are covered by tools/test_native.py.
namespace phonecam { AdbResult runAdb(const std::string &, const std::vector<std::string> &, int) { return {}; } }
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
    assert(services.size() == 1 && services[0] == "192.168.1.20:41231");
    puts("PASS: adb device, pairing-key and forward-port parsing; Wi-Fi addresses and USB-first order");
}
