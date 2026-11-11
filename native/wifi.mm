#import <Foundation/Foundation.h>
#include "wifi.hpp"
#include <arpa/inet.h>
#include <dns_sd.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>

namespace phonecam::wifi {
namespace {
constexpr int preferredPort = 47183;
constexpr auto pairingLifetime = std::chrono::minutes(3);
constexpr int maxPairingFailures = 20;

std::string hexString(const uint8_t *p, size_t n) {
    static const char *digits = "0123456789abcdef";
    std::string out; for (size_t i = 0; i < n; ++i) { out += digits[p[i] >> 4]; out += digits[p[i] & 15]; }
    return out;
}
bool fromHex(const std::string &text, Bytes &out) {
    if (text.size() % 2) return false;
    out.clear();
    for (size_t i = 0; i < text.size(); i += 2) {
        auto v = [](char c) { return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1; };
        int hi = v(text[i]), lo = v(text[i + 1]);
        if (hi < 0 || lo < 0) return false;
        out.push_back(uint8_t(hi << 4 | lo));
    }
    return true;
}
NSString *storePath() {
    if (const char *test = getenv("PHONECAM_WIFI_STORE")) return @(test);  // tests never touch the real pairings
    return [NSHomeDirectory() stringByAppendingPathComponent:@"Library/Application Support/phonecam-obs/wifi.json"];
}
void readAll(int fd, uint8_t *p, size_t size, std::atomic<bool> &stop, int seconds) {
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    while (size) {
        if (stop) throw std::runtime_error("Stopped");
        if (std::chrono::steady_clock::now() > deadline) throw std::runtime_error("video stalled for " + std::to_string(seconds) + " seconds");
        pollfd event{fd, POLLIN, 0};
        int ready = poll(&event, 1, 50);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) throw std::runtime_error("connection poll failed");
        if (!ready) continue;
        ssize_t count = recv(fd, p, size, 0);
        if (count <= 0) throw Closed("connection closed");
        size -= size_t(count); p += count;
    }
}
void writeAll(int fd, const Bytes &data) {
    size_t sent = 0;
    while (sent < data.size()) {
        ssize_t count = send(fd, data.data() + sent, data.size() - sent, 0);
        if (count <= 0) throw std::runtime_error("phone connection closed");
        sent += size_t(count);
    }
}
// Decrypts records (docs/WIFI.md) and hands out plain PCAM bytes to decodeStream.
class RecordReader {
    int fd; SessionKeys keys; std::atomic<bool> &stop;
    uint64_t record = 0; Bytes plain; size_t position = 0;
public:
    RecordReader(int fd, SessionKeys keys, std::atomic<bool> &stop) : fd(fd), keys(std::move(keys)), stop(stop) {}
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
std::string cleanName(const std::string &raw) {
    std::string out;
    for (unsigned char c : raw) if (c >= 32 && c != 127 && out.size() < 64) out += char(c);
    return out.empty() ? "Phone" : out;
}
}

Pairings loadPairings() {
    @autoreleasepool {
        Pairings result;
        NSData *data = [NSData dataWithContentsOfFile:storePath()];
        id json = data.length && data.length < 1024 * 1024 ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
        if ([json isKindOfClass:[NSDictionary class]]) {
            id mac = json[@"macId"];
            if ([mac isKindOfClass:[NSString class]] && [mac length] == 16) result.macId = [mac UTF8String];
            id phones = json[@"phones"];
            if ([phones isKindOfClass:[NSArray class]]) for (id item in phones) {
                if (![item isKindOfClass:[NSDictionary class]]) continue;
                id pid = item[@"id"], name = item[@"name"], key = item[@"key"];
                if (![pid isKindOfClass:[NSString class]] || ![name isKindOfClass:[NSString class]] || ![key isKindOfClass:[NSString class]]) continue;
                PairedPhone phone{[pid UTF8String], cleanName([name UTF8String]), {}};
                if (phone.id.size() == 16 && fromHex([key UTF8String], phone.key) && phone.key.size() == keyBytes) result.phones.push_back(phone);
            }
        }
        Bytes check;
        if (result.macId.size() != 16 || !fromHex(result.macId, check)) {
            Bytes id = randomBytes(8);
            result.macId = hexString(id.data(), id.size());
            result.phones.clear();
            savePairings(result);
        }
        return result;
    }
}

void savePairings(const Pairings &value) {
    @autoreleasepool {
        NSMutableArray *phones = [NSMutableArray array];
        for (const auto &p : value.phones)
            [phones addObject:@{@"id": @(p.id.c_str()), @"name": @(p.name.c_str()), @"key": @(hexString(p.key.data(), p.key.size()).c_str())}];
        NSData *data = [NSJSONSerialization dataWithJSONObject:@{@"macId": @(value.macId.c_str()), @"phones": phones} options:0 error:nil];
        NSString *path = storePath(), *folder = [path stringByDeletingLastPathComponent];
        [[NSFileManager defaultManager] createDirectoryAtPath:folder withIntermediateDirectories:YES attributes:@{NSFilePosixPermissions: @0700} error:nil];
        if (!data || ![data writeToFile:path options:NSDataWritingAtomic error:nil]) throw std::runtime_error("Could not save Wi-Fi pairings");
        chmod(path.fileSystemRepresentation, 0600);
    }
}

std::string macName() {
    @autoreleasepool {
        NSString *name = [[NSHost currentHost] localizedName];
        return name.length ? cleanName(name.UTF8String) : "Mac";
    }
}

std::vector<std::string> localAddresses() {
    std::vector<std::string> out;
    ifaddrs *list = nullptr;
    if (getifaddrs(&list)) return out;
    for (ifaddrs *i = list; i && out.size() < 4; i = i->ifa_next) {
        if (!i->ifa_addr || i->ifa_addr->sa_family != AF_INET || !(i->ifa_flags & IFF_UP) || (i->ifa_flags & IFF_LOOPBACK)) continue;
        auto *address = reinterpret_cast<sockaddr_in *>(i->ifa_addr);
        uint32_t host = ntohl(address->sin_addr.s_addr);
        if ((host >> 16) == 0xA9FE) continue;  // 169.254.x.x: no network
        char text[INET_ADDRSTRLEN];
        if (inet_ntop(AF_INET, &address->sin_addr, text, sizeof(text))) out.push_back(text);
    }
    freeifaddrs(list);
    return out;
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
    std::string name;
    @autoreleasepool {
        name = [@(macName().c_str()) stringByAddingPercentEncodingWithAllowedCharacters:[NSCharacterSet alphanumericCharacterSet]].UTF8String;
    }
    std::string link = "phonecam://pair?v=1&id=" + loadPairings().macId + "&c=" + code + "&p=" + std::to_string(port) +
                       "&a=" + addresses + "&n=" + name;
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
    std::string text = port ? "Direct Wi-Fi: listening on port " + std::to_string(port) : "Direct Wi-Fi: not listening";
    auto addresses = localAddresses();
    if (port && !addresses.empty()) text += " (" + addresses[0] + ")";
    text += ". Paired phones: ";
    if (pairings.phones.empty()) text += "none yet.";
    for (size_t i = 0; i < pairings.phones.size(); ++i) text += (i ? ", " : "") + pairings.phones[i].name;
    return text;
}
void Server::forgetAll() {
    auto pairings = loadPairings();
    pairings.phones.clear();
    savePairings(pairings);
}

void Server::announce() {
    if (!port) return;
    std::string id = loadPairings().macId;
    bool open = pairingOpen();
    TXTRecordRef txt; TXTRecordCreate(&txt, 0, nullptr);
    TXTRecordSetValue(&txt, "v", 1, "1");
    TXTRecordSetValue(&txt, "id", uint8_t(id.size()), id.data());
    if (open) TXTRecordSetValue(&txt, "pair", 1, "1");
    if (bonjour) DNSServiceUpdateRecord(static_cast<DNSServiceRef>(bonjour), nullptr, 0, TXTRecordGetLength(&txt), TXTRecordGetBytesPtr(&txt), 0);
    else {
        DNSServiceRef ref = nullptr;
        std::string name = "PhoneCam on " + macName();
        if (DNSServiceRegister(&ref, 0, 0, name.c_str(), "_phonecam._tcp", nullptr, nullptr, htons(uint16_t(port)),
                               TXTRecordGetLength(&txt), TXTRecordGetBytesPtr(&txt), nullptr, nullptr) == kDNSServiceErr_NoError) bonjour = ref;
    }
    TXTRecordDeallocate(&txt);
}

void Server::run(std::atomic<bool> &stop) {
    int listener = socket(AF_INET, SOCK_STREAM, 0);
    if (listener < 0) { status("Direct Wi-Fi unavailable: cannot open a network socket."); return; }
    int yes = 1; setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    sockaddr_in address{}; address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(preferredPort);
    if (bind(listener, reinterpret_cast<sockaddr *>(&address), sizeof(address))) {
        address.sin_port = 0;  // another PhoneCam source or app has 47183; Bonjour tells the phone the real port
        if (bind(listener, reinterpret_cast<sockaddr *>(&address), sizeof(address))) { close(listener); status("Direct Wi-Fi unavailable: no free port."); return; }
    }
    socklen_t length = sizeof(address);
    getsockname(listener, reinterpret_cast<sockaddr *>(&address), &length);
    listen(listener, 4);
    port = ntohs(address.sin_port);
    announce();
    while (!stop) {
        bool expired;
        { std::lock_guard<std::mutex> guard(lock); expired = !secret.empty() && std::chrono::steady_clock::now() >= expires; }
        if (expired) { closePairing(); status("Pairing window closed. Click Pair a phone over Wi-Fi to try again."); }
        pollfd event{listener, POLLIN, 0};
        if (poll(&event, 1, 200) <= 0) continue;
        int fd = accept(listener, nullptr, nullptr);
        if (fd < 0) continue;
        int on = 1; setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, sizeof(on));
        try { handle(fd, stop); }
        catch (const NotStreaming &) {}
        catch (const std::exception &error) { if (!stop) status(std::string("Wi-Fi live ended: ") + error.what() + ". Tap Start live on the phone to go live again."); }
        close(fd);
    }
    if (bonjour) { DNSServiceRefDeallocate(static_cast<DNSServiceRef>(bonjour)); bonjour = nullptr; }
    port = 0;
    close(listener);
}

void Server::handle(int fd, std::atomic<bool> &stop) {
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
                       : "A phone did not accept this Mac's Wi-Fi key. Pair it again.");
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
        [&](CVPixelBufferRef image, uint64_t sequence, uint64_t pts) {
            if (!announced) {
                announced = true;
                status("Live over direct Wi-Fi: " + std::to_string(CVPixelBufferGetWidth(image)) + "×" + std::to_string(CVPixelBufferGetHeight(image)) + ", hardware decoding.");
            }
            output(image, sequence, pts);
        });
}
}
