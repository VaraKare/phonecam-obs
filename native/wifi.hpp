#pragma once
// Direct Wi-Fi: the computer listens, announces itself with Bonjour/mDNS, pairs phones by QR or typed code
// and receives encrypted PCAM v3 video from paired phones. Protocol: docs/WIFI.md.
// wifi_server.cpp is shared; wifi.mm (macOS) and windows/wifi_win.cpp provide storage, the computer's
// name and addresses, the network announcement and the pairing card.
#include "receiver.hpp"
#include "wifi_crypto.hpp"
#include "wifi_store.hpp"
#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <vector>

namespace phonecam::wifi {
// macOS: ~/Library/Application Support/phonecam-obs/wifi.json, mode 600. Windows: %APPDATA%\phonecam-obs\wifi.json.
// PHONECAM_WIFI_STORE overrides the path (tests). A new computer id is created only when the file does not
// exist; a file that exists but cannot be read throws instead of being replaced, so pairings are never lost
// to a passing lock or a corrupt write. Use Server::forgetAll to start over.
Pairings loadPairings();
void savePairings(const Pairings &value);

struct PairingWindow { std::string code, link; };
std::string macName();                      // this computer's name, shown on the phone
std::vector<std::string> localAddresses();  // up, non-loopback, non-link-local IPv4, at most 4
// The pairing card shown in the OBS source: QR code, typed code and steps, as a BGRA image.
std::vector<uint8_t> renderPairingCard(const PairingWindow &window, uint32_t width, uint32_t height);
// Phones are accepted only from the local network: private, link-local or loopback IPv4 (docs/WIFI.md).
bool localPeer(uint32_t hostOrderAddress);

class Server {
public:
    using Status = std::function<void(const std::string &)>;
    // note: log-only lines that must not replace the status line (for example ignored outside connections).
    Server(Status status, Output output, Status note = nullptr)
        : status(std::move(status)), output(std::move(output)), note(std::move(note)) {}
    // Until stop: listens only while a phone is paired or a pairing window is open, one connection at a time
    // (pairing or live video). Never throws: problems are reported through status.
    void run(std::atomic<bool> &stop);
    // Opens a 3-minute pairing window and returns the code and QR link to show. Throws if Wi-Fi is unavailable.
    PairingWindow openPairing();
    void closePairing();
    bool pairingOpen();
    // Short line for the source properties: addresses and paired phones.
    std::string summary();
    // Forgets every paired phone, in this server and in the pairings file.
    void forget();
    // The same without a running server. Starts a new file if the old one cannot be read.
    static Pairings forgetAll();
    int listeningPort() const { return port; }
    // For a watchdog on another thread: what the server thread is doing, if it has been doing it for longer
    // than limitMs and it is not a step that normally lasts (waiting for phones, receiving video). Else null.
    const char *stuckLongerThan(int64_t limitMs) const;
    std::string computerId();
private:
    Status status;
    Output output;
    Status note;
    std::mutex lock;
    Pairings pairings;                             // the pairings file, read once; changed only through the server
    Bytes secret;                                  // current pairing window; empty when closed
    std::chrono::steady_clock::time_point expires;
    int failures = 0;
    std::atomic<int> port{0};
    std::atomic<bool> ready{false};                // pairings loaded
    std::atomic<bool> announceNeeded{false};       // ask the announcer thread to announce again; only it touches mDNS
    enum Step { Waiting, Opening, Withdrawing, Connecting, Saving, Streaming };
    std::atomic<int64_t> announceSince{0};        // the announcer thread is inside DNS-SD since (0: idle)
    std::atomic<int> step{Waiting};
    std::atomic<int64_t> stepSince{0};
    void doing(Step value);
    void *bonjour = nullptr;                       // platform announcement handle (server thread only)
    void announce();                               // (re)announces with the current pairing state
    void withdraw();                               // stops announcing
    void loop(std::atomic<bool> &stop);
    void handle(std::uintptr_t fd, std::atomic<bool> &stop);
};
}
