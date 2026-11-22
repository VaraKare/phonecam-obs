#pragma once
#include <atomic>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <vector>
// OBS-side helpers shared by the macOS and Windows plugins (each plugin is one translation unit):
// fitting the picture to the canvas, Wireless debugging pairing, and the OBS log sink.
#include <obs-module.h>
#include <util/platform.h>
#include "adb.hpp"
#include <string>
#include <thread>

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
        std::string result;
        try { result = phonecam::pairWifi(phonecam::findAdb(adbPath), address, code); }  // nothing may escape a thread
        catch (...) { result = "Wi-Fi pairing could not run adb. Check the adb path and try again."; }
        blog(LOG_INFO, "[phonecam] %s", result.c_str());
        obs_source_t *strong = obs_weak_source_get_source(weak);
        obs_weak_source_release(weak);
        if (!strong) return;
        obs_data_t *values = obs_data_create();
        obs_data_set_string(values, "wifi_code", "");  // single use; never kept in the scene file
        obs_data_set_string(values, "wifi_result", result.c_str());
        obs_source_update(strong, values);
        obs_data_release(values);
        // Properties are refreshed on the UI thread: from a worker thread this can block while the Properties
        // window is open (seen on Windows, 17 Nov).
        obs_queue_task(OBS_TASK_UI, [](void *param) {
            auto *weakRef = static_cast<obs_weak_source_t *>(param);
            if (obs_source_t *source = obs_weak_source_get_source(weakRef)) { obs_source_update_properties(source); obs_source_release(source); }
            obs_weak_source_release(weakRef);
        }, obs_source_get_weak_source(strong), false);
        obs_source_release(strong);
    }).detach();
}
void logLine(const char *line) { blog(LOG_INFO, "[phonecam] %s", line); }
// Runs task(weak source) on OBS's UI thread, but never inside the current call. On the UI thread obs_queue_task
// runs the task at once, and a Properties refresh inside a button click deletes the button OBS is still handling:
// OBS crashed on "Forget paired phones" (21 Nov 2026, Mac, twice). From the UI thread it hops through a short-lived
// thread, so OBS queues the task behind the click instead.
void queueOnUi(obs_source_t *source, void (*task)(void *)) {
    obs_weak_source_t *weak = obs_source_get_weak_source(source);
    if (!obs_in_task_thread(OBS_TASK_UI)) { obs_queue_task(OBS_TASK_UI, task, weak, false); return; }
    std::thread([task, weak] { obs_queue_task(OBS_TASK_UI, task, weak, false); }).detach();
}
// The status box at the top of the source's Properties: yellow when something needs the user, with help on hover.
bool needsAttention(const std::string &status) {
    for (const char *word : {"Not responding", "not found", "stopped", "failed", "unavailable", "ould not", "Unlock the phone",
                             "offline", "Install the PhoneCam app", "Another PhoneCam source", "Direct Wi-Fi is off", "not an executable", "not ready", "too many"})
        if (status.find(word) != std::string::npos) return true;
    return false;
}
// AUDIT-011: one PhoneCam source at a time talks to phones (USB and Wi-Fi). A second source used to open its
// own link to the same phone and report "stalled" in a loop. The others wait, say why, and take over when the
// owner is hidden or removed. One source can still appear in many scenes (Add Existing).
std::mutex phoneOwnerLock;
const void *phoneOwner = nullptr;
bool claimPhone(const void *source) {
    std::lock_guard<std::mutex> guard(phoneOwnerLock);
    if (phoneOwner && phoneOwner != source) return false;
    phoneOwner = source; return true;
}
void releasePhone(const void *source) {
    std::lock_guard<std::mutex> guard(phoneOwnerLock);
    if (phoneOwner == source) phoneOwner = nullptr;
}
constexpr const char *anotherSource = "Another PhoneCam source is using the phone. Use one PhoneCam source per phone; "
    "to show it in more scenes, add the existing source (Add Existing). This one takes over when the other is removed, "
    "or hidden without \"Keep the phone live while this source is hidden\".";

