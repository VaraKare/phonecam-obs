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
    puts("PASS: adb device, pairing-key and forward-port parsing");
}
