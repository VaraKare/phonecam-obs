#import <Foundation/Foundation.h>
#include <VideoToolbox/VideoToolbox.h>
#include "receiver.hpp"
#include "protocol.hpp"
#include <arpa/inet.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <chrono>
#include <cstring>
#include <memory>
#include <fstream>

namespace phonecam {
namespace {
std::atomic<void (*)(const char *)> logSink{nullptr};
void check(OSStatus status, const char *operation) {
    if (status) throw std::runtime_error(std::string(operation) + " failed (" + std::to_string(status) + ")");
}
struct Socket {
    int fd = -1;
    ~Socket() { if (fd >= 0) close(fd); }
};
void readExact(int fd, uint8_t *p, size_t size, std::atomic<bool> &stop, int seconds = 3) {
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
// Hardware/GPU rotation of decoded NV12 into a pooled upright buffer. Pool is rebuilt only on change.
class Rotator {
    VTPixelRotationSessionRef session = nullptr;
    CVPixelBufferPoolRef pool = nullptr;
    unsigned poolTurns = 0; bool poolMirror = false; size_t poolWidth = 0, poolHeight = 0;
public:
    Rotator() { check(VTPixelRotationSessionCreate(kCFAllocatorDefault, &session), "Rotation session"); }
    ~Rotator() {
        if (session) { VTPixelRotationSessionInvalidate(session); CFRelease(session); }
        if (pool) CVPixelBufferPoolRelease(pool);
    }
    Rotator(const Rotator &) = delete; Rotator &operator=(const Rotator &) = delete;
    // Returns a +1 retained buffer.
    // VideoToolbox applies the horizontal flip after the rotation, i.e. it mirrors the upright picture.
    CVPixelBufferRef rotate(CVPixelBufferRef image, unsigned turns, bool mirror) {
        size_t width = CVPixelBufferGetWidth(image), height = CVPixelBufferGetHeight(image);
        size_t outWidth = turns % 2 ? height : width, outHeight = turns % 2 ? width : height;
        if (!pool || turns != poolTurns || mirror != poolMirror || outWidth != poolWidth || outHeight != poolHeight) {
            if (pool) { CVPixelBufferPoolRelease(pool); pool = nullptr; }
            NSDictionary *attributes = @{(__bridge NSString *)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),
                                        (__bridge NSString *)kCVPixelBufferWidthKey: @(outWidth),
                                        (__bridge NSString *)kCVPixelBufferHeightKey: @(outHeight),
                                        (__bridge NSString *)kCVPixelBufferIOSurfacePropertiesKey: @{}};
            check(CVPixelBufferPoolCreate(kCFAllocatorDefault, nullptr, (__bridge CFDictionaryRef)attributes, &pool), "Rotation buffer pool");
            CFStringRef angle = turns == 0 ? kVTRotation_0 : turns == 1 ? kVTRotation_CW90 : turns == 2 ? kVTRotation_180 : kVTRotation_CCW90;
            check(VTSessionSetProperty(session, kVTPixelRotationPropertyKey_Rotation, angle), "Rotation angle");
            check(VTSessionSetProperty(session, kVTPixelRotationPropertyKey_FlipHorizontalOrientation, mirror ? kCFBooleanTrue : kCFBooleanFalse), "Mirror");
            poolTurns = turns; poolMirror = mirror; poolWidth = outWidth; poolHeight = outHeight;
        }
        CVPixelBufferRef out = nullptr;
        check(CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &out), "Rotation buffer");
        OSStatus status = VTPixelRotationSessionRotateImage(session, image, out);
        if (status) { CVPixelBufferRelease(out); check(status, "Picture rotation"); }
        return out;
    }
};
class Decoder {
    VTDecompressionSessionRef session = nullptr;
    std::unique_ptr<Rotator> rotator;
    unsigned turns = 0;
    bool mirror = false;
    CMVideoFormatDescriptionRef format = nullptr;
    uint32_t codec = 0, width = 0, height = 0;
    Output output;
    OSStatus outputStatus = noErr;
    bool delivered = false;
    uint64_t sequence = 0, pts = 0;
    static void decoded(void *ref, void *, OSStatus status, VTDecodeInfoFlags flags,
                        CVImageBufferRef image, CMTime, CMTime) {
        auto *self = static_cast<Decoder *>(ref);
        self->outputStatus = status;
        if (status || !image || (flags & kVTDecodeInfo_FrameDropped)) {
            if (!status) self->outputStatus = -1;
            return;
        }
        if (CVPixelBufferGetWidth(image) != self->width || CVPixelBufferGetHeight(image) != self->height ||
            CVPixelBufferGetPixelFormatType(image) != kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange ||
            CVPixelBufferGetPlaneCount(image) != 2) { self->outputStatus = -2; return; }
        self->delivered = true;
        // Callback may run on a VT thread. No exceptions may cross the C callback boundary.
        try {
            if (!self->turns && !self->mirror) self->output(image, self->sequence, self->pts);
            else {
                if (!self->rotator) self->rotator = std::make_unique<Rotator>();
                CVPixelBufferRef upright = self->rotator->rotate(image, self->turns, self->mirror);
                std::unique_ptr<std::remove_pointer_t<CVPixelBufferRef>, decltype(&CVPixelBufferRelease)> owner(upright, CVPixelBufferRelease);
                self->output(upright, self->sequence, self->pts);
            }
        }
        catch (...) { self->outputStatus = -3; }
    }
public:
    explicit Decoder(Output callback) : output(std::move(callback)) {}
    ~Decoder() {
        if (session) { VTDecompressionSessionWaitForAsynchronousFrames(session); VTDecompressionSessionInvalidate(session); CFRelease(session); }
        if (format) CFRelease(format);
    }
    void configure(const std::vector<uint8_t> &data) {
        if (format || data.size() < 20) throw std::runtime_error("Unexpected codec configuration");
        codec = be32(data.data()); width = be32(data.data() + 4); height = be32(data.data() + 8);
        if ((codec != 1 && codec != 2) || width < 128 || height < 128 || width > 4096 || height > 4096 ||
            width % 2 || height % 2)
            throw std::runtime_error("Unsupported codec/size; use a declared SDR profile");
        uint32_t rotation = be32(data.data() + 12);
        if (rotation % 90 || rotation >= 360) throw std::runtime_error("Invalid initial rotation");
        auto units = nals(data.data() + 16, data.size() - 16);
        std::vector<const uint8_t *> pointers;
        std::vector<size_t> sizes;
        unsigned types = 0;
        for (auto &n : units) {
            int type = codec == 1 ? n[0] & 31 : (n[0] >> 1) & 63;
            bool parameter = codec == 1 ? (type == 7 || type == 8) : (type >= 32 && type <= 34);
            if (parameter) {
                if (codec == 2 && n.size() < 2) throw std::runtime_error("Invalid HEVC parameter set");
                types |= 1u << (codec == 1 ? type - 7 : type - 32);
                pointers.push_back(n.data()); sizes.push_back(n.size());
            }
        }
        if (pointers.size() > 16 || types != (codec == 1 ? 3u : 7u)) throw std::runtime_error("Missing/excess codec parameter sets");
        if (codec == 1) check(CMVideoFormatDescriptionCreateFromH264ParameterSets(kCFAllocatorDefault, pointers.size(), pointers.data(), sizes.data(), 4, &format), "H.264 parameters");
        else check(CMVideoFormatDescriptionCreateFromHEVCParameterSets(kCFAllocatorDefault, pointers.size(), pointers.data(), sizes.data(), 4, nullptr, &format), "HEVC parameters");
        auto dimensions = CMVideoFormatDescriptionGetDimensions(format);
        if (dimensions.width != int32_t(width) || dimensions.height != int32_t(height))
            throw std::runtime_error("Encoded dimensions differ from the requested profile");
        NSDictionary *requirements = @{(__bridge NSString *)kVTVideoDecoderSpecification_RequireHardwareAcceleratedVideoDecoder: @YES};
        NSDictionary *attributes = @{(__bridge NSString *)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),
                                    (__bridge NSString *)kCVPixelBufferIOSurfacePropertiesKey: @{}};
        VTDecompressionOutputCallbackRecord record{decoded, this};
        check(VTDecompressionSessionCreate(kCFAllocatorDefault, format, (__bridge CFDictionaryRef)requirements,
              (__bridge CFDictionaryRef)attributes, &record, &session), "Hardware VideoToolbox decoder");
        CFTypeRef hardware = nullptr;
        check(VTSessionCopyProperty(session, kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder, kCFAllocatorDefault, &hardware), "Hardware decoder check");
        bool active = hardware && CFEqual(hardware, kCFBooleanTrue);
        if (hardware) CFRelease(hardware);
        if (!active) throw std::runtime_error("Hardware decoding is inactive; software fallback is disabled");
        check(VTSessionSetProperty(session, kVTDecompressionPropertyKey_RealTime, kCFBooleanTrue), "Realtime decoding");
    }
    bool frame(const std::vector<uint8_t> &data, const Header &h) {
        if (!session) throw std::runtime_error("Video before codec configuration");
        auto units = nals(data.data(), data.size());
        bool actualKey = false;
        for (const auto &n : units) {
            if (codec == 2 && n.size() < 2) throw std::runtime_error("Invalid HEVC NAL");
            int type = codec == 1 ? n[0] & 31 : (n[0] >> 1) & 63;
            actualKey |= codec == 1 ? type == 5 : (type >= 16 && type <= 21);
        }
        if (actualKey != bool(h.flags & 1)) throw std::runtime_error("Keyframe flag differs from encoded video");
        auto bytes = lengthPrefixed(units);
        CMBlockBufferRef block = nullptr;
        check(CMBlockBufferCreateWithMemoryBlock(kCFAllocatorDefault, nullptr, bytes.size(), kCFAllocatorDefault, nullptr, 0, bytes.size(), 0, &block), "Sample allocation");
        std::unique_ptr<std::remove_pointer_t<CMBlockBufferRef>, decltype(&CFRelease)> blockOwner(block, CFRelease);
        check(CMBlockBufferReplaceDataBytes(bytes.data(), block, 0, bytes.size()), "Sample copy");
        size_t size = bytes.size();
        CMSampleBufferRef sample = nullptr;
        CMSampleTimingInfo timing{kCMTimeInvalid, CMTimeMake(int64_t(h.pts), 1000000), kCMTimeInvalid};
        check(CMSampleBufferCreateReady(kCFAllocatorDefault, block, format, 1, 1, &timing, 1, &size, &sample), "Sample creation");
        std::unique_ptr<std::remove_pointer_t<CMSampleBufferRef>, decltype(&CFRelease)> sampleOwner(sample, CFRelease);
        sequence = h.sequence; pts = h.pts; turns = (h.flags >> 1) & 3; mirror = (h.flags >> 3) & 1; outputStatus = noErr; delivered = false;
        check(VTDecompressionSessionDecodeFrame(session, sample, 0, nullptr, nullptr), "Hardware decoding");
        check(VTDecompressionSessionWaitForAsynchronousFrames(session), "Decoder completion");
        check(outputStatus, "Decoded output");
        if (!delivered) throw std::runtime_error("Decoder buffered a frame; use a no-B-frame profile");
        return true;
    }
};
}
std::string defaultConfigPath() {
    return std::string([NSHomeDirectory() UTF8String]) + "/Library/Application Support/phonecam-obs/live.json";
}
Endpoint loadEndpoint(const std::string &configPath) {
    @autoreleasepool {
        std::ifstream settings(configPath, std::ios::binary);
        char contents[4097]; settings.read(contents, sizeof(contents));
        auto count = settings.gcount();
        if (count <= 0 || count > 4096) throw std::runtime_error("Run tools/live.py prepare to create bounded USB connection settings");
        NSData *data = [NSData dataWithBytes:contents length:size_t(count)];
        id value = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
        if (![value isKindOfClass:[NSDictionary class]]) throw std::runtime_error("Invalid USB connection settings");
        id portObject = value[@"port"], tokenObject = value[@"token"];
        if (![portObject isKindOfClass:[NSNumber class]] || ![tokenObject isKindOfClass:[NSString class]])
            throw std::runtime_error("Invalid USB port/token");
        int port = [portObject intValue];
        std::string token = [tokenObject UTF8String];
        if (port < 1024 || port > 65535 || token.size() != 64 || token.find_first_not_of("0123456789abcdef") != std::string::npos)
            throw std::runtime_error("Invalid USB port/token");
        return {port, token};
    }
}
void decodeStream(std::atomic<bool> &stop, const Reader &read, const Output &output) {
    @autoreleasepool {
        Decoder decoder(output);
        decodeMessages(stop, read, decoder, logSink.load(), "Mac");
    }
}
void setLog(void (*sink)(const char *)) { logSink = sink; }
void receive(std::atomic<bool> &stop, const Endpoint &endpoint, const Output &output) {
    @autoreleasepool {
        int port = endpoint.port;
        std::string token = endpoint.token;
        if (port < 1 || port > 65535 || token.size() != 64 || token.find_first_not_of("0123456789abcdef") != std::string::npos)
            throw std::runtime_error("Invalid USB port/token");
        Socket socket; socket.fd = ::socket(AF_INET, SOCK_STREAM, 0);
        if (socket.fd < 0) throw std::runtime_error("Cannot allocate USB socket");
        int noSigpipe = 1; setsockopt(socket.fd, SOL_SOCKET, SO_NOSIGPIPE, &noSigpipe, sizeof(noSigpipe));
        timeval timeout{1, 0}; setsockopt(socket.fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
        sockaddr_in address{}; address.sin_family = AF_INET; address.sin_port = htons(uint16_t(port)); address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (connect(socket.fd, reinterpret_cast<sockaddr *>(&address), sizeof(address))) throw ConnectionRefused("No USB link to the phone");
        token += '\n';
        if (send(socket.fd, token.data(), token.size(), 0) != ssize_t(token.size())) throw std::runtime_error("USB authentication write failed");
        decodeStream(stop, [&](uint8_t *p, size_t n, int seconds) { readExact(socket.fd, p, n, stop, seconds); }, output);
    }
}
}
