# USB/native OBS research handoff

Date: 6 November 2026. Branch: `research/usb-obs-latency`, based on camera research commit `332f455`. The user explicitly requested proceeding after the short HQ capture. The controlled stock-quality gate is still **inconclusive**; no physical gate has passed.

## Implemented

- Separate **Start USB live** action in the phone app; no media-file output. Benchmark recording remains separate. No additional Android permissions: CAMERA only, no audio/Internet/background service.
- Authenticated Android abstract local socket over ADB, versioned framing, codec configuration, sequence identities, PTS and keyframe flags. Bounded two-frame queue, 4 MiB payload limit, byte budget and keyframe recovery after overflow. Camera opens only following the user's Start and receiver authentication.
- Surface-input hardware AVC/HEVC capture using the selected profile, FAST/HQ request, SDR/BT.709, no B-frames requested, stabilization off. Startup/write/capture-stall deadlines; Stop, background, rotation and device/source disconnection cleanup. A late camera-open callback closes its device even after capture-worker shutdown.
- Native Apple Silicon OBS input source, VideoToolbox hardware decoder required and explicitly checked. Bounded packet parsing, codec-parameter/dimension checks, keyframe-content validation, monotonic frame order, GOP recovery and synchronous bounded decode. Raw NV12 supplied through OBS's RAM API with async buffering disabled. Mac arrival timestamps drive display; phone PTS is diagnostic. No zero-copy, optical-latency or clock-synchronization claim.
- Pinned/checksummed OBS 33.0.0/33.0.1 and SIMDe 0.8.2 headers fetched only into ignored build directories. Apple command-line-tool build, ad-hoc-signed local plugin, standalone decoder diagnostic, license texts and explicit uninstall instructions. No full Xcode/CMake installation.
- ADB preparation refuses to replace another device/destination's forwarding. Private token settings are written atomically with mode 0600; no token or serial appears in reports/logs.

## Checked locally

| Check | Result |
|---|---|
| Gradle unit tests, lint, debug APK build | Passed; 8 JVM tests |
| Android lint | 0 errors, 25 warnings (research UI strings/tool version notices) |
| Host research/setup tools | 23 tests passed |
| Native integration | 5 tests passed: real VideoToolbox hardware decode of synthetic 1080p AVC/HEVC, sequence-gap recovery, malformed headers, keyframe mismatch rejection |
| Native protocol parser | Address/undefined-behavior sanitizer run passed |
| Native OBS module registration | Loaded and registered against installed OBS library |
| Actual OBS startup | `phonecam-usb` listed in OBS's loaded modules |
| APK signing and permission inspection | Signature valid; CAMERA only |

Native checks initially used OBS 33.0.0. Opening OBS updated the installed application to **33.0.1**; the build now selects pinned matching headers and the native build/module/decode checks passed against 33.0.1 as well. Existing OBS needs restarting to load a subsequently rebuilt plugin; no sources/scenes or recording settings were modified.

APK: `app/build/outputs/apk/debug/app-debug.apk`, version `0.2.0-usb-research`, versionCode 2, package `dev.phonecam.obs`, 2,791,883 bytes. SHA-256: `65aaff5093bbf26b7b2bbd89cf8d2cf542dafef261a0562e0f014299db01182d`.

The plugin is installed in this user's OBS plugin folder. The phone initially disconnected during the attempted update, then reappeared authorized. The updated APK was successfully installed with `install -r`, the foreground activity opened without capture, and authenticated USB forwarding/token settings were prepared. The capability scan completed with 26 declared profiles; rear 1080p HEVC at 10 Mb/s and the HIGH_QUALITY checkbox were selected for the next manual Start. Previous benchmark clips were not deleted. No phone camera was started remotely, no live physical session was run, and no media was saved by these tests. Synthetic fixtures lived in temporary directories.

CI includes host tests, Android checks and the sanitizer wire-parser check; it has not run remotely. Native OBS/VideoToolbox verification currently runs locally, not in CI. There is no configured remote, PR, release tag, notarization or public alpha.

## Next physical step

Installation and forwarding are ready. Restart OBS for the current plugin, add **PhoneCam USB (Research)**, select the previously tested rear 1080p30 HEVC 10 Mb/s HQ settings, then let the user tap Start. Follow [USB live setup](../USB_LIVE.md).

Actual preview, stop/background/source lifecycle, sustained throughput, temperature/memory and optical p95 remain unmeasured. The initial build supports upright landscape only, one receiver, no audio, no reconnect-triggered camera restart and no phone preview. A different profile/phone is unverified. Complete the controlled quality comparison before making a combined quality/latency claim. The two-hour/20-reconnect, audio, manufacturer coverage and clean-Mac installation gates remain future work.
