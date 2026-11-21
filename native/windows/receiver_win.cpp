// Windows receiver: Winsock connection to the adb-forwarded port, FFmpeg decoding (D3D11VA hardware with
// software fallback, OBS's own pinned FFmpeg), CPU rotation and mirror split across threads, then the shared
// PCAM loop. When frames run late it logs where the time went, once a second.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <ws2tcpip.h>
#include "receiver.hpp"
#include "rotate.hpp"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <future>
#include <mutex>
#include <string>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/hwcontext.h>
}

namespace phonecam {
namespace {
std::atomic<LogSink> logSink{nullptr};
std::mutex nameLock;
std::string lastDecoder = "hardware decoding";

void startWinsock() {
    static std::once_flag once;
    std::call_once(once, [] { WSADATA data; WSAStartup(MAKEWORD(2, 2), &data); });
}
struct Socket {
    SOCKET fd = INVALID_SOCKET;
    ~Socket() { if (fd != INVALID_SOCKET) closesocket(fd); }
};
void readExact(SOCKET fd, uint8_t *p, size_t size, std::atomic<bool> &stop, int seconds) {
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    while (size) {
        if (stop) throw std::runtime_error("Stopped");
        if (std::chrono::steady_clock::now() > deadline) throw std::runtime_error("video stalled for " + std::to_string(seconds) + " seconds");
        fd_set readable; FD_ZERO(&readable); FD_SET(fd, &readable);
        timeval wait{0, 50000};
        int ready = select(0, &readable, nullptr, nullptr, &wait);
        if (ready == SOCKET_ERROR) throw std::runtime_error("connection poll failed");
        if (!ready) continue;
        int count = recv(fd, reinterpret_cast<char *>(p), int(std::min<size_t>(size, 1 << 20)), 0);
        if (count <= 0) throw Closed("connection closed");
        size -= size_t(count); p += count;
    }
}
std::string averror(int code) { char text[AV_ERROR_MAX_STRING_SIZE] = {}; av_strerror(code, text, sizeof(text)); return text; }

class Decoder {
    Output output;
    AVCodecContext *context = nullptr;
    AVBufferRef *device = nullptr;
    AVPacket *packet = av_packet_alloc();
    AVFrame *decoded = av_frame_alloc(), *system = av_frame_alloc();
    uint32_t codec = 0, width = 0, height = 0;
    std::vector<uint8_t> parameters, rotated[3];
    bool hardware = false, delivered = false;
    uint64_t sequence = 0, pts = 0;
    // Per-second timing of each step, logged only when some frame took longer than a 30 fps frame.
    using Clock = std::chrono::steady_clock;
    struct Timing { double decode = 0, copy = 0, rotate = 0, hand = 0; unsigned frames = 0, slow = 0; Clock::time_point start = Clock::now(); } timing;
    Clock::time_point sent;
    static double ms(Clock::time_point a, Clock::time_point b) { return std::chrono::duration<double, std::milli>(b - a).count(); }
    void account(double decode, double copy, double rotate, double hand) {
        timing.decode += decode; timing.copy += copy; timing.rotate += rotate; timing.hand += hand; ++timing.frames;
        if (decode + copy + rotate + hand > 33.3) ++timing.slow;
        if (Clock::now() - timing.start < std::chrono::seconds(1)) return;
        if (timing.slow) if (auto sink = logSink.load()) {
            const double n = timing.frames;
            char line[200];
            snprintf(line, sizeof(line), "PC frame timing (average of %u): decode %.1f ms, copy from GPU %.1f ms, rotate %.1f ms, hand to OBS %.1f ms",
                     timing.frames, timing.decode / n, timing.copy / n, timing.rotate / n, timing.hand / n);
            sink(line);
        }
        timing = Timing{};
    }

