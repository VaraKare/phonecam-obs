// Windows half of the adb integration: finding adb.exe, starting its server when needed and running it
// without a shell or console window, passing only our output pipe to it (OBS's other handles never leak
// into the adb server).
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <windows.h>
#include <iphlpapi.h>
#include "adb.hpp"
#include <chrono>
#include <cstdlib>
#include <mutex>
#include <thread>

namespace phonecam {
namespace {
constexpr size_t maxOutput = 64 * 1024;
std::wstring wide(const std::string &text) {
    if (text.empty()) return {};
    int size = MultiByteToWideChar(CP_UTF8, 0, text.data(), int(text.size()), nullptr, 0);
    std::wstring out(size_t(size), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text.data(), int(text.size()), out.data(), size);
    return out;
}
bool executable(const std::string &path) {
    DWORD attributes = GetFileAttributesW(wide(path).c_str());
    return !path.empty() && attributes != INVALID_FILE_ATTRIBUTES && !(attributes & FILE_ATTRIBUTE_DIRECTORY);
}
std::string environment(const char *name) {
    char *value = nullptr; size_t size = 0;
    if (_dupenv_s(&value, &size, name) || !value) return {};
    std::string out(value); free(value);
    return out;
}
// Windows command-line quoting (CommandLineToArgvW rules).
std::wstring quote(const std::wstring &arg) {
    if (!arg.empty() && arg.find_first_of(L" \t\"") == std::wstring::npos) return arg;
    std::wstring out = L"\"";
    size_t slashes = 0;
    for (wchar_t c : arg) {
        if (c == L'\\') { ++slashes; continue; }
        if (c == L'"') out.append(slashes * 2 + 1, L'\\'); else out.append(slashes, L'\\');
        slashes = 0; out += c;
    }
    out.append(slashes * 2, L'\\');
    return out + L"\"";
}

// The adb server's port: ANDROID_ADB_SERVER_PORT when set, as adb itself does, else 5037.
int serverPort() {
    const std::string value = environment("ANDROID_ADB_SERVER_PORT");
    const int port = value.empty() ? 5037 : atoi(value.c_str());
    return port > 0 && port < 65536 ? port : 5037;
}
// Reads the TCP table rather than connecting: a refused connection to localhost takes about 2 s on Windows.
bool listening(int port) {
    ULONG size = 0;
    if (GetTcpTable(nullptr, &size, FALSE) != ERROR_INSUFFICIENT_BUFFER) return false;
    std::vector<uint8_t> memory(size);
    auto *table = reinterpret_cast<MIB_TCPTABLE *>(memory.data());
    if (GetTcpTable(table, &size, FALSE) != NO_ERROR) return false;
    for (DWORD i = 0; i < table->dwNumEntries; ++i)
        if (table->table[i].dwState == DWORD(MIB_TCP_STATE_LISTEN) && ntohs(u_short(table->table[i].dwLocalPort)) == port) return true;
    return false;
}

std::mutex serverLock;
HANDLE serverProcess = nullptr;
// Closing OBS closes this job handle, which ends the adb server we started.
HANDLE serverJob() {
    static HANDLE job = [] {
        HANDLE created = CreateJobObjectW(nullptr, nullptr);
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        if (created) SetInformationJobObject(created, JobObjectExtendedLimitInformation, &limits, sizeof(limits));
        return created;
    }();
    return job;
}
// Starts `adb -L tcp:PORT server nodaemon` with no window and all output discarded. noMdns adds
// ADB_MDNS=0 (network discovery off) for this process only.
bool startServer(const std::string &adb, int port, bool noMdns) {
    SECURITY_ATTRIBUTES inherit{sizeof(inherit), nullptr, TRUE};
    HANDLE nul = CreateFileW(L"NUL", GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, &inherit, OPEN_EXISTING, 0, nullptr);
    if (nul == INVALID_HANDLE_VALUE) return false;
    std::wstring command = quote(wide(adb)) + L" -L tcp:" + std::to_wstring(port) + L" server nodaemon";
    std::vector<wchar_t> environmentBlock;
    if (noMdns) {
        if (wchar_t *current = GetEnvironmentStringsW()) {
            const wchar_t *begin = current, *end = current;
            while (*end) end += wcslen(end) + 1;
            environmentBlock.assign(begin, end);
            FreeEnvironmentStringsW(current);
        }
        const std::wstring extra = L"ADB_MDNS=0";
        environmentBlock.insert(environmentBlock.end(), extra.begin(), extra.end());
        environmentBlock.push_back(L'\0'); environmentBlock.push_back(L'\0');
    }
    SIZE_T size = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &size);
    std::vector<uint8_t> attributeMemory(size);
    auto *attributes = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attributeMemory.data());
    bool listed = InitializeProcThreadAttributeList(attributes, 1, 0, &size) &&
        UpdateProcThreadAttribute(attributes, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, &nul, sizeof(nul), nullptr, nullptr);
    STARTUPINFOEXW startup{};
    startup.StartupInfo.cb = sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = nul; startup.StartupInfo.hStdOutput = nul; startup.StartupInfo.hStdError = nul;
    startup.lpAttributeList = listed ? attributes : nullptr;
    PROCESS_INFORMATION process{};
    BOOL started = listed && CreateProcessW(wide(adb).c_str(), command.data(), nullptr, nullptr, TRUE,
        CREATE_NO_WINDOW | CREATE_SUSPENDED | EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT,
        noMdns ? environmentBlock.data() : nullptr, nullptr, &startup.StartupInfo, &process);
    if (listed) DeleteProcThreadAttributeList(attributes);
    CloseHandle(nul);
    if (!started) return false;
    if (HANDLE job = serverJob()) AssignProcessToJobObject(job, process.hProcess);
    ResumeThread(process.hThread);
    CloseHandle(process.hThread);
    serverProcess = process.hProcess;
    return true;
}
bool serverRunning() { return serverProcess && WaitForSingleObject(serverProcess, 0) == WAIT_TIMEOUT; }
void stopServer() {
    if (!serverProcess) return;
    if (serverRunning()) { TerminateProcess(serverProcess, 1); WaitForSingleObject(serverProcess, 2000); }
    CloseHandle(serverProcess); serverProcess = nullptr;
}
}