// AUDIT-013: why the last session ended stays readable until the next session starts; the live status
// line moves on to "Ready…" within a second or two.
void noteEnding(std::string &lastEnded, const std::string &status) {
    for (const char *prefix : {"Live ended: ", "Wi-Fi live ended: "})
        if (status.rfind(prefix, 0) == 0) { lastEnded = status.substr(strlen(prefix)); return; }
    if (status.rfind("Live over", 0) == 0) lastEnded.clear();
}
void addStatus(obs_properties_t *props, const std::string &status, const std::string &lastEnded = std::string()) {
    obs_property_t *line = obs_properties_add_text(props, "status", status.c_str(), OBS_TEXT_INFO);
    obs_property_text_set_info_type(line, needsAttention(status) ? OBS_TEXT_INFO_WARNING : OBS_TEXT_INFO_NORMAL);
    // No long description: OBS would show it beside the status and squeeze both (20 Nov). Each status says the next step.
    if (!lastEnded.empty() && status.find(lastEnded) == std::string::npos)
        obs_properties_add_text(props, "last_ended", ("Last session ended: " + lastEnded).c_str(), OBS_TEXT_INFO);
}

// ---- 0.6: the phone talks back (controls, status, microphone) ----

// A PhoneCam source made before 0.6 had no sound, so OBS saved it with no audio tracks: the microphone then shows
// in the mixer as "unassigned" and is missing from recordings (21 Nov). When the phone's sound starts and the source
// has no track at all, it gets the tracks a new OBS source gets (1-6). Every session: a "once per source" rule
// missed it in the 22 Nov test. Someone who wants no phone sound leaves the phone microphone off, or mutes it.
void assignAudioTracks(void *weakSource) {
    auto *weak = static_cast<obs_weak_source_t *>(weakSource);
    obs_source_t *strong = obs_weak_source_get_source(weak);
    obs_weak_source_release(weak);
    if (!strong) return;
    if (obs_source_get_audio_mixers(strong) == 0) {
        obs_source_set_audio_mixers(strong, 0x3F);
        blog(LOG_INFO, "[phonecam] The phone's sound had no audio tracks in OBS; turned on tracks 1-6 so streams and recordings include it.");
    }
    obs_source_release(strong);
}
void refreshProperties(void *weakSource) {
    auto *weak = static_cast<obs_weak_source_t *>(weakSource);
    obs_source_t *strong = obs_weak_source_get_source(weak);
    obs_weak_source_release(weak);
    if (!strong) return;
    obs_source_update_properties(strong);
    obs_source_release(strong);
}
std::vector<std::string> splitList(const std::string &text, char by) {
    std::vector<std::string> out; std::string part; std::istringstream in(text);
    while (std::getline(in, part, by)) if (!part.empty()) out.push_back(part);
    return out;
}
// One per source. The USB channel is shared with the connection thread (which may outlive the source); its
// callbacks are cleared before this object goes away.
struct PhoneControls {
    obs_source_t *source;
    std::shared_ptr<phonecam::Channel> usb = std::make_shared<phonecam::Channel>(), wifi = std::make_shared<phonecam::Channel>();
    std::mutex lock;
    std::map<std::string, std::string> info;  // the phone's last status; empty without a 0.6 phone
    phonecam::ClockMap clock;                 // under lock
    std::atomic<uint64_t> audioUntil{0};      // OBS clock (ns): the microphone sent audio recently
    std::atomic<bool> tracksChecked{false};
    explicit PhoneControls(obs_source_t *value) : source(value) {
        phonecam::Channel::Callbacks callbacks{
            [this](const std::string &text) { received(text); },
            [this](const phonecam::AudioChunk &chunk, uint64_t pts) { sound(chunk, pts); },
            [this] { ended(); }};
        usb->setCallbacks(callbacks); wifi->setCallbacks(callbacks);
    }
    ~PhoneControls() { usb->setCallbacks({}); wifi->setCallbacks({}); }
    PhoneControls(const PhoneControls &) = delete;
    bool send(const std::string &command) {
        bool sent = usb->send(command) || wifi->send(command);
        blog(LOG_INFO, "[phonecam] Phone command \"%s\"%s", command.c_str(), sent ? "" : " not sent: no PhoneCam 0.6 phone is live");
        return sent;
    }
    std::map<std::string, std::string> snapshot() { std::lock_guard<std::mutex> guard(lock); return info; }
    // "status\nkey=value\n…" from the phone replaces the shown values; "hello…" starts a session.
    void received(const std::string &text) {
        auto lines = splitList(text, '\n');
        if (lines.empty()) return;
        if (lines[0] == "hello") {
            { std::lock_guard<std::mutex> guard(lock); info.clear(); clock.reset(); }
            blog(LOG_INFO, "[phonecam] The phone app talks to OBS: camera controls are in this source's Properties → Phone.");
            return;
        }
        if (lines[0] == "timings") { blog(LOG_INFO, "[phonecam] Phone start timings: %s", text.substr(8).c_str()); return; }
        if (lines[0] != "status") return;
        std::map<std::string, std::string> next;
        for (size_t i = 1; i < lines.size(); ++i) {
            auto eq = lines[i].find('=');
            if (eq != std::string::npos && eq > 0 && next.size() < 32) next[lines[i].substr(0, eq)] = lines[i].substr(eq + 1, 200);
        }
        bool changed;
        { std::lock_guard<std::mutex> guard(lock); changed = next != info; info = std::move(next); }
        if (changed) queueOnUi(source, refreshProperties);
    }
    void ended() {
        { std::lock_guard<std::mutex> guard(lock); info.clear(); clock.reset(); }
        audioUntil = 0; tracksChecked = false;
        queueOnUi(source, refreshProperties);
    }
    // A picture's OBS timestamp: arrival time, or the phone's own time mapped to this clock while the microphone
    // is on, so sound and picture line up.
    uint64_t stamp(uint64_t phonePts) {
        uint64_t now = os_gettime_ns();
        if (now >= audioUntil) return now;
        std::lock_guard<std::mutex> guard(lock);
        return clock.map(phonePts, now);
    }
    void sound(const phonecam::AudioChunk &chunk, uint64_t pts) {
        uint64_t now = os_gettime_ns(), at;
        { std::lock_guard<std::mutex> guard(lock); at = clock.map(pts, now); }
        audioUntil = now + 1'000'000'000ull;
        if (!tracksChecked.exchange(true)) {
            blog(LOG_INFO, "[phonecam] Receiving the phone's sound (%u Hz, %u channel%s).", chunk.rate, unsigned(chunk.channels), chunk.channels == 1 ? "" : "s");
            queueOnUi(source, assignAudioTracks);
        }
        struct obs_source_audio out{};
        out.data[0] = chunk.pcm;
        out.frames = uint32_t(chunk.bytes / (2u * chunk.channels));
        out.speakers = chunk.channels == 2 ? SPEAKERS_STEREO : SPEAKERS_MONO;
        out.format = AUDIO_FORMAT_16BIT;
        out.samples_per_sec = chunk.rate;
        out.timestamp = at;
        obs_source_output_audio(source, &out);
    }
};

