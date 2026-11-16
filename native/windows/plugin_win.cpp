// PhoneCam OBS source for Windows (test build, docs/WINDOWS.md). Same source id, settings, automatic USB
// connection and direct Wi-Fi pairing as the Mac plugin; FFmpeg decoding.
#include "wifi.hpp"  // first: it brings in winsock2.h before windows.h
#include <obs-module.h>
#include <util/platform.h>
#include "adb.hpp"
#include "obs_common.hpp"
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

OBS_DECLARE_MODULE()
MODULE_EXPORT const char *obs_module_description(void) { return "Android phone camera over USB or Wi-Fi (Windows test build)"; }

namespace {
struct Source {
    obs_source_t *source;
    std::atomic<bool> stop{false};
    std::thread worker;
    std::mutex lock, sizeLock;
    std::string adbPath, status = "Starting…";
    uint32_t shownWidth = 0, shownHeight = 0;
    std::unique_ptr<phonecam::wifi::Server> wifi;
    std::thread wifiWorker;
    std::atomic<bool> card{false};
    explicit Source(obs_source_t *value) : source(value) {}
    std::string adbSetting() { std::lock_guard<std::mutex> guard(lock); return adbPath; }
    std::string currentStatus() { std::lock_guard<std::mutex> guard(lock); return status; }
    void setStatus(const std::string &value) {
        { std::lock_guard<std::mutex> guard(lock); status = value; }
        blog(LOG_INFO, "[phonecam] %s", value.c_str());
        obs_source_update_properties(source);
    }
    void noteSize(uint32_t width, uint32_t height) {
        std::lock_guard<std::mutex> guard(sizeLock);
        if (width == shownWidth && height == shownHeight) return;
        shownWidth = width; shownHeight = height;
        obs_queue_task(OBS_TASK_UI, fitToCanvas, obs_source_get_weak_source(source), false);
    }
    // One picture path for every connection: decoded NV12 or I420 from USB or Wi-Fi.
    void showFrame(phonecam::Image image) {
        if (stop) return;
        card = false;
        struct obs_source_frame frame{};
        frame.format = image->nv12 ? VIDEO_FORMAT_NV12 : VIDEO_FORMAT_I420;
        frame.width = image->width; frame.height = image->height;
        noteSize(frame.width, frame.height);
        for (int i = 0; i < (image->nv12 ? 2 : 3); ++i) { frame.data[i] = const_cast<uint8_t *>(image->plane[i]); frame.linesize[i] = image->stride[i]; }
        // Arrival-based OBS clock, as on the Mac. OBS copies the picture before this returns.
        frame.timestamp = os_gettime_ns(); frame.full_range = false;
        video_format_get_parameters(VIDEO_CS_709, VIDEO_RANGE_PARTIAL, frame.color_matrix, frame.color_range_min, frame.color_range_max);
        obs_source_output_video(source, &frame);
    }
    void showPairingCard() {
        if (!wifi) { setStatus("Direct Wi-Fi is off. Turn on \"Allow paired phones over Wi-Fi\" first, and keep this source visible."); return; }
        auto window = wifi->openPairing();
        const uint32_t width = 1280, height = 720;
        auto pixels = phonecam::wifi::renderPairingCard(window, width, height);
        struct obs_source_frame frame{};
        frame.format = VIDEO_FORMAT_BGRA; frame.width = width; frame.height = height;
        frame.data[0] = pixels.data(); frame.linesize[0] = width * 4;
        frame.timestamp = os_gettime_ns(); frame.full_range = true;
        noteSize(width, height);
        card = true;
        obs_source_output_video(source, &frame);
        setStatus("Pairing for 3 minutes: in PhoneCam open Settings → Pair a computer and scan the QR code, or type " + window.code + ".");
    }
    void hidePairingCard() { if (card.exchange(false)) obs_source_output_video(source, nullptr); }
    bool wifiAllowed() {
        obs_data_t *settings = obs_source_get_settings(source);
        bool allowed = obs_data_get_bool(settings, "wifi_direct");
        obs_data_release(settings);
        return allowed;
    }
    void start() {
        if (worker.joinable()) return;
        stop = false;
        { std::lock_guard<std::mutex> guard(sizeLock); shownWidth = shownHeight = 0; }
        worker = std::thread([this] {
            phonecam::autoReceive(stop, [this] { return adbSetting(); }, [this](const std::string &value) { setStatus(value); },
                [this](phonecam::Image image, uint64_t, uint64_t) { showFrame(image); });
            obs_source_output_video(source, nullptr);
        });
        if (wifiAllowed()) {
            wifi = std::make_unique<phonecam::wifi::Server>([this](const std::string &value) {
                setStatus(value);
                if (value.rfind("Paired with", 0) == 0 || value.rfind("Pairing window closed", 0) == 0) hidePairingCard();
            }, [this](phonecam::Image image, uint64_t, uint64_t) { showFrame(image); });
            wifiWorker = std::thread([this] { wifi->run(stop); });
        }
    }
    void end() {
        stop = true;
        if (worker.joinable()) worker.join();
        if (wifiWorker.joinable()) wifiWorker.join();
        wifi.reset(); card = false;
        obs_source_output_video(source, nullptr);
    }
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
void defaults(obs_data_t *settings) { obs_data_set_default_bool(settings, "fit_canvas", true); obs_data_set_default_bool(settings, "wifi_direct", true); }
void destroy(void *value) { delete static_cast<Source *>(value); }
void show(void *value) { static_cast<Source *>(value)->start(); }
void hide(void *value) { static_cast<Source *>(value)->end(); }
obs_properties_t *properties(void *value) {
    obs_properties_t *p = obs_properties_create();
    std::string status = value ? static_cast<Source *>(value)->currentStatus() : "Not running";
    obs_properties_add_text(p, "status", ("Status: " + status).c_str(), OBS_TEXT_INFO);
    obs_properties_add_text(p, "help", "Connect the phone by USB, or pair it once over Wi-Fi. Keep this source visible, then tap Start live on the phone.", OBS_TEXT_INFO);
    obs_properties_add_button2(p, "wifi_qr", "Pair a phone over Wi-Fi", [](obs_properties_t *, obs_property_t *, void *data) {
        if (data) static_cast<Source *>(data)->showPairingCard();
        return true;
    }, value);
    obs_properties_add_button2(p, "fit_now", "Fit to canvas now", [](obs_properties_t *, obs_property_t *, void *data) {
        if (data) fit(static_cast<Source *>(data)->source, true);
        return false;
    }, value);
    if (value) {
        auto *self = static_cast<Source *>(value);
        if (self->wifi) obs_properties_add_text(p, "wifi_summary", self->wifi->summary().c_str(), OBS_TEXT_INFO);
    }
    obs_properties_t *options = obs_properties_create();
    obs_properties_add_bool(options, "fit_canvas", "Fit the picture to the canvas automatically");
    obs_properties_add_bool(options, "wifi_direct", "Allow paired phones over Wi-Fi");
    obs_properties_add_button2(options, "wifi_forget", "Forget paired phones", [](obs_properties_t *, obs_property_t *, void *data) {
        if (!data) return false;
        try { phonecam::wifi::Server::forgetAll(); static_cast<Source *>(data)->setStatus("Forgot all paired phones. Pair again to use Wi-Fi."); }
        catch (const std::exception &error) { static_cast<Source *>(data)->setStatus(error.what()); }
        return true;
    }, value);
    obs_properties_add_group(p, "options", "Options", OBS_GROUP_NORMAL, options);
    obs_properties_t *advanced = obs_properties_create();
    obs_properties_add_text(advanced, "adb_path", "adb.exe path (found automatically)", OBS_TEXT_DEFAULT);
    obs_properties_add_text(advanced, "adb_help", "Wireless debugging: only needed if this PC was never allowed over USB.", OBS_TEXT_INFO);
    obs_properties_add_text(advanced, "wifi_address", "Pairing address and port", OBS_TEXT_DEFAULT);
    obs_properties_add_text(advanced, "wifi_code", "Pairing code", OBS_TEXT_DEFAULT);
    obs_properties_add_button2(advanced, "wifi_pair", "Pair Wireless debugging", [](obs_properties_t *, obs_property_t *, void *data) {
        if (data) startWifiPairing(static_cast<Source *>(data)->source);
        return false;
    }, value);
    if (value) {
        obs_data_t *settings = obs_source_get_settings(static_cast<Source *>(value)->source);
        std::string result = obs_data_get_string(settings, "wifi_result");
        obs_data_release(settings);
        if (!result.empty()) obs_properties_add_text(advanced, "wifi_status", result.c_str(), OBS_TEXT_INFO);
    }
    obs_properties_add_group(p, "advanced", "Advanced", OBS_GROUP_NORMAL, advanced);
    return p;
}
}

MODULE_EXPORT bool obs_module_load(void) {
    obs_source_info info{};
    info.id = "phonecam_usb_v1";  // same id as on the Mac, so saved scenes keep their source
    info.type = OBS_SOURCE_TYPE_INPUT;
    info.output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_DO_NOT_DUPLICATE;
    info.get_name = name; info.create = create; info.destroy = destroy; info.update = update;
    info.get_defaults = defaults; info.show = show; info.hide = hide; info.get_properties = properties;
    info.icon_type = OBS_ICON_TYPE_CAMERA;
    obs_register_source(&info);
    phonecam::setLog(logLine);
    return true;
}
