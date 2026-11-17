// Direct Wi-Fi server shared by macOS and Windows (docs/WIFI.md): pairing window, handshake, encrypted
// records and the listen loop. Platform files supply storage, names, addresses and the announcement.
#include "wifi.hpp"
#include "wifi_net.hpp"
#include <algorithm>
#include <chrono>
#include <cstring>
#include <thread>

namespace phonecam::wifi {
namespace {
constexpr int preferredPort = 47183;
constexpr auto pairingLifetime = std::chrono::minutes(3);
constexpr int maxPairingFailures = 20;

void readAll(net::Fd fd, uint8_t *p, size_t size, std::atomic<bool> &stop, int seconds) {
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    while (size) {
        if (stop) throw std::runtime_error("Stopped");
        if (std::chrono::steady_clock::now() > deadline) throw std::runtime_error("video stalled for " + std::to_string(seconds) + " seconds");
        int ready = net::pollRead(fd, 50);
        if (ready < 0 && net::interrupted()) continue;
        if (ready < 0) throw std::runtime_error("connection poll failed");
        if (!ready) continue;
        long count = net::receive(fd, p, size);
        if (count <= 0) throw Closed("connection closed");
        size -= size_t(count); p += count;
    }
}
void writeAll(net::Fd fd, const Bytes &data) {
    size_t sent = 0;
    while (sent < data.size()) {
        long count = net::transmit(fd, data.data() + sent, data.size() - sent);
        if (count <= 0) throw std::runtime_error("phone connection closed");
        sent += size_t(count);
    }
}
// Decrypts records (docs/WIFI.md) and hands out plain PCAM bytes to decodeStream.
class RecordReader {
    net::Fd fd; SessionKeys keys; std::atomic<bool> &stop;
    uint64_t record = 0; Bytes plain; size_t position = 0;
public:
    RecordReader(net::Fd fd, SessionKeys keys, std::atomic<bool> &stop) : fd(fd), keys(std::move(keys)), stop(stop) {}
    void read(uint8_t *p, size_t size, int seconds) {
        while (size) {
            if (position == plain.size()) {
                uint8_t length[4]; readAll(fd, length, 4, stop, seconds);
                uint32_t n = uint32_t(length[0]) << 24 | uint32_t(length[1]) << 16 | uint32_t(length[2]) << 8 | length[3];
                if (n == 0 || n > maxRecord) throw std::runtime_error("Wi-Fi record has an invalid length");
                Bytes cipher(n), tag(recordTagBytes);
                readAll(fd, cipher.data(), n, stop, seconds);
                readAll(fd, tag.data(), tag.size(), stop, seconds);
                plain = openRecord(keys, record++, cipher, tag);
                position = 0;
            }
            size_t take = std::min(size, plain.size() - position);
            std::memcpy(p, plain.data() + position, take);
            p += take; size -= take; position += take;
        }
    }
};
// One pairings file per OS user, shared by every PhoneCam source in OBS: reads and writes never overlap.
std::mutex storeLock;
Pairings readPairings() { std::lock_guard<std::mutex> guard(storeLock); return loadPairings(); }
void pauseFor(std::atomic<bool> &stop, int milliseconds) {
    for (int waited = 0; waited < milliseconds && !stop; waited += 20) std::this_thread::sleep_for(std::chrono::milliseconds(20));
}
// Percent-encodes everything except ASCII letters and digits (the phone URL-decodes the name).
std::string percentEncode(const std::string &text) {
    static const char *digits = "0123456789ABCDEF";
    std::string out;
    for (unsigned char c : text) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) out += char(c);
        else { out += '%'; out += digits[c >> 4]; out += digits[c & 15]; }
    }
    return out;
}
}

bool localPeer(uint32_t a) {
    return (a >> 24) == 10 || (a & 0xFFF00000u) == 0xAC100000u || (a >> 16) == 0xC0A8 ||  // 10/8, 172.16/12, 192.168/16
           (a >> 16) == 0xA9FE || (a >> 24) == 127;                                     // link-local, loopback
}

