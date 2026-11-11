// Windows half of the adb integration: finding adb.exe and running it without a shell or console window,
// passing only our output pipe to it (OBS's other handles never leak into the adb server).
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include "adb.hpp"
#include <chrono>
#include <cstdlib>

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
