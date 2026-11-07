#include <obs-module.h>
#include <util/platform.h>
#include "receiver.hpp"
#include <thread>
#include <mutex>
#include <chrono>

OBS_DECLARE_MODULE()
MODULE_EXPORT const char *obs_module_description(void) { return "Experimental Android USB camera with required hardware decode"; }

namespace {
struct Source {
    obs_source_t *source;
    std::atomic<bool> stop{false};
    std::thread worker;
    explicit Source(obs_source_t *value) : source(value) {}
    void start() {
        if (worker.joinable()) return;
        stop = false;
        worker = std::thread([this] {
            std::string lastError;
            while (!stop) {
                try {
                    uint64_t count = 0;
                    phonecam::receive(stop, phonecam::defaultConfigPath(), [this, &count, &lastError](CVPixelBufferRef image, uint64_t, uint64_t) {
                        if (stop) return;
                        if (CVPixelBufferLockBaseAddress(image, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess) return;
                        struct obs_source_frame frame{};
                        frame.format = VIDEO_FORMAT_NV12;
                        frame.width = uint32_t(CVPixelBufferGetWidth(image)); frame.height = uint32_t(CVPixelBufferGetHeight(image));
                        for (size_t i = 0; i < 2; ++i) {
                            frame.data[i] = static_cast<uint8_t *>(CVPixelBufferGetBaseAddressOfPlane(image, i));
                            frame.linesize[i] = uint32_t(CVPixelBufferGetBytesPerRowOfPlane(image, i));
                        }
                        // Arrival-based OBS clock. Sender PTS is carried for diagnostics, not subtracted across clocks.
                        frame.timestamp = os_gettime_ns(); frame.full_range = false;
                        video_format_get_parameters(VIDEO_CS_709, VIDEO_RANGE_PARTIAL, frame.color_matrix, frame.color_range_min, frame.color_range_max);
                        obs_source_output_video(source, &frame);
                        CVPixelBufferUnlockBaseAddress(image, kCVPixelBufferLock_ReadOnly);
                        if (++count == 1) {
                            lastError.clear();
                            blog(LOG_INFO, "[phonecam] USB video connected: %ux%u NV12; VideoToolbox hardware decode confirmed. Quality/optical latency unverified.", frame.width, frame.height);
                        }
                    });
                } catch (const std::exception &error) {
                    if (!stop && lastError != error.what()) {
                        lastError = error.what(); blog(LOG_WARNING, "[phonecam] %s", error.what());
                    }
                }
                obs_source_output_video(source, nullptr);
                for (int i = 0; i < 10 && !stop; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
        });
    }
    void end() { stop = true; if (worker.joinable()) worker.join(); }
    ~Source() { end(); }
};
const char *name(void *) { return "PhoneCam USB (Research)"; }
void *create(obs_data_t *, obs_source_t *source) {
    obs_source_set_async_unbuffered(source, true);
    return new Source(source);
}
void destroy(void *value) { delete static_cast<Source *>(value); }
void show(void *value) { static_cast<Source *>(value)->start(); }
void hide(void *value) { static_cast<Source *>(value)->end(); }
obs_properties_t *properties(void *) {
    auto *p = obs_properties_create();
    obs_properties_add_text(p, "instructions", "Run tools/live.py prepare, then tap Start live on the phone. Any orientation; the picture turns upright automatically (portrait changes this source to a tall shape). SDR video only, no audio. Quality and optical latency unverified.", OBS_TEXT_INFO);
    return p;
}
}
MODULE_EXPORT bool obs_module_load(void) {
    obs_source_info info{};
    info.id = "phonecam_usb_v1"; info.type = OBS_SOURCE_TYPE_INPUT;
    info.output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_DO_NOT_DUPLICATE;
    info.get_name = name; info.create = create; info.destroy = destroy;
    info.show = show; info.hide = hide; info.get_properties = properties;
    info.icon_type = OBS_ICON_TYPE_CAMERA;
    obs_register_source(&info);
    return true;
}
