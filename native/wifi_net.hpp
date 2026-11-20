#pragma once
// The few socket calls the direct Wi-Fi server needs, for POSIX (macOS) and Winsock (Windows).
#include <cstddef>
#include <cstdint>
#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#include <mutex>
#else
#include <arpa/inet.h>
#include <cerrno>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#endif

namespace phonecam::wifi::net {
constexpr size_t chunk = 1 << 20;
#ifdef _WIN32
using Fd = SOCKET;
using Length = int;
constexpr Fd invalid = INVALID_SOCKET;
inline void startup() { static std::once_flag once; std::call_once(once, [] { WSADATA data; WSAStartup(MAKEWORD(2, 2), &data); }); }
inline int pollRead(Fd fd, int milliseconds) { WSAPOLLFD event{fd, POLLRDNORM, 0}; return WSAPoll(&event, 1, milliseconds); }
inline bool interrupted() { return false; }
inline void closeFd(Fd fd) { closesocket(fd); }
inline long receive(Fd fd, uint8_t *p, size_t n) { return recv(fd, reinterpret_cast<char *>(p), int(n < chunk ? n : chunk), 0); }
inline long transmit(Fd fd, const uint8_t *p, size_t n) { return send(fd, reinterpret_cast<const char *>(p), int(n < chunk ? n : chunk), 0); }
// Windows' SO_REUSEADDR would let another program take the port; ask for it exclusively instead.
inline void prepareListener(Fd fd) { BOOL yes = TRUE; setsockopt(fd, SOL_SOCKET, SO_EXCLUSIVEADDRUSE, reinterpret_cast<const char *>(&yes), sizeof(yes)); }
inline void prepareConnection(Fd) {}  // send() never raises SIGPIPE on Windows
#else
using Fd = int;
using Length = socklen_t;
constexpr Fd invalid = -1;
inline void startup() {}
inline int pollRead(Fd fd, int milliseconds) { pollfd event{fd, POLLIN, 0}; return poll(&event, 1, milliseconds); }
inline bool interrupted() { return errno == EINTR; }
inline void closeFd(Fd fd) { close(fd); }
inline long receive(Fd fd, uint8_t *p, size_t n) { return long(recv(fd, p, n < chunk ? n : chunk, 0)); }
inline long transmit(Fd fd, const uint8_t *p, size_t n) { return long(send(fd, p, n < chunk ? n : chunk, 0)); }
inline void prepareListener(Fd fd) { int yes = 1; setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes)); }
inline void prepareConnection(Fd fd) { int on = 1; setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, sizeof(on)); }
#endif
}
