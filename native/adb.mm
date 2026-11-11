#import <Foundation/Foundation.h>
#include "adb.hpp"
#include <chrono>
#include <csignal>
#include <fcntl.h>
#include <poll.h>
#include <spawn.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>

extern char **environ;

namespace phonecam {
namespace {
constexpr size_t maxOutput = 64 * 1024;
bool executable(const std::string &path) {
    struct stat info{};
    return !path.empty() && stat(path.c_str(), &info) == 0 && S_ISREG(info.st_mode) && access(path.c_str(), X_OK) == 0;
}
}

std::string findAdb(const std::string &configured) {
    if (!configured.empty()) return executable(configured) ? configured : std::string();
    std::vector<std::string> candidates;
    if (const char *sdk = getenv("ANDROID_HOME")) candidates.push_back(std::string(sdk) + "/platform-tools/adb");
    if (const char *home = getenv("HOME")) candidates.push_back(std::string(home) + "/Library/Android/sdk/platform-tools/adb");
    candidates.push_back("/opt/homebrew/bin/adb");
    candidates.push_back("/usr/local/bin/adb");
    // OBS opened from Finder gets a minimal PATH, so the fixed locations above come first.
    if (const char *path = getenv("PATH")) {
        std::string value = path; size_t start = 0;
        while (start <= value.size()) {
            size_t end = value.find(':', start);
            if (end == std::string::npos) end = value.size();
            if (end > start) candidates.push_back(value.substr(start, end - start) + "/adb");
            start = end + 1;
        }
    }
    for (const auto &candidate : candidates) if (executable(candidate)) return candidate;
    return {};
}

AdbResult runAdb(const std::string &adb, const std::vector<std::string> &args, int timeoutMs) {
    int pipeFds[2];
    if (pipe(pipeFds)) return {};
    posix_spawn_file_actions_t actions; posix_spawn_file_actions_init(&actions);
    posix_spawn_file_actions_addopen(&actions, 0, "/dev/null", O_RDONLY, 0);
    posix_spawn_file_actions_adddup2(&actions, pipeFds[1], 1);
    posix_spawn_file_actions_adddup2(&actions, pipeFds[1], 2);
    posix_spawnattr_t attributes; posix_spawnattr_init(&attributes);
    // macOS: close every other descriptor, so OBS files/sockets never leak into the ADB server.
    posix_spawnattr_setflags(&attributes, POSIX_SPAWN_CLOEXEC_DEFAULT);
    std::vector<char *> argv{const_cast<char *>(adb.c_str())};
    for (const auto &arg : args) argv.push_back(const_cast<char *>(arg.c_str()));
    argv.push_back(nullptr);
    pid_t pid = 0;
    int spawned = posix_spawn(&pid, adb.c_str(), &actions, &attributes, argv.data(), environ);
    posix_spawn_file_actions_destroy(&actions); posix_spawnattr_destroy(&attributes);
    close(pipeFds[1]);
    AdbResult result;
    if (spawned) { close(pipeFds[0]); return result; }
    fcntl(pipeFds[0], F_SETFL, fcntl(pipeFds[0], F_GETFL) | O_NONBLOCK);
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    char buffer[4096];
    auto drain = [&] {
        ssize_t count;
        while ((count = read(pipeFds[0], buffer, sizeof(buffer))) > 0)
            if (result.output.size() < maxOutput) result.output.append(buffer, size_t(count));
    };
    int waitStatus = 0;
    while (true) {
        pollfd event{pipeFds[0], POLLIN, 0};
        poll(&event, 1, 50);
        drain();
        // Wait for exit, not EOF: a freshly started ADB server daemon may inherit the pipe.
        pid_t done = waitpid(pid, &waitStatus, WNOHANG);
        if (done == pid) { drain(); result.status = WIFEXITED(waitStatus) ? WEXITSTATUS(waitStatus) : -1; break; }
        if (done < 0) break;
        if (std::chrono::steady_clock::now() > deadline) {
            kill(pid, SIGKILL); waitpid(pid, &waitStatus, 0);
            result.status = -1; result.output = "ADB timed out";
            break;
        }
    }
    close(pipeFds[0]);
    return result;
}

}