void prepareAdb(const std::string &adb) {
    std::lock_guard<std::mutex> guard(serverLock);
    const int port = serverPort();
    if (listening(port)) return;
    if (serverRunning()) stopServer();  // ours, but not answering: start again
    // Network discovery is only needed for Wireless debugging; drop it if adb won't start with it.
    for (bool noMdns : {false, true}) {
        if (!startServer(adb, port, noMdns)) return;  // adb's own start will be tried (and timed out) instead
        for (int waited = 0; waited < 10000 && serverRunning(); waited += 100) {
            if (listening(port)) return;
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
        stopServer();
    }
}

std::string findAdb(const std::string &configured) {
    if (!configured.empty()) return executable(configured) ? configured : std::string();
    std::vector<std::string> candidates;
    if (auto sdk = environment("ANDROID_HOME"); !sdk.empty()) candidates.push_back(sdk + "\\platform-tools\\adb.exe");
    if (auto local = environment("LOCALAPPDATA"); !local.empty()) candidates.push_back(local + "\\Android\\Sdk\\platform-tools\\adb.exe");
    candidates.push_back("C:\\platform-tools\\adb.exe");
    if (auto path = environment("PATH"); !path.empty()) {
        size_t start = 0;
        while (start <= path.size()) {
            size_t end = path.find(';', start);
            if (end == std::string::npos) end = path.size();
            if (end > start) candidates.push_back(path.substr(start, end - start) + "\\adb.exe");
            start = end + 1;
        }
    }
    for (const auto &candidate : candidates) if (executable(candidate)) return candidate;
    return {};
}

AdbResult runAdb(const std::string &adb, const std::vector<std::string> &args, int timeoutMs) {
    AdbResult result;
    SECURITY_ATTRIBUTES inherit{sizeof(inherit), nullptr, TRUE};
    HANDLE readPipe = nullptr, writePipe = nullptr;
    if (!CreatePipe(&readPipe, &writePipe, &inherit, 0)) return result;
    SetHandleInformation(readPipe, HANDLE_FLAG_INHERIT, 0);
    HANDLE nul = CreateFileW(L"NUL", GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, &inherit, OPEN_EXISTING, 0, nullptr);
    std::wstring command = quote(wide(adb));
    for (const auto &arg : args) command += L" " + quote(wide(arg));

    SIZE_T size = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &size);
    std::vector<uint8_t> attributeMemory(size);
    auto *attributes = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attributeMemory.data());
    HANDLE inherited[2] = {writePipe, nul};
    bool listed = InitializeProcThreadAttributeList(attributes, 1, 0, &size) &&
        UpdateProcThreadAttribute(attributes, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherited, sizeof(inherited), nullptr, nullptr);
    STARTUPINFOEXW startup{};
    startup.StartupInfo.cb = sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = nul; startup.StartupInfo.hStdOutput = writePipe; startup.StartupInfo.hStdError = writePipe;
    startup.lpAttributeList = listed ? attributes : nullptr;
    PROCESS_INFORMATION process{};
    BOOL started = listed && CreateProcessW(wide(adb).c_str(), command.data(), nullptr, nullptr, TRUE,
        CREATE_NO_WINDOW | EXTENDED_STARTUPINFO_PRESENT, nullptr, nullptr, &startup.StartupInfo, &process);
    if (listed) DeleteProcThreadAttributeList(attributes);
    CloseHandle(writePipe);
    if (nul != INVALID_HANDLE_VALUE) CloseHandle(nul);
    if (!started) { CloseHandle(readPipe); return result; }

    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    char buffer[4096];
    auto drain = [&] {
        DWORD available = 0, count = 0;
        while (PeekNamedPipe(readPipe, nullptr, 0, nullptr, &available, nullptr) && available &&
               ReadFile(readPipe, buffer, std::min<DWORD>(available, DWORD(sizeof(buffer))), &count, nullptr) && count)
            if (result.output.size() < maxOutput) result.output.append(buffer, count);
    };
    while (true) {
        drain();
        // Wait for exit, not end of output: a freshly started adb server may keep the pipe open.
        if (WaitForSingleObject(process.hProcess, 50) == WAIT_OBJECT_0) {
            drain();
            DWORD code = 0; GetExitCodeProcess(process.hProcess, &code);
            result.status = int(code);
            break;
        }
        if (std::chrono::steady_clock::now() > deadline) {
            TerminateProcess(process.hProcess, 1); WaitForSingleObject(process.hProcess, 1000);
            result.status = -1; result.output = "ADB timed out";
            break;
        }
    }
    CloseHandle(process.hThread); CloseHandle(process.hProcess); CloseHandle(readPipe);
    return result;
}
}
