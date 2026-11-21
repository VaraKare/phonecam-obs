#pragma once
#include <cstring>
#include <mutex>
// OBS-side helpers shared by the macOS and Windows plugins (each plugin is one translation unit):
// fitting the picture to the canvas, Wireless debugging pairing, and the OBS log sink.
#include <obs-module.h>
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
}