static int64_t steadyNowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
void Server::doing(Step value) { step = value; stepSince = steadyNowMs(); }
const char *Server::stuckLongerThan(int64_t limitMs) const {
    const int now = step;
    if (now == Waiting || now == Streaming || steadyNowMs() - stepSince < limitMs) return nullptr;
    switch (now) {
        case Opening: return "opening the network port";
        case Announcing: return "announcing on the network (DNS-SD)";
        case Withdrawing: return "withdrawing the network announcement (DNS-SD)";
        case Connecting: return "talking to a phone that connected";
        case Saving: return "saving the pairing";
        default: return "working";
    }
}

std::string Server::computerId() { std::lock_guard<std::mutex> guard(lock); return pairings.macId; }

PairingWindow Server::openPairing() {
    for (int i = 0; i < 100 && !ready; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(20));
    if (!ready) throw std::runtime_error("Direct Wi-Fi is not ready. Hide and show this source, then try again.");
    std::string code;
    {
        std::lock_guard<std::mutex> guard(lock);
        secret = randomBytes(codeBytes); failures = 0;
        expires = std::chrono::steady_clock::now() + pairingLifetime;
        code = encodeCode(secret);
    }
    announceNeeded = true;
    // The server thread opens the listener as soon as a window is open (it stays closed with nothing paired).
    for (int i = 0; i < 150 && !port; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(20));
    if (!port) { closePairing(); throw std::runtime_error("Direct Wi-Fi could not open a network port."); }
    std::string addresses;
    for (const auto &a : localAddresses()) addresses += (addresses.empty() ? "" : ",") + a;
    std::string link = "phonecam://pair?v=1&id=" + computerId() + "&c=" + code + "&p=" + std::to_string(port) +
                       "&a=" + addresses + "&n=" + percentEncode(macName());
    return {formatCode(code), link};
}
void Server::closePairing() {
    { std::lock_guard<std::mutex> guard(lock); secret.clear(); }
    announceNeeded = true;
}
bool Server::pairingOpen() {
    std::lock_guard<std::mutex> guard(lock);
    return !secret.empty() && std::chrono::steady_clock::now() < expires;
}
std::string Server::summary() {
    std::vector<std::string> names;
    { std::lock_guard<std::mutex> guard(lock); for (const auto &p : pairings.phones) names.push_back(p.name); }
    if (!ready) return "Wi-Fi: not available";
    std::string text;
    if (port) {
        auto addresses = localAddresses();
        text = "Wi-Fi: ready" + (addresses.empty() ? std::string() : " on " + addresses[0]);
    } else text = "Wi-Fi: listens only while pairing or once a phone is paired";
    text += names.empty() ? " · No paired phones yet" : " · Paired: ";
    for (size_t i = 0; i < names.size(); ++i) text += (i ? ", " : "") + names[i];
    return text;
}
Pairings Server::forgetAll() {
    std::lock_guard<std::mutex> guard(storeLock);
    Pairings value;
    try { value = loadPairings(); }
    catch (const std::exception &) {  // unreadable file: forgetting is the way to start over
        Bytes id = randomBytes(8);
        value.macId = hexString(id.data(), id.size());
    }
    value.phones.clear();
    savePairings(value);
    return value;
}
void Server::forget() {
    Pairings value = forgetAll();
    std::lock_guard<std::mutex> guard(lock);
    pairings = value;
}

void Server::run(std::atomic<bool> &stop) {
    try { loop(stop); }
    catch (const std::exception &error) { status(std::string("Direct Wi-Fi stopped: ") + error.what()); }
    catch (...) { status("Direct Wi-Fi stopped after an unexpected error."); }
    try { withdraw(); } catch (...) {}
    ready = false;
}

