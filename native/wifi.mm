#import <Foundation/Foundation.h>
// macOS half of direct Wi-Fi: pairing storage (JSON via Foundation), the Mac's name and addresses, and the
// Bonjour announcement (dns_sd). The server itself is shared: wifi_server.cpp.
#include "wifi.hpp"
#include <arpa/inet.h>
#include <dns_sd.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <sys/stat.h>

namespace phonecam::wifi {
namespace {
// Never nil, even for bytes that are not UTF-8 (a nil value in a dictionary literal raises and aborts OBS).
NSString *text(const std::string &value) {
    return [[NSString alloc] initWithBytes:value.data() length:value.size() encoding:NSUTF8StringEncoding] ?: @"";
}
NSString *storePath() {
    if (const char *test = getenv("PHONECAM_WIFI_STORE")) return @(test);  // tests never touch the real pairings
    return [NSHomeDirectory() stringByAppendingPathComponent:@"Library/Application Support/phonecam-obs/wifi.json"];
}
}

Pairings loadPairings() {
    @autoreleasepool {
        Pairings result;
        NSString *path = storePath();
        if (![[NSFileManager defaultManager] fileExistsAtPath:path]) {  // first use: a new computer id
            Bytes id = randomBytes(8);
            result.macId = hexString(id.data(), id.size());
            savePairings(result);
            return result;
        }
        NSData *data = [NSData dataWithContentsOfFile:path];
        id json = data.length && data.length < 1024 * 1024 ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
        if (![json isKindOfClass:[NSDictionary class]]) throw std::runtime_error("The Wi-Fi pairings file could not be read.");
        id mac = json[@"macId"];
        if ([mac isKindOfClass:[NSString class]] && [mac length] == 16) result.macId = [mac UTF8String];
        Bytes check;
        if (result.macId.size() != 16 || !fromHex(result.macId, check)) throw std::runtime_error("The Wi-Fi pairings file is damaged.");
        id phones = json[@"phones"];
        if ([phones isKindOfClass:[NSArray class]]) for (id item in phones) {
            if (![item isKindOfClass:[NSDictionary class]]) continue;
            id pid = item[@"id"], name = item[@"name"], key = item[@"key"];
            if (![pid isKindOfClass:[NSString class]] || ![name isKindOfClass:[NSString class]] || ![key isKindOfClass:[NSString class]]) continue;
            PairedPhone phone{[pid UTF8String], cleanName([name UTF8String]), {}};
            if (phone.id.size() == 16 && fromHex([key UTF8String], phone.key) && phone.key.size() == keyBytes) result.phones.push_back(phone);
        }
        return result;
    }
}

void savePairings(const Pairings &value) {
    @autoreleasepool {
        NSMutableArray *phones = [NSMutableArray array];
        for (const auto &p : value.phones)
            [phones addObject:@{@"id": text(p.id), @"name": text(p.name), @"key": text(hexString(p.key.data(), p.key.size()))}];
        NSData *data = [NSJSONSerialization dataWithJSONObject:@{@"macId": text(value.macId), @"phones": phones} options:0 error:nil];
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

void Server::announce() {
    if (!port) return;
    std::string id = computerId();
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

void Server::withdraw() {
    if (bonjour) { DNSServiceRefDeallocate(static_cast<DNSServiceRef>(bonjour)); bonjour = nullptr; }
}
}
