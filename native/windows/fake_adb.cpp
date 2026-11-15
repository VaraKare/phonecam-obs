// Stand-in for adb.exe in the Windows CI: runs a "server" that only listens, lists one phone, returns its
// pairing key and "forwards" to the test's local server port. Every call is appended to FAKE_ADB_LOG.
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <windows.h>
#include <cstdio>
#include <cstdlib>
#include <string>
int main(int argc, char **argv) {
    std::string args;
    for (int i = 1; i < argc; ++i) args += (i > 1 ? " " : "") + std::string(argv[i]);
    char *log = nullptr, *token = nullptr, *port = nullptr; size_t size = 0;
    _dupenv_s(&log, &size, "FAKE_ADB_LOG"); _dupenv_s(&token, &size, "FAKE_ADB_TOKEN"); _dupenv_s(&port, &size, "FAKE_ADB_PORT");
    if (log) { FILE *f = nullptr; if (!fopen_s(&f, log, "a") && f) { fprintf(f, "%s\n", args.c_str()); fclose(f); } }
    if (args.rfind("-L tcp:", 0) == 0 && args.find(" server nodaemon") != std::string::npos) {
        // Listen like adb's server until the plugin's job object ends this process.
        WSADATA data; WSAStartup(MAKEWORD(2, 2), &data);
        SOCKET server = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        sockaddr_in address{}; address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        address.sin_port = htons(u_short(atoi(args.c_str() + 7)));
        if (bind(server, reinterpret_cast<sockaddr *>(&address), sizeof(address)) || listen(server, 4)) return 1;
        for (;;) Sleep(1000);
    }
    if (args == "devices") printf("List of devices attached\nFAKE1\tdevice\n");
    else if (args.find("content query") != std::string::npos && token) printf("Row: 0 token=%s\n", token);
    else if (args.find("forward tcp:0") != std::string::npos && port) printf("%s\n", port);
    free(log); free(token); free(port);
    return 0;
}