void Server::loop(std::atomic<bool> &stop) {
    net::startup();
    Pairings loaded;
    try { loaded = readPairings(); }
    catch (const std::exception &error) {
        status(std::string("Direct Wi-Fi is off: ") + error.what() + " Click Forget paired phones to start over.");
        return;
    }
    { std::lock_guard<std::mutex> guard(lock); pairings = std::move(loaded); }
    ready = true;
    net::Fd listener = net::invalid;
    auto closeListener = [&] {
        if (listener == net::invalid) return;
        doing(Withdrawing);
        withdraw();
        net::closeFd(listener); listener = net::invalid; port = 0;
        if (note) note("Wi-Fi: stopped listening (nothing paired and no pairing window).");
    };
    bool portProblem = false;
    bool ignoredNoted = false;
    std::chrono::steady_clock::time_point lastIgnored;
    while (!stop) {
        doing(Waiting);
        bool expired, wanted;
        {
            std::lock_guard<std::mutex> guard(lock);
            expired = !secret.empty() && std::chrono::steady_clock::now() >= expires;
            if (expired) secret.clear();
            wanted = !pairings.phones.empty() || !secret.empty();
        }
        if (expired) { announceNeeded = true; status("Pairing window closed. Click Pair a phone over Wi-Fi to try again."); }
        // Nothing paired and no window open: no listening port at all (audit/FINDINGS.md AUDIT-006).
        if (!wanted) { closeListener(); pauseFor(stop, 200); continue; }
        if (listener == net::invalid) {
            doing(Opening);
            listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
            if (listener == net::invalid) {
                if (!portProblem) status("Direct Wi-Fi unavailable: cannot open a network socket.");
                portProblem = true; pauseFor(stop, 1000); continue;
            }
            net::prepareListener(listener);
            sockaddr_in address{}; address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_ANY);
            address.sin_port = htons(preferredPort);
            if (bind(listener, reinterpret_cast<sockaddr *>(&address), net::Length(sizeof(address)))) {
                address.sin_port = 0;  // another PhoneCam source or app has 47183; Bonjour tells the phone the real port
                if (bind(listener, reinterpret_cast<sockaddr *>(&address), net::Length(sizeof(address)))) {
                    net::closeFd(listener); listener = net::invalid;
                    if (!portProblem) status("Direct Wi-Fi unavailable: no free port.");
                    portProblem = true; pauseFor(stop, 1000); continue;
                }
            }
            net::Length length = net::Length(sizeof(address));
            getsockname(listener, reinterpret_cast<sockaddr *>(&address), &length);
            listen(listener, 4);
            portProblem = false;
            port = ntohs(address.sin_port);
            announceNeeded = true;
            if (note) note("Wi-Fi: listening on port " + std::to_string(port) + ".");
        }
        if (announceNeeded.exchange(false)) { doing(Announcing); announce(); }
        doing(Waiting);
        if (net::pollRead(listener, 200) <= 0) continue;
        sockaddr_in peer{}; net::Length peerLength = net::Length(sizeof(peer));
        net::Fd fd = accept(listener, reinterpret_cast<sockaddr *>(&peer), &peerLength);
        if (fd == net::invalid) { if (note) note("Wi-Fi: a connection could not be accepted."); continue; }
        if (peer.sin_family != AF_INET || !localPeer(ntohl(peer.sin_addr.s_addr))) {
            net::closeFd(fd);
            // Log at most once a minute, without the address, so a public network's refusals can be told apart
            // from client isolation (where nothing arrives at all).
            auto now = std::chrono::steady_clock::now();
            if (note && (!ignoredNoted || now - lastIgnored >= std::chrono::minutes(1))) {
                ignoredNoted = true; lastIgnored = now;
                note("Ignored a Wi-Fi connection from outside the local network. Only private, link-local and loopback addresses may connect.");
            }
            continue;
        }
        net::prepareConnection(fd);
        doing(Connecting);
        try { handle(std::uintptr_t(fd), stop); }
        catch (const NotStreaming &) {}
        catch (const std::exception &error) { if (!stop) status(std::string("Wi-Fi live ended: ") + error.what() + ". Tap Start live on the phone to go live again."); }
        doing(Withdrawing);  // closing the connection can wait for the network
        net::closeFd(fd);
        doing(Waiting);
    }
    closeListener();
}

