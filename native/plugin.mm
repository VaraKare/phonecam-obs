#include <obs-module.h>
#include <util/platform.h>
#include "adb.hpp"
#include <thread>
#include <mutex>
#include <chrono>

OBS_DECLARE_MODULE()
MODULE_EXPORT const char *obs_module_description(void) { return "Android phone camera over USB or Wi-Fi with hardware decoding"; }

namespace {
struct Fit { obs_source_t *source; float width, height; bool force; };
bool fitItem(obs_scene_t *, obs_sceneitem_t *item, void *data) {
    auto *fit = static_cast<Fit *>(data);
    if (obs_sceneitem_get_source(item) != fit->source) return true;
    struct vec2 scale, pos;
    obs_sceneitem_get_scale(item, &scale); obs_sceneitem_get_pos(item, &pos);
    // Automatically only untouched items: the user's own size, position, rotation or bounding box win.
    // The "Fit to canvas now" button (force) resets them on request.
    if (!fit->force && (obs_sceneitem_get_bounds_type(item) != OBS_BOUNDS_NONE || scale.x != 1.f || scale.y != 1.f ||
        pos.x != 0.f || pos.y != 0.f || obs_sceneitem_get_rot(item) != 0.f)) return true;
    struct vec2 origin, one; vec2_set(&origin, 0.f, 0.f); vec2_set(&one, 1.f, 1.f);
    obs_sceneitem_set_pos(item, &origin); obs_sceneitem_set_scale(item, &one); obs_sceneitem_set_rot(item, 0.f);
    if (fit->force) { struct obs_sceneitem_crop crop{}; obs_sceneitem_set_crop(item, &crop); }
    struct vec2 bounds; vec2_set(&bounds, fit->width, fit->height);
    obs_sceneitem_set_alignment(item, OBS_ALIGN_LEFT | OBS_ALIGN_TOP);
    obs_sceneitem_set_bounds_type(item, OBS_BOUNDS_SCALE_INNER);
    obs_sceneitem_set_bounds_alignment(item, OBS_ALIGN_CENTER);
    obs_sceneitem_set_bounds(item, &bounds);
    blog(LOG_INFO, "[phonecam] Fitted the picture to the %.0f×%.0f canvas (Transform → Scale to inner bounds)", fit->width, fit->height);
    return true;
}
bool fitScene(void *data, obs_source_t *sceneSource) {
    if (obs_scene_t *scene = obs_scene_from_source(sceneSource)) obs_scene_enum_items(scene, fitItem, data);
    return true;
}
/**
 * Runs on the OBS UI thread. A 4K or portrait picture is larger than a 1080p canvas, so OBS would show
 * only part of it. Untouched scene items get "Scale to inner bounds" at canvas size, which then also
 * keeps portrait and landscape fitted when the phone turns.
 */
void fit(obs_source_t *source, bool force) {
    struct obs_video_info video{};
    if (!obs_get_video_info(&video)) return;
    Fit fit{source, float(video.base_width), float(video.base_height), force};
    obs_enum_scenes(fitScene, &fit);
}
void fitToCanvas(void *param) {
    auto *weak = static_cast<obs_weak_source_t *>(param);
    obs_source_t *source = obs_weak_source_get_source(weak);
    obs_weak_source_release(weak);
    if (!source) return;
    obs_data_t *settings = obs_source_get_settings(source);
    bool enabled = obs_data_get_bool(settings, "fit_canvas");
    obs_data_release(settings);
    if (enabled) fit(source, false);
    obs_source_release(source);
}
/** Wireless debugging pairing takes a few seconds, so it runs off the UI thread; the source may be gone by then. */
void startWifiPairing(obs_source_t *source) {
    obs_data_t *settings = obs_source_get_settings(source);
    std::string adbPath = obs_data_get_string(settings, "adb_path"), address = obs_data_get_string(settings, "wifi_address"),
                code = obs_data_get_string(settings, "wifi_code");
    obs_data_release(settings);
    obs_weak_source_t *weak = obs_source_get_weak_source(source);
    std::thread([weak, adbPath, address, code] {
        std::string result = phonecam::pairWifi(phonecam::findAdb(adbPath), address, code);
        blog(LOG_INFO, "[phonecam] %s", result.c_str());
        obs_source_t *strong = obs_weak_source_get_source(weak);
        obs_weak_source_release(weak);
        if (!strong) return;
        obs_data_t *values = obs_data_create();
        obs_data_set_string(values, "wifi_code", "");  // single use; never kept in the scene file
        obs_data_set_string(values, "wifi_result", result.c_str());
        obs_source_update(strong, values);
        obs_data_release(values);
        obs_source_update_properties(strong);
        obs_source_release(strong);
    }).detach();
}
void logLine(const char *line) { blog(LOG_INFO, "[phonecam] %s", line); }
struct Source {
    obs_source_t *source;
    std::atomic<bool> stop{false};
    std::thread worker;
    std::mutex lock;
    std::string adbPath, status = "Starting…";
    uint32_t shownWidth = 0, shownHeight = 0;  // touched only by the receiving thread
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
        stop = false; shownWidth = shownHeight = 0;
        worker = std::thread([this] {
            phonecam::autoReceive(stop, [this] { return adbSetting(); }, [this](const std::string &value) { setStatus(value); },
                [this](CVPixelBufferRef image, uint64_t, uint64_t) {
                    if (stop) return;
                    if (CVPixelBufferLockBaseAddress(image, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess) return;
                    struct obs_source_frame frame{};
                    frame.format = VIDEO_FORMAT_NV12;
                    frame.width = uint32_t(CVPixelBufferGetWidth(image)); frame.height = uint32_t(CVPixelBufferGetHeight(image));
                    if (frame.width != shownWidth || frame.height != shownHeight) {
                        shownWidth = frame.width; shownHeight = frame.height;
                        obs_queue_task(OBS_TASK_UI, fitToCanvas, obs_source_get_weak_source(source), false);
                    }
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
const char *name(void *) { return "PhoneCam (phone camera)"; }
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
void defaults(obs_data_t *settings) { obs_data_set_default_bool(settings, "fit_canvas", true); }
void destroy(void *value) { delete static_cast<Source *>(value); }
void show(void *value) { static_cast<Source *>(value)->start(); }
void hide(void *value) { static_cast<Source *>(value)->end(); }
obs_properties_t *properties(void *value) {
    auto *p = obs_properties_create();
    std::string status = value ? static_cast<Source *>(value)->currentStatus() : "Not running";
    obs_properties_add_text(p, "status", ("Status: " + status).c_str(), OBS_TEXT_INFO);
    obs_properties_add_text(p, "help",
        "Connect the phone by USB (USB debugging on) or Wi-Fi (below), keep this source visible, then tap Start live in the PhoneCam app. "
        "Pairing and reconnection are automatic. Rotate freely: the picture stays upright and, with the option below, "
        "fits the canvas in portrait and landscape. Video only, no audio yet.", OBS_TEXT_INFO);
    obs_properties_add_button2(p, "fit_now", "Fit to canvas now", [](obs_properties_t *, obs_property_t *, void *data) {
        if (data) fit(static_cast<Source *>(data)->source, true);  // properties callbacks run on the UI thread
        return false;
    }, value);
    obs_properties_add_bool(p, "fit_canvas", "Fit the picture to the canvas automatically (until you resize or move it yourself)");
    obs_properties_add_text(p, "adb_path", "adb path (optional; found automatically)", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "wifi_help",
        "Wi-Fi (no cable): on the phone open Developer options → Wireless debugging → turn it on → Pair device with pairing code. "
        "Type the IP address & port and the 6-digit code below, then click Pair over Wi-Fi. Pair once; after that, turn Wireless "
        "debugging on (phone and Mac on the same Wi-Fi) and the phone connects automatically. No Internet access is needed. "
        "With the cable plugged in, USB is used.", OBS_TEXT_INFO);
    obs_properties_add_text(p, "wifi_address", "Pairing IP address & port", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "wifi_code", "Pairing code", OBS_TEXT_DEFAULT);
    obs_properties_add_button2(p, "wifi_pair", "Pair over Wi-Fi", [](obs_properties_t *, obs_property_t *, void *data) {
        if (data) startWifiPairing(static_cast<Source *>(data)->source);
        return false;
    }, value);
    if (value) {
        obs_data_t *settings = obs_source_get_settings(static_cast<Source *>(value)->source);
        std::string result = obs_data_get_string(settings, "wifi_result");
        obs_data_release(settings);
        if (!result.empty()) obs_properties_add_text(p, "wifi_status", result.c_str(), OBS_TEXT_INFO);
    }
    return p;
}
}
MODULE_EXPORT bool obs_module_load(void) {
    obs_source_info info{};
    // The id stays stable so existing scenes keep their source.
    info.id = "phonecam_usb_v1"; info.type = OBS_SOURCE_TYPE_INPUT;
    info.output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_DO_NOT_DUPLICATE;
    info.get_name = name; info.create = create; info.destroy = destroy; info.update = update;
    info.get_defaults = defaults; info.show = show; info.hide = hide; info.get_properties = properties;
    info.icon_type = OBS_ICON_TYPE_CAMERA;
    obs_register_source(&info);
    phonecam::setLog(logLine);
    return true;
}
