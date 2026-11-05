# PhoneCam OBS

Read `/Users/owner/.codex/RTK.md` when present; prefix shell commands with `rtk` on that host.

This is a separate project from DropBeam. Do not change DropBeam or reuse its uncommitted work.

Current scope: Android 10+ capability scanning and explicitly initiated camera-quality recordings. USB/OBS, audio, GUI, Wi-Fi, virtual camera, iOS and installers are gated future milestones. Never mark a physical-device gate passed from a build, emulator, or synthetic fixture.

- Camera2 → MediaCodec surface input; hardware H.264/H.265 only. No Rust/JNI during camera research.
- No recording until the user presses Record. No microphone, network permission, background capture, analytics, or automatic media cache.
- Runtime capability checks; an advertised combination is unverified until an actual capture succeeds. Unsupported configurations fail clearly rather than silently changing settings.
- Keep telemetry bounded. Release camera, codec, surfaces, threads, and muxer on stop, permission loss, errors, backgrounding, and destruction.
- Record requested and observed settings separately. Do not infer glass-to-OBS latency from encoder timestamps.
- Preserve failed research outputs with an incomplete marker. Never submit them as successful samples.
- Raw recordings and private blind-review mappings stay out of Git.
- Run `./gradlew testDebugUnitTest lintDebug assembleDebug` and `python3 -m unittest discover -s tools/tests` before a research code handoff.

See docs/PLAN.md and docs/PHASES.md for gates, and CONTRIBUTING.md for branches.
