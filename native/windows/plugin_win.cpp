// PhoneCam OBS source for Windows (test build, docs/WINDOWS.md). Same source id, settings, automatic USB
// connection and direct Wi-Fi pairing as the Mac plugin; FFmpeg decoding.
#include "wifi.hpp"  // first: it brings in winsock2.h before windows.h
#include <obs-module.h>
#include <util/platform.h>
#include "adb.hpp"
#include "obs_common.hpp"
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

OBS_DECLARE_MODULE()
MODULE_EXPORT const char *obs_module_description(void) { return "Android phone camera over USB or Wi-Fi (Windows test build)"; }

namespace {
struct Source;
struct AdbPath { std::mutex lock; std::string path; };
// Shared with the USB connection thread, so a thread stuck in a call that never returns can be left behind
// without touching a deleted source: OBS never waits more than a few seconds for it.
struct Link {
    std::atomic<bool> stop{false};
    phonecam::Activity activity;
    std::timed_mutex lock;
    Source *owner = nullptr;  // null once the source no longer waits for this thread
    std::shared_ptr<AdbPath> adb;
    std::mutex doneLock;
    std::condition_variable doneSignal;
    bool done = false;
    int64_t reportedSince = -1;  // watchdog: the stuck step already reported
};
int64_t steadyMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

struct Source {
    obs_source_t *source;
    std::atomic<bool> stop{false};  // Wi-Fi server and frame output
    std::mutex linkLock;  // the video thread's watchdog reads link while OBS may destroy the source
    std::shared_ptr<Link> link;
    std::thread worker;
    std::mutex lock, sizeLock;
    std::shared_ptr<AdbPath> adb = std::make_shared<AdbPath>();
    std::string status = "Starting…";
    bool leaked = false;
    uint32_t shownWidth = 0, shownHeight = 0;
    // Direct Wi-Fi server: started and stopped under wifiLock, from show/hide and from the "Allow paired phones
    // over Wi-Fi" switch (applied at once, audit/FINDINGS.md AUDIT-006). The UI thread reads it under the same lock.
    std::mutex wifiLock;
    bool showing = false, wifiWanted = true;
    std::atomic<bool> wifiStop{false};
    std::unique_ptr<phonecam::wifi::Server> wifi;
    std::thread wifiWorker;
    std::atomic<bool> card{false};
    explicit Source(obs_source_t *value) : source(value) {}
    std::shared_ptr<Link> current() { std::lock_guard<std::mutex> guard(linkLock); return link; }
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
        std::lock_guard<std::mutex> guard(wifiLock);
        if (!wifi) { setStatus("Direct Wi-Fi is off. Turn on \"Allow paired phones over Wi-Fi\" first, and keep this source visible."); return; }
        phonecam::wifi::PairingWindow window;
        try { window = wifi->openPairing(); }
        catch (const std::exception &error) { setStatus(error.what()); return; }
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
    // wifiLock held.
    void startWifi() {
        if (wifi || !showing || !wifiWanted) return;
        wifiStop = false;
        wifi = std::make_unique<phonecam::wifi::Server>([this](const std::string &value) {
            setStatus(value);
            if (value.rfind("Paired with", 0) == 0 || value.rfind("Pairing window closed", 0) == 0) hidePairingCard();
        }, [this](phonecam::Image image, uint64_t, uint64_t) { showFrame(image); },
        [](const std::string &value) { blog(LOG_INFO, "[phonecam] %s", value.c_str()); });
        auto *server = wifi.get();
        wifiWorker = std::thread([server, this] {
            try { server->run(wifiStop); }  // run() reports its own problems; this is the last line of defence
            catch (...) { setStatus("Direct Wi-Fi stopped after an unexpected error."); }
        });
    }
    // wifiLock held.
    void stopWifi() {
        wifiStop = true;
        if (wifiWorker.joinable()) wifiWorker.join();
        wifi.reset();
        hidePairingCard();
    }
    void setWifiWanted(bool wanted) {
        std::lock_guard<std::mutex> guard(wifiLock);
        wifiWanted = wanted;
        if (wanted) startWifi(); else stopWifi();
    }
    void start() {
        if (current()) return;
        stop = false;
        { std::lock_guard<std::mutex> guard(sizeLock); shownWidth = shownHeight = 0; }
        auto shared = std::make_shared<Link>();
        shared->owner = this; shared->adb = adb;
        shared->activity.set(phonecam::Activity::Starting);
        { std::lock_guard<std::mutex> guard(linkLock); link = shared; }
        worker = std::thread([shared] {
            auto report = [shared](const std::string &value) {
                std::lock_guard<std::timed_mutex> guard(shared->lock);
                if (shared->owner) shared->owner->setStatus(value);
            };
            // Nothing may escape a thread (std::terminate would close OBS; audit/FINDINGS.md AUDIT-004).
            try {
                phonecam::autoReceive(shared->stop,
                    [shared] { std::lock_guard<std::mutex> guard(shared->adb->lock); return shared->adb->path; }, report,
                    [shared](phonecam::Image image, uint64_t, uint64_t) {
                        std::lock_guard<std::timed_mutex> guard(shared->lock);
                        if (shared->owner) shared->owner->showFrame(image);
                    }, &shared->activity);
            } catch (const std::exception &error) { try { report(std::string("USB connection stopped: ") + error.what()); } catch (...) {} }
            catch (...) { try { report("USB connection stopped after an unexpected error. Hide and show this source to retry."); } catch (...) {} }
            {
                std::lock_guard<std::timed_mutex> guard(shared->lock);
                if (shared->owner) obs_source_output_video(shared->owner->source, nullptr);
            }
            std::lock_guard<std::mutex> guard(shared->doneLock);
            shared->done = true; shared->doneSignal.notify_all();
        });
        std::lock_guard<std::mutex> guard(wifiLock);
        showing = true;
        startWifi();
    }
    // Called by OBS while it draws (show/hide), so it waits at most a few seconds for the USB thread.
    void end() {
        stop = true;
        std::shared_ptr<Link> shared;
        { std::lock_guard<std::mutex> guard(linkLock); shared = std::move(link); }
        if (shared) {
            shared->stop = true;
            bool finished;
            {
                std::unique_lock<std::mutex> guard(shared->doneLock);
                finished = shared->doneSignal.wait_for(guard, std::chrono::seconds(6), [&] { return shared->done; });
            }
            // A callback still inside OBS after 2 s: keep this source's memory (destroy skips it) rather than
            // free it under that callback.
            if (shared->lock.try_lock_for(std::chrono::seconds(2))) { shared->owner = nullptr; shared->lock.unlock(); }
            else leaked = true;
            if (finished) worker.join();
            else {
                blog(LOG_WARNING, "[phonecam] The connection thread is still busy (%s); OBS continues without it.",
                     phonecam::Activity::name(shared->activity.step));
                phonecam::keepLoaded();
                worker.detach();
            }
        }
        {
            std::lock_guard<std::mutex> guard(wifiLock);
            showing = false;
            stopWifi();
        }
        card = false;
        obs_source_output_video(source, nullptr);
    }
    // Watchdog, once per frame: reports a connection step that has not finished for 15 s, once per step.
    void watch() {
        auto shared = current();
        if (!shared) return;
        const int step = shared->activity.step;
        const int64_t since = shared->activity.sinceMs;
        if (phonecam::Activity::waits(step) || since == shared->reportedSince || steadyMs() - since < 15000) return;
        shared->reportedSince = since;
        blog(LOG_WARNING, "[phonecam] No progress for 15 s while %s.", phonecam::Activity::name(step));
        setStatus(std::string("Not responding while ") + phonecam::Activity::name(step) +
                  ". Unplug the phone and plug it in again; if this stays, close and reopen OBS.");
    }
    ~Source() { end(); }
};

const char *name(void *) { return "PhoneCam (phone camera)"; }
void update(void *value, obs_data_t *settings) {
    auto *self = static_cast<Source *>(value);
    {
        std::lock_guard<std::mutex> guard(self->adb->lock);
        self->adb->path = obs_data_get_string(settings, "adb_path");
    }
    self->setWifiWanted(obs_data_get_bool(settings, "wifi_direct"));
}
void *create(obs_data_t *settings, obs_source_t *source) {
    obs_source_set_async_unbuffered(source, true);
    auto *self = new Source(source);
    update(self, settings);
    return self;
}
void defaults(obs_data_t *settings) { obs_data_set_default_bool(settings, "fit_canvas", true); obs_data_set_default_bool(settings, "wifi_direct", true); }
void destroy(void *value) {
    auto *self = static_cast<Source *>(value);
    self->end();
    if (!self->leaked) delete self;
}
void tick(void *value, float) { static_cast<Source *>(value)->watch(); }
void show(void *value) { static_cast<Source *>(value)->start(); }
void hide(void *value) { static_cast<Source *>(value)->end(); }
obs_properties_t *properties(void *value) {
    obs_properties_t *p = obs_properties_create();
    std::string status = value ? static_cast<Source *>(value)->currentStatus() : "Not running";
    obs_properties_add_text(p, "status", ("Status: " + status).c_str(), OBS_TEXT_INFO);
    obs_properties_add_text(p, "help", "Connect the phone by USB, or pair it once over Wi-Fi. Keep this source visible, then tap Start live on the phone.", OBS_TEXT_INFO);
    obs_properties_add_button2(p, "wifi_qr", "Pair a phone over Wi-Fi", [](obs_properties_t *, obs_property_t *, void *data) {
        if (data) { try { static_cast<Source *>(data)->showPairingCard(); } catch (...) {} }
        return true;
    }, value);
    obs_properties_add_button2(p, "fit_now", "Fit to canvas now", [](obs_properties_t *, obs_property_t *, void *data) {
        if (data) fit(static_cast<Source *>(data)->source, true);
        return false;
    }, value);
    if (value) {
        auto *self = static_cast<Source *>(value);
        std::string summary;
        {
            std::lock_guard<std::mutex> guard(self->wifiLock);
            try { if (self->wifi) summary = self->wifi->summary(); } catch (...) {}
        }
        if (!summary.empty()) obs_properties_add_text(p, "wifi_summary", summary.c_str(), OBS_TEXT_INFO);
    }
    obs_properties_t *options = obs_properties_create();
    obs_properties_add_bool(options, "fit_canvas", "Fit the picture to the canvas automatically");
    obs_properties_add_bool(options, "wifi_direct", "Allow paired phones over Wi-Fi");
    obs_properties_add_button2(options, "wifi_forget", "Forget paired phones", [](obs_properties_t *, obs_property_t *, void *data) {
        if (!data) return false;
        auto *self = static_cast<Source *>(data);
        try {
            std::lock_guard<std::mutex> guard(self->wifiLock);
            if (self->wifi) self->wifi->forget(); else phonecam::wifi::Server::forgetAll();
            self->setStatus("Forgot all paired phones. Pair again to use Wi-Fi.");
        }
        catch (const std::exception &error) { self->setStatus(error.what()); }
        catch (...) { self->setStatus("Could not forget the paired phones."); }
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
    info.video_tick = tick;
    info.icon_type = OBS_ICON_TYPE_CAMERA;
    obs_register_source(&info);
    phonecam::setLog(logLine);
    return true;
}
