// Windows half of direct Wi-Fi: pairing storage (%APPDATA%\phonecam-obs\wifi.json), the PC's name and
// addresses, and the mDNS announcement through Windows' own DNS-SD (DnsServiceRegister, Windows 10 1809+).
// The server itself is shared: wifi_server.cpp.
#include "wifi.hpp"
#include "wifi_net.hpp"
#include <iphlpapi.h>
#include <windns.h>
#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <memory>
#include <mutex>
#include <sstream>

namespace phonecam::wifi {
namespace {
std::wstring wide(const std::string &text) {
    if (text.empty()) return {};
    int size = MultiByteToWideChar(CP_UTF8, 0, text.data(), int(text.size()), nullptr, 0);
    std::wstring out(size_t(size), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text.data(), int(text.size()), out.data(), size);
    return out;
}
std::string narrow(const std::wstring &text) {
    if (text.empty()) return {};
    int size = WideCharToMultiByte(CP_UTF8, 0, text.data(), int(text.size()), nullptr, 0, nullptr, nullptr);
    std::string out(size_t(size), '\0');
    WideCharToMultiByte(CP_UTF8, 0, text.data(), int(text.size()), out.data(), size, nullptr, nullptr);
    return out;
}
std::wstring environment(const wchar_t *name) {
    wchar_t *value = nullptr; size_t size = 0;
    if (_wdupenv_s(&value, &size, name) || !value) return {};
    std::wstring out(value); free(value);
    return out;
}
std::filesystem::path storePath() {
    if (auto test = environment(L"PHONECAM_WIFI_STORE"); !test.empty()) return test;  // tests never touch the real pairings
    auto appData = environment(L"APPDATA");
    if (appData.empty()) throw std::runtime_error("Could not find the AppData folder for Wi-Fi pairings");
    return std::filesystem::path(appData) / L"phonecam-obs" / L"wifi.json";
}

// One DNS-SD registration. Windows completes registration and removal asynchronously; the instance must
// stay alive until removal has completed.
struct Registration {
    DNS_SERVICE_INSTANCE *instance = nullptr;
    DNS_SERVICE_REGISTER_REQUEST request{};
    HANDLE done = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    // Windows' documentation is unclear whether `result` is a copy or our own instance, so it is not freed
    // here (at worst a few hundred bytes each time a pairing window opens or closes).
    static VOID WINAPI completed(DWORD, PVOID context, PDNS_SERVICE_INSTANCE) {
        SetEvent(static_cast<Registration *>(context)->done);
    }
    ~Registration() {
        if (instance) {
            if (DnsServiceDeRegister(&request, nullptr) == DNS_REQUEST_PENDING) WaitForSingleObject(done, 2000);
            DnsServiceFreeInstance(instance);
        }
        if (done) CloseHandle(done);
    }
};
std::mutex announcing;  // openPairing (UI thread) and the server thread both announce and withdraw
void drop(void *&handle) { delete static_cast<Registration *>(handle); handle = nullptr; }
}

Pairings loadPairings() {
    Pairings result;
    std::ifstream file(storePath(), std::ios::binary);
    if (file) {
        std::ostringstream text; text << file.rdbuf();
        if (text.str().size() < 1024 * 1024) readStore(text.str(), result);
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

void savePairings(const Pairings &value) {
    // The file sits in the user's own AppData, which only that user (and administrators) can read.
    const auto path = storePath();
    std::error_code ignored;
    std::filesystem::create_directories(path.parent_path(), ignored);
    auto temporary = path; temporary += L".tmp";
    {
        std::ofstream file(temporary, std::ios::binary | std::ios::trunc);
        file << writeStore(value);
        if (!file.flush()) throw std::runtime_error("Could not save Wi-Fi pairings");
    }
    if (!MoveFileExW(temporary.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH))
        throw std::runtime_error("Could not save Wi-Fi pairings");
}

std::string macName() {
    wchar_t name[256]; DWORD size = 256;
    if (GetComputerNameExW(ComputerNamePhysicalDnsHostname, name, &size) && size) return cleanName(narrow(std::wstring(name, size)));
    return "PC";
}

std::vector<std::string> localAddresses() {
    // Adapters with a default gateway first: that is the home or office network. Virtual adapters (Hyper-V,
    // WSL, VPNs without a gateway) come after, so the phone tries the real network first.
    std::vector<std::pair<bool, std::string>> found;
    ULONG size = 16 * 1024;
    std::vector<uint8_t> memory;
    ULONG status = ERROR_BUFFER_OVERFLOW;
    for (int attempt = 0; attempt < 3 && status == ERROR_BUFFER_OVERFLOW; ++attempt) {
        memory.resize(size);
        status = GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER | GAA_FLAG_INCLUDE_GATEWAYS,
                                      nullptr, reinterpret_cast<IP_ADAPTER_ADDRESSES *>(memory.data()), &size);
    }
    if (status != NO_ERROR) return {};
    for (auto *adapter = reinterpret_cast<IP_ADAPTER_ADDRESSES *>(memory.data()); adapter; adapter = adapter->Next) {
        if (adapter->OperStatus != IfOperStatusUp || adapter->IfType == IF_TYPE_SOFTWARE_LOOPBACK) continue;
        for (auto *unicast = adapter->FirstUnicastAddress; unicast; unicast = unicast->Next) {
            auto *address = reinterpret_cast<sockaddr_in *>(unicast->Address.lpSockaddr);
            if (address->sin_family != AF_INET) continue;
            uint32_t host = ntohl(address->sin_addr.s_addr);
            if ((host >> 16) == 0xA9FE || (host >> 24) == 127) continue;  // 169.254.x.x: no network
            char text[INET_ADDRSTRLEN];
            if (inet_ntop(AF_INET, &address->sin_addr, text, sizeof(text))) found.emplace_back(adapter->FirstGatewayAddress != nullptr, text);
        }
    }
    std::stable_sort(found.begin(), found.end(), [](const auto &a, const auto &b) { return a.first > b.first; });
    std::vector<std::string> out;
    for (const auto &entry : found) if (out.size() < 4) out.push_back(entry.second);
    return out;
}

void Server::announce() {
    if (!port) return;
    std::lock_guard<std::mutex> guard(announcing);
    drop(bonjour);  // DNS-SD on Windows cannot update a TXT record in place: register again
    const std::wstring id = wide(loadPairings().macId), host = wide(macName());
    const std::wstring instance = L"PhoneCam on " + host + L"._phonecam._tcp.local", hostName = host + L".local";
    std::vector<PCWSTR> keys{L"v", L"id"}, values{L"1", id.c_str()};
    if (pairingOpen()) { keys.push_back(L"pair"); values.push_back(L"1"); }
    IP4_ADDRESS address = 0;
    auto addresses = localAddresses();
    if (!addresses.empty()) inet_pton(AF_INET, addresses[0].c_str(), &address);
    auto registration = std::make_unique<Registration>();
    registration->instance = DnsServiceConstructInstance(instance.c_str(), hostName.c_str(), address ? &address : nullptr, nullptr,
                                                         WORD(int(port)), 0, 0, DWORD(keys.size()), keys.data(), values.data());
    if (!registration->instance) return;
    registration->request.Version = DNS_QUERY_REQUEST_VERSION1;
    registration->request.pServiceInstance = registration->instance;
    registration->request.pRegisterCompletionCallback = Registration::completed;
    registration->request.pQueryContext = registration.get();
    if (DnsServiceRegister(&registration->request, nullptr) != DNS_REQUEST_PENDING) {
        DnsServiceFreeInstance(registration->instance); registration->instance = nullptr;
        return;  // no announcement: the phone still finds this PC by the addresses in the QR code
    }
    WaitForSingleObject(registration->done, 2000);
    bonjour = registration.release();
}

void Server::withdraw() {
    std::lock_guard<std::mutex> guard(announcing);
    drop(bonjour);
}
}