    static AVPixelFormat pickFormat(AVCodecContext *, const AVPixelFormat *formats) {
        for (const AVPixelFormat *f = formats; *f != AV_PIX_FMT_NONE; ++f) if (*f == AV_PIX_FMT_D3D11) return *f;
        for (const AVPixelFormat *f = formats; *f != AV_PIX_FMT_NONE; ++f)
            if (*f == AV_PIX_FMT_YUV420P || *f == AV_PIX_FMT_YUVJ420P || *f == AV_PIX_FMT_NV12) return *f;
        return AV_PIX_FMT_NONE;  // 10-bit or other formats: refuse rather than show wrong colours
    }
    void open(bool tryHardware) {
        if (context) avcodec_free_context(&context);
        if (device) av_buffer_unref(&device);
        const AVCodec *decoder = avcodec_find_decoder(codec == 1 ? AV_CODEC_ID_H264 : AV_CODEC_ID_HEVC);
        if (!decoder) throw std::runtime_error("This FFmpeg has no H.264/H.265 decoder");
        context = avcodec_alloc_context3(decoder);
        context->flags |= AV_CODEC_FLAG_LOW_DELAY;
        context->thread_type = FF_THREAD_SLICE;  // frame threads would add a frame of delay each
        context->thread_count = 0;
        context->extradata = static_cast<uint8_t *>(av_mallocz(parameters.size() + AV_INPUT_BUFFER_PADDING_SIZE));
        memcpy(context->extradata, parameters.data(), parameters.size());
        context->extradata_size = int(parameters.size());
        hardware = tryHardware && av_hwdevice_ctx_create(&device, AV_HWDEVICE_TYPE_D3D11VA, nullptr, nullptr, 0) == 0;
        if (hardware) { context->hw_device_ctx = av_buffer_ref(device); context->get_format = pickFormat; }
        int status = avcodec_open2(context, decoder, nullptr);
        if (status < 0) throw std::runtime_error("Decoder setup failed: " + averror(status));
    }
    // Returns the rotation time; the hand-over to OBS is timed by the caller.
    double deliver(AVFrame *frame, unsigned turns, bool mirror) {
        if (uint32_t(frame->width) != width || uint32_t(frame->height) != height)
            throw std::runtime_error("Encoded dimensions differ from the requested profile");
        const bool nv12 = frame->format == AV_PIX_FMT_NV12;
        if (!nv12 && frame->format != AV_PIX_FMT_YUV420P && frame->format != AV_PIX_FMT_YUVJ420P)
            throw std::runtime_error("Unsupported decoded picture format (only 8-bit 4:2:0 video is supported)");
        Picture picture; picture.nv12 = nv12;
        const int planes = nv12 ? 2 : 3;
        const auto began = Clock::now();
        if (!turns && !mirror) {
            picture.width = width; picture.height = height;
            for (int i = 0; i < planes; ++i) { picture.plane[i] = frame->data[i]; picture.stride[i] = uint32_t(frame->linesize[i]); }
        } else {
            picture.width = (turns & 1) ? height : width; picture.height = (turns & 1) ? width : height;
            // Luma in two halves plus each chroma plane: three or four similar-sized pieces of work.
            struct Piece { int plane, first, last; };
            std::vector<Piece> pieces;
            for (int i = 0; i < planes; ++i) {
                const int w = i ? int(width / 2) : int(width), h = i ? int(height / 2) : int(height);
                const int element = (nv12 && i == 1) ? 2 : 1;
                const int outStride = ((turns & 1) ? h : w) * element, outRows = (turns & 1) ? w : h;
                rotated[i].resize(size_t(outStride) * outRows);
                picture.plane[i] = rotated[i].data(); picture.stride[i] = uint32_t(outStride);
                if (i == 0) { pieces.push_back({0, 0, outRows / 2}); pieces.push_back({0, outRows / 2, outRows}); }
                else pieces.push_back({i, 0, outRows});
            }
            auto rotate = [&](const Piece &piece) {
                const int i = piece.plane;
                const int w = i ? int(width / 2) : int(width), h = i ? int(height / 2) : int(height);
                if (nv12 && i == 1) rotatePlane<2>(frame->data[i], frame->linesize[i], w, h, rotated[i].data(), int(picture.stride[i]), int(turns), mirror, piece.first, piece.last);
                else rotatePlane<1>(frame->data[i], frame->linesize[i], w, h, rotated[i].data(), int(picture.stride[i]), int(turns), mirror, piece.first, piece.last);
            };
            std::vector<std::future<void>> others;
            for (size_t k = 1; k < pieces.size(); ++k) others.push_back(std::async(std::launch::async, rotate, pieces[k]));
            rotate(pieces[0]);
            for (auto &other : others) other.get();
        }
        const double rotateMs = ms(began, Clock::now());
        delivered = true;
        output(&picture, sequence, pts);
        return rotateMs;
    }
    void drain(unsigned turns, bool mirror) {
        while (true) {
            int status = avcodec_receive_frame(context, decoded);
            if (status == AVERROR(EAGAIN) || status == AVERROR_EOF) return;
            if (status < 0) throw std::runtime_error("Decoding failed: " + averror(status));
            if (!delivered) {  // report what actually decodes, not what was requested
                std::lock_guard<std::mutex> guard(nameLock);
                lastDecoder = decoded->format == AV_PIX_FMT_D3D11 ? "hardware decoding (D3D11)" : "software decoding";
            }
            const auto decodedAt = Clock::now();
            double rotateMs;
            auto copiedAt = decodedAt;
            if (decoded->format == AV_PIX_FMT_D3D11) {
                // Copy to NV12 in memory, reusing one buffer instead of allocating 12 MB per 4K frame.
                const auto *frames = reinterpret_cast<const AVHWFramesContext *>(decoded->hw_frames_ctx->data);
                if (!system->buf[0] || system->width != decoded->width || system->height != decoded->height || system->format != frames->sw_format) {
                    av_frame_unref(system);
                    system->format = frames->sw_format; system->width = decoded->width; system->height = decoded->height;
                    if (av_frame_get_buffer(system, 0) < 0) throw std::runtime_error("Out of memory");
                }
                status = av_hwframe_transfer_data(system, decoded, 0);
                if (status < 0) throw std::runtime_error("Hardware frame copy failed: " + averror(status));
                copiedAt = Clock::now();
                rotateMs = deliver(system, turns, mirror);
            } else rotateMs = deliver(decoded, turns, mirror);
            const double total = ms(copiedAt, Clock::now());
            account(ms(sent, decodedAt), ms(decodedAt, copiedAt), rotateMs, total - rotateMs);
            av_frame_unref(decoded);
        }
    }
    void send(const std::vector<uint8_t> &data, unsigned turns, bool mirror) {
        av_packet_unref(packet);
        if (av_new_packet(packet, int(data.size())) < 0) throw std::runtime_error("Out of memory");
        memcpy(packet->data, data.data(), data.size());
        sent = Clock::now();
        int status = avcodec_send_packet(context, packet);
        if (status < 0) throw std::runtime_error("Decoding failed: " + averror(status));
        drain(turns, mirror);
    }
public:
    explicit Decoder(Output callback) : output(std::move(callback)) {}
    ~Decoder() {
        av_frame_free(&decoded); av_frame_free(&system); av_packet_free(&packet);
        if (context) avcodec_free_context(&context);
        if (device) av_buffer_unref(&device);
    }
    void configure(const std::vector<uint8_t> &data) {
        // A second configuration (phone switched camera or quality) reopens the decoder below; open() frees the old one.
        if (data.size() < 20) throw std::runtime_error("Unexpected codec configuration");
        delivered = false;
        codec = be32(data.data()); width = be32(data.data() + 4); height = be32(data.data() + 8);
        if ((codec != 1 && codec != 2) || width < 128 || height < 128 || width > 4096 || height > 4096 || width % 2 || height % 2)
            throw std::runtime_error("Unsupported codec/size; use a declared SDR profile");
        uint32_t rotation = be32(data.data() + 12);
        if (rotation % 90 || rotation >= 360) throw std::runtime_error("Invalid initial rotation");
        unsigned types = 0;
        for (auto &n : nals(data.data() + 16, data.size() - 16)) {
            int type = codec == 1 ? n[0] & 31 : (n[0] >> 1) & 63;
            if (codec == 1 ? (type == 7 || type == 8) : (type >= 32 && type <= 34)) types |= 1u << (codec == 1 ? type - 7 : type - 32);
        }
        if (types != (codec == 1 ? 3u : 7u)) throw std::runtime_error("Missing codec parameter sets");
        parameters.assign(data.begin() + 16, data.end());
        open(true);
    }
    void frame(const std::vector<uint8_t> &data, const Header &h) {
        if (!context) throw std::runtime_error("Video before codec configuration");
        bool actualKey = false;
        for (const auto &n : nals(data.data(), data.size())) {
            int type = codec == 1 ? n[0] & 31 : (n[0] >> 1) & 63;
            actualKey |= codec == 1 ? type == 5 : (type >= 16 && type <= 21);
        }
        if (actualKey != bool(h.flags & 1)) throw std::runtime_error("Keyframe flag differs from encoded video");
        sequence = h.sequence; pts = h.pts;
        const unsigned turns = (h.flags >> 1) & 3; const bool mirror = (h.flags >> 3) & 1;
        try { send(data, turns, mirror); }
        catch (const std::runtime_error &) {
            // Hardware decoding can fail on machines without a video decoder (or in virtual machines);
            // before any picture was shown, reopen in software and retry this keyframe once.
            if (!hardware || delivered || !actualKey) throw;
            open(false);
            send(data, turns, mirror);
        }
    }
};
}

void decodeStream(std::atomic<bool> &stop, const Reader &read, const Output &output, Channel *channel) {
    Decoder decoder(output);
    decodeMessages(stop, read, decoder, logSink.load(), "PC", channel);
}

void receive(std::atomic<bool> &stop, const Endpoint &endpoint, const Output &output, Channel *channel) {
    startWinsock();
    if (endpoint.port < 1 || endpoint.port > 65535 || endpoint.token.size() != 64 ||
        endpoint.token.find_first_not_of("0123456789abcdef") != std::string::npos) throw std::runtime_error("Invalid USB port/token");
    Socket socket; socket.fd = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (socket.fd == INVALID_SOCKET) throw std::runtime_error("Cannot allocate USB socket");
    sockaddr_in address{}; address.sin_family = AF_INET; address.sin_port = htons(uint16_t(endpoint.port));
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    // Bounded connect: a refused local port takes about 2 s on Windows, and a stuck adb must not hold this
    // thread. A link that does not answer within 3 s counts as gone, so the phone is found again.
    u_long nonBlocking = 1;
    ioctlsocket(socket.fd, FIONBIO, &nonBlocking);
    if (connect(socket.fd, reinterpret_cast<sockaddr *>(&address), sizeof(address)) == SOCKET_ERROR) {
        if (WSAGetLastError() != WSAEWOULDBLOCK) throw ConnectionRefused("No USB link to the phone");
        fd_set writable, failed; FD_ZERO(&writable); FD_ZERO(&failed); FD_SET(socket.fd, &writable); FD_SET(socket.fd, &failed);
        timeval wait{3, 0};
        if (select(0, nullptr, &writable, &failed, &wait) != 1 || !FD_ISSET(socket.fd, &writable)) throw ConnectionRefused("No USB link to the phone");
        int error = 0, length = int(sizeof(error));
        if (getsockopt(socket.fd, SOL_SOCKET, SO_ERROR, reinterpret_cast<char *>(&error), &length) || error) throw ConnectionRefused("No USB link to the phone");
    }
    nonBlocking = 0;
    ioctlsocket(socket.fd, FIONBIO, &nonBlocking);
    DWORD sendLimit = 2000;
    setsockopt(socket.fd, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char *>(&sendLimit), sizeof(sendLimit));
    std::string token = endpoint.token + "\n";
    if (send(socket.fd, token.data(), int(token.size()), 0) != int(token.size())) throw std::runtime_error("USB authentication write failed");
    SOCKET fd = socket.fd;
    auto write = [fd](const std::vector<uint8_t> &bytes) {
        size_t sent = 0;
        while (sent < bytes.size()) {
            int n = ::send(fd, reinterpret_cast<const char *>(bytes.data() + sent), int(bytes.size() - sent), 0);
            if (n <= 0) throw std::runtime_error("USB write failed");
            sent += size_t(n);
        }
    };
    // ADB accepts locally even when the phone isn't listening; then this write lands in a closing link.
    if (channel) { try { write(control(pluginHello)); } catch (...) { throw NotStreaming("Waiting for the record button on the phone"); } }
    Attached attached(channel, write);
    decodeStream(stop, [&](uint8_t *p, size_t n, int seconds) { readExact(socket.fd, p, n, stop, seconds); }, output, channel);
}

void setLog(void (*sink)(const char *)) { logSink = sink; }
std::string decoderName() { std::lock_guard<std::mutex> guard(nameLock); return lastDecoder; }
}