bool phoneStop(obs_properties_t *, obs_property_t *, void *data) {
    if (data) static_cast<PhoneControls *>(data)->send("stop");
    return false;
}
// A Phone control changed in Properties: send it only when it differs from what the phone reports, so opening or
// refreshing Properties (which re-applies every value) never sends anything.
bool phoneChanged(void *data, obs_properties_t *, obs_property_t *property, obs_data_t *settings) {
    if (!data) return false;
    auto *phone = static_cast<PhoneControls *>(data);
    auto info = phone->snapshot();
    std::string name = obs_property_name(property);
    if (name == "phone_camera") {
        std::string value = obs_data_get_string(settings, "phone_camera");
        if (!value.empty() && value != info["camera"]) phone->send("camera " + value);
    } else if (name == "phone_quality") {
        std::string value = obs_data_get_string(settings, "phone_quality");
        if (!value.empty() && value != info["quality"]) phone->send("quality " + value);
    } else if (name == "phone_torch") {
        bool on = obs_data_get_bool(settings, "phone_torch");
        if (on != (info["torch"] == "on")) phone->send(on ? "torch on" : "torch off");
    } else if (name == "phone_mirror") {
        bool on = obs_data_get_bool(settings, "phone_mirror");
        if (on != (info["mirror"] == "on")) phone->send(on ? "mirror on" : "mirror off");
    }
    return false;  // the phone's status refreshes Properties once the change has happened
}
// "Phone" section: shown while a PhoneCam 0.6 phone is live, as compact as OBS allows (it lays controls out one
// per row): two drop-down lists, two tick boxes and Stop. Starting stays on the phone (owner's rule).
// UI thread (properties() runs there): the values are set to what the phone reports before OBS shows them.
void addPhoneSection(obs_properties_t *props, PhoneControls *phone) {
    if (!phone) return;
    auto info = phone->snapshot();
    if (info.empty()) return;
    obs_data_t *settings = obs_source_get_settings(phone->source);
    obs_data_set_string(settings, "phone_camera", info["camera"].c_str());
    obs_data_set_string(settings, "phone_quality", info["quality"].c_str());
    obs_data_set_bool(settings, "phone_torch", info["torch"] == "on");
    obs_data_set_bool(settings, "phone_mirror", info["mirror"] == "on");
    obs_data_release(settings);

    obs_properties_t *group = obs_properties_create();
    std::string line = info["mode"] == "screen" ? "Sharing the phone screen" : info["camera"] + " camera";
    if (!info["route"].empty()) line += " · " + info["route"];
    if (!info["battery"].empty()) line += " · battery " + info["battery"] + " %" + (info["charging"] == "yes" ? " (charging)" : "");
    if (info["mic"] == "on") line += " · microphone on";
    obs_properties_add_text(group, "phone_info", line.c_str(), OBS_TEXT_INFO);
    if (!info["warning"].empty()) {
        obs_property_t *warning = obs_properties_add_text(group, "phone_warning", info["warning"].c_str(), OBS_TEXT_INFO);
        obs_property_text_set_info_type(warning, OBS_TEXT_INFO_WARNING);
    }
    auto cameras = splitList(info["cameras"], ',');
    if (cameras.size() > 1 && info["mode"] != "screen") {
        obs_property_t *list = obs_properties_add_list(group, "phone_camera", "Camera", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
        for (const auto &camera : cameras) obs_property_list_add_string(list, camera.c_str(), camera.c_str());
        obs_property_set_modified_callback2(list, phoneChanged, phone);
    }
    auto qualities = splitList(info["qualities"], ',');
    if (!qualities.empty()) {
        obs_property_t *list = obs_properties_add_list(group, "phone_quality", "Quality", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
        for (const auto &quality : qualities) obs_property_list_add_string(list, quality.c_str(), quality.c_str());
        obs_property_set_modified_callback2(list, phoneChanged, phone);
    }
    if (info["torch"] == "on" || info["torch"] == "off")
        obs_property_set_modified_callback2(obs_properties_add_bool(group, "phone_torch", "Torch"), phoneChanged, phone);
    if (!info["mirror"].empty())
        obs_property_set_modified_callback2(obs_properties_add_bool(group, "phone_mirror", "Mirror"), phoneChanged, phone);
    obs_properties_add_button2(group, "phone_stop", "Stop live on the phone", phoneStop, phone);
    obs_properties_add_group(props, "phone", "Phone", OBS_GROUP_NORMAL, group);
}
// OBS hotkeys (Settings → Hotkeys) for each PhoneCam source.
void registerPhoneHotkeys(obs_source_t *source, PhoneControls *phone) {
    auto hotkey = [&](const char *name, const char *description, obs_hotkey_func func) {
        obs_hotkey_register_source(source, name, description, func, phone);
    };
    hotkey("phonecam.switch_camera", "PhoneCam: switch phone camera", [](void *data, obs_hotkey_id, obs_hotkey_t *, bool pressed) {
        if (pressed) static_cast<PhoneControls *>(data)->send("camera next");
    });
    hotkey("phonecam.torch", "PhoneCam: torch on/off", [](void *data, obs_hotkey_id, obs_hotkey_t *, bool pressed) {
        if (!pressed) return;
        auto *phone = static_cast<PhoneControls *>(data);
        phone->send(phone->snapshot()["torch"] == "on" ? "torch off" : "torch on");
    });
    hotkey("phonecam.stop", "PhoneCam: stop live on the phone", [](void *data, obs_hotkey_id, obs_hotkey_t *, bool pressed) {
        if (pressed) static_cast<PhoneControls *>(data)->send("stop");
    });
}
}
