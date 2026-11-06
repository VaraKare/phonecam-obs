# Research software handoff

**Historical first handoff.** Subsequent physical captures and the user-authorized USB research phase supersede the original scope/next-step statements below. See [USB research handoff](USB_IMPLEMENTATION_STATUS.md) and [current phase tracker](../PHASES.md).

Date: 5 November 2026. Repository `phonecam-obs`, branch `research/camera-quality`.

## Implemented

- Android 10+ capability report: camera IDs, sensor orientation/timestamp source, lens/processing/stabilization modes, recording sizes/durations, FPS ranges, hardware-advertised surface-input AVC/HEVC encoders and bitrate/profile limits.
- Encoder-only Camera2 → MediaCodec → MP4 benchmark recorder; explicit user action; H.264/H.265, fixed bitrate choices, FAST/HIGH_QUALITY comparisons, limited-mode opt-in, 15/30/60-second duration, landscape and storage checks.
- Requested and observed settings, processing confirmation counters, encoded timing and bounded recent gap statistics, thermal status and bounded heap samples. Partial/error outputs remain labeled incomplete. No automatic quality or latency assertion.
- Cleanup on stop, error, startup/drain deadlines, rotation, backgrounding and activity destruction. No foreground service/background capture in this milestone.
- Only CAMERA permission; no microphone or network access. Backup and device-transfer exclusions for app data. Explicit local deletion and documented retention.
- Host doctor/install/streaming collection/baseline-command/blind-review/evaluation tooling. Tar traversal/links/duplicates/size overflow rejected; raw footage/private mappings ignored by Git. ffprobe validates actual media against sidecars. Independent repetitions and all required scenes/baselines are mandatory.
- Checked-in Gradle wrapper with distribution SHA-256, pinned build plugins, CI workflow, plan/branch/device/benchmark/data documentation, and license notices.

## Local verification

- `./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon`: passed.
- Android JVM tests: **4 passed**, covering bounded timing statistics, long-run counters and non-monotonic timestamps.
- `python3 -m unittest discover -s tools/tests -v`: **17 passed**, including the actual FFmpeg/ffprobe synthetic-container round trip. Synthetic test clips lived only in temporary directories and are not phone evidence.
- Android lint: **0 errors**, 20 warnings for hardcoded English research UI strings and a newer Gradle version. These are not suppressed; inspect `app/build/reports/lint-results-debug.html`. Localization is future work.
- APK signature verified; declared application permission is CAMERA only.
- `doctor`: reports zero authorized phones and `needs_device`; no device was installed or recorded.
- CI is configured but has not run on GitHub. No remote, pull request, public release, or release tag was created.

APK: `app/build/outputs/apk/debug/app-debug.apk`, approximately 2.6 MB, version `0.1.0-research`, package `dev.phonecam.obs`. This is a debug-signed research build, not public distribution.

SHA-256 at this handoff: `1d2f71ec8803be4eb7334164b3f7a327c1a4b3593e6a4bca2fc8e2d46b60d38a`.

SDK command-line tools, Android platform 36 and build tools 35.0.0 were installed at the standard user SDK location. Existing JDK 19 was used. No Android Studio, Xcode, emulator or phone app installation was performed. Tool downloads/caches are separate from the app and benchmark data.

## Pending physical evidence

The supplied Vivo model/OS still needs confirmation via ADB. Camera/session behavior, actual hardware processing, UX on device, visual quality, temperature, long-session memory, USB transport and optical latency are **unverified**. An APK build cannot close these gates.

Next: follow docs/ANDROID_SETUP.md, install, scan, record the baseline experiments, and complete docs/BENCHMARKS.md. Quality must pass before starting the USB/native OBS experiment. Later roadmap items intentionally have no speculative implementation.

## Review and repository state

The initial documentation foundation is on `main`; implementation remains on the research branch for review and physical validation. No physical gate has been merged as passed. The separate DropBeam repository and its ongoing changes were not edited.