void Server::handle(std::uintptr_t socketHandle, std::atomic<bool> &stop) {
    const auto fd = net::Fd(socketHandle);
    Bytes hello(clientHelloBytes);
    readAll(fd, hello.data(), hello.size(), stop, 5);
    if (std::memcmp(hello.data(), "PCW1", 4) || (hello[4] != 1 && hello[4] != 2)) return;  // not a PhoneCam phone
    bool pairing = hello[4] == 2;
    std::string macId = hexString(hello.data() + 5, 8), phoneId = hexString(hello.data() + 13, 8);
    auto refuse = [&](uint8_t code) { writeAll(fd, Bytes{'P', 'C', 'W', '1', code}); };
    if (macId != computerId()) { refuse(1); return; }
    Bytes key;
    if (pairing) {
        std::lock_guard<std::mutex> guard(lock);
        if (secret.empty() || std::chrono::steady_clock::now() >= expires) { refuse(2); return; }
        key = codeKey(secret);
    } else {
        auto find = [&] { std::lock_guard<std::mutex> guard(lock); for (const auto &p : pairings.phones) if (p.id == phoneId) key = p.key; };
        find();
        if (key.empty()) {  // perhaps paired through another PhoneCam source since this one started
            try { Pairings fresh = readPairings(); std::lock_guard<std::mutex> guard(lock); if (fresh.macId == pairings.macId) pairings = std::move(fresh); }
            catch (const std::exception &) {}
            find();
        }
        if (key.empty()) { refuse(1); return; }
    }
    Ephemeral session;
    Bytes z = session.shared(Bytes(hello.begin() + 21, hello.begin() + 86));
    Bytes prefix{'P', 'C', 'W', '1', 0};
    Bytes mine = session.publicKey(), nonce = randomBytes(nonceBytes);
    prefix.insert(prefix.end(), mine.begin(), mine.end());
    prefix.insert(prefix.end(), nonce.begin(), nonce.end());
    Handshake shake(key, z, join(hello, prefix));
    writeAll(fd, join(prefix, shake.tagM));
    Bytes finish(tagBytes + 1);
    auto failed = [&](const char *message) {
        bool lockedOut = false;
        if (pairing) {
            std::lock_guard<std::mutex> guard(lock);
            if (++failures >= maxPairingFailures && !secret.empty()) { secret.clear(); lockedOut = true; }  // against repeated guessing
        }
        status(message);
        if (lockedOut) { announceNeeded = true; status("Pairing window closed after too many failed attempts. Click Pair a phone over Wi-Fi to try again."); }
    };
    // The phone checks tagM first and hangs up if the code (or long-term key) does not match.
    try { readAll(fd, finish.data(), finish.size(), stop, pairing ? 30 : 5); }
    catch (const Closed &) {
        failed(pairing ? "A phone tried to pair with the wrong code (or cancelled). Check the code and try again."
                       : "A phone did not accept this computer's Wi-Fi key. Pair it again.");
        return;
    }
    Bytes name(finish.back());
    if (name.size() > 64) return;
    if (!name.empty()) readAll(fd, name.data(), name.size(), stop, 5);
    if (!equalConstantTime(Bytes(finish.begin(), finish.begin() + tagBytes), shake.tagP)) {
        failed(pairing ? "A phone tried to pair with the wrong code. Check the code and try again." : "A phone failed the Wi-Fi security check. Pair it again.");
        return;
    }
    SessionKeys keys = shake.keys();
    if (pairing) {
        // Saved before the phone is told it is paired: if saving fails, neither side keeps a half pairing.
        std::string phoneName = cleanName(std::string(name.begin(), name.end()));
        Pairings updated;
        doing(Saving);
        {
            std::lock_guard<std::mutex> guard(storeLock);
            try { updated = loadPairings(); } catch (const std::exception &) { std::lock_guard<std::mutex> inner(lock); updated = pairings; }
            std::vector<PairedPhone> kept;
            for (auto &p : updated.phones) if (p.id != phoneId) kept.push_back(p);
            kept.push_back({phoneId, phoneName, keys.longTerm});
            updated.phones = kept;
            savePairings(updated);
        }
        { std::lock_guard<std::mutex> guard(lock); pairings = updated; }
        writeAll(fd, Bytes{0});
        closePairing();
        status("Paired with " + phoneName + " over Wi-Fi. Tap Start live on the phone.");
        return;
    }
    writeAll(fd, Bytes{0});
    doing(Streaming);
    RecordReader reader(fd, keys, stop);
    bool announced = false;
    decodeStream(stop, [&](uint8_t *p, size_t n, int seconds) { reader.read(p, n, seconds); },
        [&](Image image, uint64_t sequence, uint64_t pts) {
            if (!announced) {
                announced = true;
                status("Live over direct Wi-Fi: " + std::to_string(imageWidth(image)) + "×" + std::to_string(imageHeight(image)) + ", " + decoderName() + ".");
            }
            output(image, sequence, pts);
        });
}
}
