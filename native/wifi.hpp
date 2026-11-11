#pragma once
// Direct Wi-Fi: the Mac listens, announces itself with Bonjour, pairs phones by QR or typed code and
// receives encrypted PCAM v3 video from paired phones. Protocol: docs/WIFI.md.
#include "receiver.hpp"
#include "wifi_crypto.hpp"
#include <atomic>
#include <chrono>
#include <functional>
#include <mutex>
#include <string>
#include <vector>

namespace phonecam::wifi {
struct PairedPhone { std::string id, name; Bytes key; };
struct Pairings { std::string macId; std::vector<PairedPhone> phones; };
// ~/Library/Application Support/phonecam-obs/wifi.json, mode 600; a new Mac id is created on first use.
Pairings loadPairings();
void savePairings(const Pairings &value);

struct PairingWindow { std::string code, link; };
std::string macName();
std::vector<std::string> localAddresses();  // up, non-loopback, non-link-local IPv4, at most 4
// The pairing card shown in the OBS source: QR code, typed code and steps, as a BGRA image.
std::vector<uint8_t> renderPairingCard(const PairingWindow &window, uint32_t width, uint32_t height);

class Server {
public:
    using Status = std::function<void(const std::string &)>;
    Server(Status status, Output output) : status(std::move(status)), output(std::move(output)) {}
    // Listens until stop: one connection at a time (pairing or live video).
    void run(std::atomic<bool> &stop);
    // Opens a 3-minute pairing window and returns the code and QR link to show.
    PairingWindow openPairing();
    void closePairing();
    bool pairingOpen();
    // Short line for the source properties: port, addresses and paired phones.
    std::string summary();
    static void forgetAll();
    int listeningPort() const { return port; }
private:
    Status status;
    Output output;
    std::mutex lock;
    Bytes secret;                                  // current pairing window; empty when closed
    std::chrono::steady_clock::time_point expires;
    int failures = 0;
    std::atomic<int> port{0};
    void *bonjour = nullptr;                       // DNSServiceRef
    void announce();
    void handle(int fd, std::atomic<bool> &stop);
};
}
