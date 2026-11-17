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
void refreshOnUi(void *weakSource);

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
    std::string status = "Starting…", beforeWarning;
    std::atomic<int64_t> cardUntil{0};
    std::atomic<bool> hideCardRequested{false};  // set by the Wi-Fi thread, acted on by a UI task  // the pairing card hides itself on time, even if the Wi-Fi server is busy
    bool wifiWarned = false;            // video thread only
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
    // "Keep the phone live while this source is hidden": hide leaves USB and Wi-Fi running. start/end are
    // serialised because hide runs on OBS's video thread and the switch on the UI thread.
    std::atomic<bool> visible{false}, keepLive{false};
    std::recursive_mutex runLock;
    explicit Source(obs_source_t *value) : source(value) {}
    std::shared_ptr<Link> current() { std::lock_guard<std::mutex> guard(linkLock); return link; }
    std::string currentStatus() { std::lock_guard<std::mutex> guard(lock); return status; }
    void setStatus(const std::string &value) {
        { std::lock_guard<std::mutex> guard(lock); status = value; beforeWarning.clear(); }
        blog(LOG_INFO, "[phonecam] %s", value.c_str());
        obs_source_update_properties(source);
    }
    // A watchdog warning replaces the status line until progress resumes; then the status it replaced comes
    // back (the connection thread does not repeat an unchanged status; audit/FINDINGS.md AUDIT-022a).
    void warn(const std::string &value) {
        {
            std::lock_guard<std::mutex> guard(lock);
            if (beforeWarning.empty()) beforeWarning = status;
            status = value;
        }
        blog(LOG_WARNING, "[phonecam] %s", value.c_str());
        obs_source_update_properties(source);
    }
    void clearWarning() {
        std::string previous;
        { std::lock_guard<std::mutex> guard(lock); previous = beforeWarning; }
        if (!previous.empty()) setStatus(previous);
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
        cardUntil = steadyMs() + 180000;  // the server's pairing window lasts 3 minutes
        obs_source_output_video(source, &frame);
        setStatus("Pairing for 3 minutes: in PhoneCam open Settings → Pair a computer and scan the QR code, or type " + window.code + ".");
    }
    // From any thread without waiting on OBS: the status line and the card are updated by a UI task.
    void postStatus(const std::string &value, bool hideCard) {
        { std::lock_guard<std::mutex> guard(lock); status = value; beforeWarning.clear(); }
        blog(LOG_INFO, "[phonecam] %s", value.c_str());
        if (hideCard) hideCardRequested = true;
        obs_queue_task(OBS_TASK_UI, refreshOnUi, obs_source_get_weak_source(source), false);
    }
    void hidePairingCard() { if (card.exchange(false)) obs_source_output_video(source, nullptr); }
    // wifiLock held.
    void startWifi() {
        if (wifi || !showing || !wifiWanted) return;
        wifiStop = false;
        // The Wi-Fi thread never calls into OBS beyond the log: the status line and the pairing card are
        // updated by a UI task. On the 17 Nov laptop the thread stopped for good inside this callback right
        // after "Paired with…", so nothing was accepted any more (phone: ECONNREFUSED).
        wifi = std::make_unique<phonecam::wifi::Server>([this](const std::string &value) {
            postStatus(value, value.rfind("Paired with", 0) == 0 || value.rfind("Pairing window closed", 0) == 0);
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
    void setKeepLive(bool keep) {
        keepLive = keep;
        if (keep || visible) return;
        std::lock_guard<std::recursive_mutex> running(runLock);
        if (bool(current())) end();  // switched off while hidden: stop now, as a hide would have
    }
    void setWifiWanted(bool wanted) {
        std::lock_guard<std::mutex> guard(wifiLock);
        wifiWanted = wanted;
        if (wanted) startWifi(); else stopWifi();
    }
    void start() {
        std::lock_guard<std::recursive_mutex> running(runLock);
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
    // Called by OBS on its video thread (hide) and on destroy: waits at most half a second for the USB thread
    // (audit/FINDINGS.md AUDIT-002); a thread still finishing is left behind and can no longer reach the source.
    void end() {
        std::lock_guard<std::recursive_mutex> running(runLock);
        stop = true;
        std::shared_ptr<Link> shared;
        { std::lock_guard<std::mutex> guard(linkLock); shared = std::move(link); }
        if (shared) {
            shared->stop = true;
            bool finished;
            {
                std::unique_lock<std::mutex> guard(shared->doneLock);
                finished = shared->doneSignal.wait_for(guard, std::chrono::milliseconds(500), [&] { return shared->done; });
            }
            // A callback still inside OBS after 2 s: keep this source's memory (destroy skips it) rather than
            // free it under that callback.
            if (shared->lock.try_lock_for(std::chrono::seconds(2))) { shared->owner = nullptr; shared->lock.unlock(); }
            else leaked = true;
            if (finished) worker.join();
            else {
                blog(LOG_INFO, "[phonecam] The connection thread finishes in the background (%s).",
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
        if (card && !hideCardRequested && steadyMs() > cardUntil)
            postStatus("Pairing window closed. Click Pair a phone over Wi-Fi to try again.", true);
        watchWifi();
        auto shared = current();
        if (!shared) return;
        const int step = shared->activity.step;
        const int64_t since = shared->activity.sinceMs;
        if (shared->reportedSince >= 0 && since != shared->reportedSince) { shared->reportedSince = -1; clearWarning(); }
        if (phonecam::Activity::waits(step) || since == shared->reportedSince || steadyMs() - since < 15000) return;
        shared->reportedSince = since;
        warn(std::string("Not responding for 15 s while ") + phonecam::Activity::name(step) +
             ". Unplug the phone and plug it in again; if this stays, close and reopen OBS.");
    }
    // The Wi-Fi server thread stuck in one step (17 Nov laptop: it stopped accepting after a pairing).
    // Reported once in the log, so the step can be fixed; never blocks the video thread.
    void watchWifi() {
        std::unique_lock<std::mutex> guard(wifiLock, std::try_to_lock);
        if (!guard || !wifi) return;
        const char *stuck = wifi->stuckLongerThan(10000);
        if (stuck && !wifiWarned) { wifiWarned = true; blog(LOG_WARNING, "[phonecam] Wi-Fi server: no progress for 10 s while %s.", stuck); }
        if (!stuck && wifiWarned) { wifiWarned = false; blog(LOG_INFO, "[phonecam] Wi-Fi server: working again."); }
    }
    ~Source() { end(); }
};

// UI thread: applies what the Wi-Fi thread asked for. The weak reference keeps a removed source safe.
void refreshOnUi(void *weakSource) {
    auto *weak = static_cast<obs_weak_source_t *>(weakSource);
    obs_source_t *strong = obs_weak_source_get_source(weak);
    obs_weak_source_release(weak);
    if (!strong) return;
    if (auto *self = static_cast<Source *>(obs_obj_get_data(strong)); self && self->hideCardRequested.exchange(false))
        self->hidePairingCard();
    obs_source_update_properties(strong);
    obs_source_release(strong);
}

const char *name(void *) { return "PhoneCam (phone camera)"; }
void update(void *value, obs_data_t *settings) {
    auto *self = static_cast<Source *>(value);
    {
        std::lock_guard<std::mutex> guard(self->adb->lock);
        self->adb->path = obs_data_get_string(settings, "adb_path");
    }
    self->setWifiWanted(obs_data_get_bool(settings, "wifi_direct"));
    self->setKeepLive(obs_data_get_bool(settings, "keep_live"));
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
void show(void *value) { auto *self = static_cast<Source *>(value); self->visible = true; self->start(); }
void hide(void *value) {
    auto *self = static_cast<Source *>(value);
    self->visible = false;
    if (!self->keepLive) self->end();
}
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
    obs_property_set_long_description(obs_properties_add_bool(options, "fit_canvas", "Fit the picture to the canvas automatically"),
        "Scales the phone picture to fit the canvas when it first appears or its size changes (portrait, landscape, 4K). Untick it to keep your own size and position.");
    obs_property_set_long_description(obs_properties_add_bool(options, "wifi_direct", "Allow paired phones over Wi-Fi"),
        "Lets phones you paired before connect over your local Wi-Fi without a cable. While it is on and a phone is paired, this computer listens on port 47183 on the local network only. Untick it to close that port.");
    obs_property_set_long_description(obs_properties_add_bool(options, "keep_live", "Keep the phone live while this source is hidden"),
        "Normally hiding this source (eye icon or another scene) stops the phone, and you tap Start live again. Tick this to keep the phone sending while hidden, so switching back is instant. The phone keeps its camera on, gets warmer and uses more battery.");
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
