// Direct Wi-Fi server shared by macOS and Windows (docs/WIFI.md): pairing window, handshake, encrypted
// records and the listen loop. Platform files supply storage, names, addresses and the announcement.
#include "wifi.hpp"
#include "wifi_net.hpp"
#include <algorithm>
#include <chrono>
#include <cstring>

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

PairingWindow Server::openPairing() {
    std::string code;
    {
        std::lock_guard<std::mutex> guard(lock);
        secret = randomBytes(codeBytes); failures = 0;
        expires = std::chrono::steady_clock::now() + pairingLifetime;
        code = encodeCode(secret);
    }
    announce();
    std::string addresses;
    for (const auto &a : localAddresses()) addresses += (addresses.empty() ? "" : ",") + a;
    std::string link = "phonecam://pair?v=1&id=" + loadPairings().macId + "&c=" + code + "&p=" + std::to_string(port) +
                       "&a=" + addresses + "&n=" + percentEncode(macName());
    return {formatCode(code), link};
}
void Server::closePairing() {
    { std::lock_guard<std::mutex> guard(lock); secret.clear(); }
    announce();
}
bool Server::pairingOpen() {
    std::lock_guard<std::mutex> guard(lock);
    return !secret.empty() && std::chrono::steady_clock::now() < expires;
}
std::string Server::summary() {
    auto pairings = loadPairings();
    auto addresses = localAddresses();
    std::string text = !port ? "Wi-Fi: not available" : "Wi-Fi: ready" + (addresses.empty() ? std::string() : " on " + addresses[0]);
    text += pairings.phones.empty() ? " · No paired phones yet" : " · Paired: ";
    for (size_t i = 0; i < pairings.phones.size(); ++i) text += (i ? ", " : "") + pairings.phones[i].name;
    return text;
}
void Server::forgetAll() {
    auto pairings = loadPairings();
    pairings.phones.clear();
    savePairings(pairings);
}

void Server::run(std::atomic<bool> &stop) {
    net::startup();
    net::Fd listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (listener == net::invalid) { status("Direct Wi-Fi unavailable: cannot open a network socket."); return; }
    net::prepareListener(listener);
    sockaddr_in address{}; address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(preferredPort);
    if (bind(listener, reinterpret_cast<sockaddr *>(&address), net::Length(sizeof(address)))) {
        address.sin_port = 0;  // another PhoneCam source or app has 47183; Bonjour tells the phone the real port
        if (bind(listener, reinterpret_cast<sockaddr *>(&address), net::Length(sizeof(address)))) { net::closeFd(listener); status("Direct Wi-Fi unavailable: no free port."); return; }
    }
    net::Length length = net::Length(sizeof(address));
    getsockname(listener, reinterpret_cast<sockaddr *>(&address), &length);
    listen(listener, 4);
    port = ntohs(address.sin_port);
    announce();
    while (!stop) {
        bool expired;
        { std::lock_guard<std::mutex> guard(lock); expired = !secret.empty() && std::chrono::steady_clock::now() >= expires; }
        if (expired) { closePairing(); status("Pairing window closed. Click Pair a phone over Wi-Fi to try again."); }
        if (net::pollRead(listener, 200) <= 0) continue;
        net::Fd fd = accept(listener, nullptr, nullptr);
        if (fd == net::invalid) continue;
        net::prepareConnection(fd);
        try { handle(std::uintptr_t(fd), stop); }
        catch (const NotStreaming &) {}
        catch (const std::exception &error) { if (!stop) status(std::string("Wi-Fi live ended: ") + error.what() + ". Tap Start live on the phone to go live again."); }
        net::closeFd(fd);
    }
    withdraw();
    port = 0;
    net::closeFd(listener);
}

void Server::handle(std::uintptr_t socketHandle, std::atomic<bool> &stop) {
    const auto fd = net::Fd(socketHandle);
    Bytes hello(clientHelloBytes);
    readAll(fd, hello.data(), hello.size(), stop, 5);
    if (std::memcmp(hello.data(), "PCW1", 4) || (hello[4] != 1 && hello[4] != 2)) return;  // not a PhoneCam phone
    bool pairing = hello[4] == 2;
    auto pairings = loadPairings();
    std::string macId = hexString(hello.data() + 5, 8), phoneId = hexString(hello.data() + 13, 8);
    auto refuse = [&](uint8_t code) { writeAll(fd, Bytes{'P', 'C', 'W', '1', code}); };
    if (macId != pairings.macId) { refuse(1); return; }
    Bytes key;
    if (pairing) {
        std::lock_guard<std::mutex> guard(lock);
        if (secret.empty() || std::chrono::steady_clock::now() >= expires) { refuse(2); return; }
        key = codeKey(secret);
    } else {
        for (const auto &p : pairings.phones) if (p.id == phoneId) key = p.key;
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
        if (pairing) {
            std::lock_guard<std::mutex> guard(lock);
            if (++failures >= maxPairingFailures) secret.clear();  // closes the window against repeated guessing
        }
        status(message);
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
    writeAll(fd, Bytes{0});
    SessionKeys keys = shake.keys();
    if (pairing) {
        std::string phoneName = cleanName(std::string(name.begin(), name.end()));
        auto updated = loadPairings();
        std::vector<PairedPhone> kept;
        for (auto &p : updated.phones) if (p.id != phoneId) kept.push_back(p);
        kept.push_back({phoneId, phoneName, keys.longTerm});
        updated.phones = kept;
        savePairings(updated);
        closePairing();
        status("Paired with " + phoneName + " over Wi-Fi. Tap Start live on the phone.");
        return;
    }
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
