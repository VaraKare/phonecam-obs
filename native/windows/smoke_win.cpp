// Loads the plugin into the real OBS 33.0.1 libobs on Windows and checks the source registers.
#include <obs.h>
#include <cstdio>
#include <cstring>
int main(int argc, char **argv) {
    if (argc != 2 || !obs_startup("en-US", nullptr, nullptr)) return 2;
    obs_module_t *module = nullptr;
    if (obs_open_module(&module, argv[1], ".") != MODULE_SUCCESS || !obs_init_module(module)) return 3;
    bool found = false;
    const char *id;
    for (size_t i = 0; obs_enum_input_types(i, &id); ++i) if (!strcmp(id, "phonecam_usb_v1")) found = true;
    obs_shutdown();
    puts(found ? "PASS: OBS (Windows) loaded PhoneCam source" : "FAIL: source not registered");
    return found ? 0 : 1;
}
