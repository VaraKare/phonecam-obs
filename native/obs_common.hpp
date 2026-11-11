#pragma once
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
}
