# First real Windows test (15 November 2026)

A borrowed Windows laptop, the vivo V2437 and the CI test build of the Windows plugin (CI run 43712010314, before the fixes below). The laptop's owner asked that nothing be installed and everything be removed afterwards, so the test ran from a portable kit: OBS 33.0.1 in portable mode, Google's platform-tools zip and the plugin in one Desktop folder, started from a PowerShell script that sent the PhoneCam log lines back to the Mac on the local network and deleted the folder when OBS closed. The final cleanup report said "Left behind: nothing"; the laptop's existing adb keys were left as they were.

## Machine

| | |
|---|---|
| Windows | 11 Home, build 26200 |
| CPU | AMD Ryzen 5 5600H |
| Graphics | AMD Radeon RX 5500M (OBS used it) and Radeon integrated graphics |
| Memory | 8 GB |
| Security software | Avast Antivirus and Windows Defender; Smart App Control off |
| Phone over USB | Windows' own drivers: "ADB Interface" worked without installing a vivo driver. The phone also showed a virtual CD drive (D:), which can be ignored |

## Results

| Check | Result |
|---|---|
| Plugin loads in OBS 33.0.1 x64 | Passed: the PhoneCam source appeared in the prepared scene |
| adb finds the phone | Passed only after a workaround (below) |
| Live over USB, 1080p (portrait, 1080×1920) | **Worked**: hardware decoding (D3D11), about 13 seconds without late frames; the owner saw the video stay on with a few dropped frames |
| Live over USB, 4K (portrait, 2160×3840) | Worked but too slow: every frame took 45–60 ms on the laptop (needs under 33 ms for 30 fps) |
| Rotation, front camera, mirror, long run, Wi-Fi | Not tested (time with the laptop ran out) |

This is a partial W5 result: the Windows plugin works on a real PC over USB at 1080p. It is not a pass: 4K is too slow, the session was short and several checks were not run.

## Problems found and what changed

1. **adb's server never started.** `adb start-server` hung with all three Windows USB methods and with an older adb (35.0.2): the background server process existed but never opened its port or wrote its log. Running the server in adb's foreground mode, `adb -L tcp:PORT server nodaemon`, worked at once. The likely cause is Avast interfering with adb's background start. **Change:** on Windows the plugin now checks whether an adb server is listening and, if not, starts one in foreground mode, tied to OBS with a job object so it ends when OBS closes (falls back to network discovery off if it does not start). Unproven on real hardware.
2. **Video stalled after a few seconds** during diagnosis. This was caused by the test kit, not the plugin: adb packet tracing (`ADB_TRACE`) slowed the USB link until the phone stopped. Environment variables set by a script run with `iex` stay in that PowerShell window, so the tracing carried over to the next run until it was cleared explicitly.
3. **4K decoding too slow.** The plugin's per-frame time covers decoding, copying the picture from the graphics card, rotation on the CPU and handing it to OBS. **Changes:** rotation now works in tiles (about twice as fast on an M1) and is split across threads; the copy from the graphics card reuses one buffer instead of allocating 12 MB per 4K frame; and when frames run late the plugin logs the average time of each step once a second, so the next Windows test shows which step is slow. If the copy or hand-over dominates, the next step is rotating on the GPU. Unproven on real hardware.

## Next Windows test

Use a new CI build with these changes and the [test-day checklist](../WINDOWS_TEST.md): 1080p for a few minutes, 4K (read the "PC frame timing" lines), rotation, front camera with Mirror in OBS, and reconnecting after unplugging.
