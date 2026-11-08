#include <obs-module.h>
#include <util/platform.h>
#include "adb.hpp"
#include <thread>
#include <mutex>
#include <chrono>

OBS_DECLARE_MODULE()
MODULE_EXPORT const char *obs_module_description(void) { return "Android phone camera over USB with hardware decoding"; }

namespace {
struct Source {
    obs_source_t *source;
    std::atomic<bool> stop{false};
    std::thread worker;
    std::mutex lock;
    std::string adbPath, status = "Starting…";
    explicit Source(obs_source_t *value) : source(value) {}
    std::string adbSetting() { std::lock_guard<std::mutex> guard(lock); return adbPath; }
    std::string currentStatus() { std::lock_guard<std::mutex> guard(lock); return status; }
    void setStatus(const std::string &value) {
        { std::lock_guard<std::mutex> guard(lock); status = value; }
        blog(LOG_INFO, "[phonecam] %s", value.c_str());
        obs_source_update_properties(source);
    }
    void start() {
        if (worker.joinable()) return;
        stop = false;
        worker = std::thread([this] {
            phonecam::autoReceive(stop, [this] { return adbSetting(); }, [this](const std::string &value) { setStatus(value); },
                [this](CVPixelBufferRef image, uint64_t, uint64_t) {
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
                });
            obs_source_output_video(source, nullptr);
        });
    }
    void end() { stop = true; if (worker.joinable()) worker.join(); obs_source_output_video(source, nullptr); }
    ~Source() { end(); }
};
const char *name(void *) { return "PhoneCam (USB phone camera)"; }
void update(void *value, obs_data_t *settings) {
    auto *self = static_cast<Source *>(value);
    std::lock_guard<std::mutex> guard(self->lock);
    self->adbPath = obs_data_get_string(settings, "adb_path");
}
void *create(obs_data_t *settings, obs_source_t *source) {
    obs_source_set_async_unbuffered(source, true);
    auto *self = new Source(source);
    update(self, settings);
    return self;
}
void destroy(void *value) { delete static_cast<Source *>(value); }
void show(void *value) { static_cast<Source *>(value)->start(); }
void hide(void *value) { static_cast<Source *>(value)->end(); }
obs_properties_t *properties(void *value) {
    auto *p = obs_properties_create();
    std::string status = value ? static_cast<Source *>(value)->currentStatus() : "Not running";
    obs_properties_add_text(p, "status", ("Status: " + status).c_str(), OBS_TEXT_INFO);
    obs_properties_add_text(p, "help",
        "Connect the phone by USB (USB debugging on), keep this source visible, then tap Start live in the PhoneCam app. "
        "Pairing and reconnection are automatic. Rotate freely: the picture stays upright. Set Transform → Bounding Box Type "
        "to \"Scale to inner bounds\" so portrait and landscape both fit. Video only, no audio yet.", OBS_TEXT_INFO);
    obs_properties_add_text(p, "adb_path", "adb path (optional; found automatically)", OBS_TEXT_DEFAULT);
    return p;
}
}
MODULE_EXPORT bool obs_module_load(void) {
    obs_source_info info{};
    // The id stays stable so existing scenes keep their source.
    info.id = "phonecam_usb_v1"; info.type = OBS_SOURCE_TYPE_INPUT;
    info.output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_DO_NOT_DUPLICATE;
    info.get_name = name; info.create = create; info.destroy = destroy; info.update = update;
    info.show = show; info.hide = hide; info.get_properties = properties;
    info.icon_type = OBS_ICON_TYPE_CAMERA;
    obs_register_source(&info);
    return true;
}
