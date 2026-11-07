# PhoneCam OBS

Read `/Users/owner/.codex/RTK.md` when present; prefix shell commands with `rtk` on that host.

This is a separate project from DropBeam. Do not change DropBeam or reuse its uncommitted work.

Current scope: Android 10+ capability scanning, explicitly initiated camera-quality recordings, and USB/native OBS feasibility. On 6 November 2026 the user explicitly elected to begin USB research while visual quality remains inconclusive. On 7 November 2026 the user set the product direction: the phone is only a live camera for OBS (no on-phone copy during live), with a phone preview, 4K/60 fps profiles where advertised, any orientation, optional microphone audio (off unless the user selects it), and wireless after USB is proven — first ADB Wireless debugging, native Wi-Fi only with separate approval. Versioned GitHub releases, Windows support (required, phased) and later features follow docs/ROADMAP.md; release only what a real phone and OBS have proven. Never mark a physical-device gate passed from a build, emulator, or synthetic fixture.

- Camera2 → MediaCodec surface input; hardware H.264/H.265 only. No Rust/JNI during camera research.
- No recording until the user presses Record; live capture requires Start USB live. No microphone unless the user turns audio on for that session (request RECORD_AUDIO only then; refuse rather than silently drop audio if denied). No network permission until native Wi-Fi is separately approved. No background capture, analytics, or automatic media cache. USB uses an authenticated Android abstract local socket forwarded by ADB.
- Runtime capability checks; an advertised combination is unverified until an actual capture succeeds. Unsupported configurations fail clearly rather than silently changing settings.
- Keep telemetry bounded. Release camera, codec, surfaces, threads, and muxer on stop, permission loss, errors, backgrounding, and destruction.
- Record requested and observed settings separately. Do not infer glass-to-OBS latency from encoder timestamps.
- Preserve failed research outputs with an incomplete marker. Never submit them as successful samples.
- Raw recordings and private blind-review mappings stay out of Git.
- Run `./gradlew testDebugUnitTest lintDebug assembleDebug` and `python3 -m unittest discover -s tools/tests` before a research code handoff.

See docs/PLAN.md and docs/PHASES.md for gates, and CONTRIBUTING.md for branches.
