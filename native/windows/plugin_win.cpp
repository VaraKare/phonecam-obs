// PhoneCam OBS source for Windows (test build, docs/WINDOWS.md). W1: registers the same source id and
// properties layout as the Mac plugin so scenes move between platforms; the phone connection follows in W2-W4.
#include <obs-module.h>

OBS_DECLARE_MODULE()
MODULE_EXPORT const char *obs_module_description(void) { return "Android phone camera over USB or Wi-Fi (Windows test build)"; }

namespace {
struct Source { obs_source_t *source; };
const char *name(void *) { return "PhoneCam (phone camera)"; }
void *create(obs_data_t *, obs_source_t *source) { return new Source{source}; }
void destroy(void *data) { delete static_cast<Source *>(data); }
obs_properties_t *properties(void *) {
    obs_properties_t *props = obs_properties_create();
    obs_properties_add_text(props, "status",
        "Windows test build: the phone connection is not available yet. See docs/WINDOWS.md for progress.", OBS_TEXT_INFO);
    return props;
}
}

MODULE_EXPORT bool obs_module_load(void) {
    obs_source_info info{};
    info.id = "phonecam_usb_v1";  // same id as on the Mac, so saved scenes keep their source
    info.type = OBS_SOURCE_TYPE_INPUT;
    info.output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_DO_NOT_DUPLICATE;
    info.get_name = name; info.create = create; info.destroy = destroy; info.get_properties = properties;
    info.icon_type = OBS_ICON_TYPE_CAMERA;
    obs_register_source(&info);
    return true;
}
