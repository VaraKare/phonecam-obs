# USB live experiment

This is a research implementation, not a completed feasibility gate. On 6 November 2026 the user elected to proceed while the controlled stock-quality comparison remained inconclusive. Synthetic decode tests and successful builds do not establish phone compatibility, optical latency, or sustained stability.

## Build and connect

Reference environment: Apple M1, macOS 26.6.2, OBS **33.0.1** (initially 33.0.0, now 33.0.2; the plugin builds against the pinned 33.0.1 SDK and loads in OBS 33.0 and newer), Vivo V2437 / Android 16. Android API minimum remains 29. Other phones and profiles are unverified. Native builds currently require Apple Silicon, Apple command-line tools and OBS at `/Applications/OBS.app`; full Xcode and CMake are unnecessary. Python 3.10+ and ADB must be on PATH. These are developer-installed research builds, not a clean-Mac alpha installer.

Run from the repository (prefix shell commands with `rtk` on this host):

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 -m unittest discover -s tools/tests
python3 tools/build_native.py --install
python3 tools/test_native.py
python3 tools/phonecam.py install
```

1. Open **PhoneCam** on the connected/unlocked phone. Accept camera permission if needed. Opening the app does not start capture.
2. Open OBS; if it was open during plugin installation, restart it after saving your work. Add a source using **Sources → + → PhoneCam (phone camera)** (the source id is unchanged, so older scenes keep it). Keep that source visible. Its **Properties → Status** line shows what it is waiting for.
3. Pairing and forwarding are automatic (since 0.1.0-alpha.1): while visible, the source finds `adb`, lists authorized phones, reads the connection key with `adb shell content query --uri content://dev.phonecam.obs.pairing/token` (the app answers only Android's shell user, uid 2000), creates `adb forward tcp:0 localabstract:phonecam-live-v1`, and retries every second. A refused connection means the forward is gone (unplug/USB reset), so it re-detects; a connection closed before any header means no phone session yet. The key is re-read every 15 seconds while idle, so a reinstalled app re-pairs. The forward is removed when the source is hidden or removed. `tools/live.py prepare` remains only for the command-line receiver.
4. On the phone, choose **Rear** or **Front** and a **Quality** (Best, High, Balanced, or Custom from Settings). The app scans the cameras automatically when it opens and offers only advertised combinations; none is verified until a capture succeeds.
5. Hold the phone any way up. Tap **Start live to OBS** yourself. The banner turns orange while waiting (up to 30 seconds) and red **● LIVE** once frames flow; the optional phone preview appears under it. Keep the app visible. The camera opens only after authentication. Turning the phone keeps the session running: each frame carries the clockwise rotation, measured from gravity (works with rotation lock on), and the Mac turns the picture upright in hardware. Turning to portrait changes the OBS source to a tall shape; set the source's **Transform → Edit Transform → Bounding Box Type** to *Scale to inner bounds* so it always fits. The same button stops the session.
6. OBS should show the live image. If it exceeds the canvas, right-click the source → **Transform → Fit to Screen**. The app shows encoded-frame/drop counts. OBS's log should contain `[phonecam] Live: W×H, hardware decoding.`
7. Tap **Stop live** on the phone. Verify OBS clears. Hiding/deleting the source or losing the connection stops the session; rotating does not. Leaving the app or locking the phone keeps live running with a notification (background live, v0.5) unless it is turned off in Settings; then it stops the session. Swiping PhoneCam away from recent apps stops it. Restart with another explicit phone Start. There is no automatic camera restart or automatic recording.

For a decoder-only diagnostic, hide/remove the OBS source first (one receiver per session), run `python3 tools/live.py receive --frames 300`, then tap Start USB live. The command prints bounded aggregate metadata and saves no media. A successful result verifies a short hardware-decoded connection, not glass-to-preview latency.

## Transport and receiver

`Camera2 → hardware MediaCodec surface encoder → bounded encoded queue → Android abstract local socket → ADB → Mac loopback TCP → required hardware VideoToolbox decode → raw NV12 → OBS async video`.

No microphone, Wi-Fi listener, media muxer, or disk media queue is added. The only service is the background-live foreground service (v0.5), which runs only during a session the user started. The token file and host connection settings are separate from media; see [data handling](DATA.md). Software decoding is deliberately rejected.

The two-frame queue has an 8 MiB byte ceiling and 4 MiB maximum individual payload. Overflow discards queued dependencies and requests a keyframe. A fresh keyframe can restart decoding; dependent frames after a sequence gap are ignored until one arrives. A blocked phone write is cancelled after a 750 ms deadline checked every 250 ms. Camera startup is bounded to 15 seconds, receiver packet inactivity to 3 seconds after initial startup, and an encoding stall to approximately 5–7 seconds. These are recovery limits, not promised streaming latency.

The receiver submits one access unit at a time and waits for hardware completion, bounding in-flight decode. It deliberately rejects buffered/no-output decode, malformed packets, reordered timestamps, mismatched parameter dimensions and mislabeled keyframes. OBS uses its standard RAM-copy source API, not a zero-copy path. It disables the async source's optional buffering and uses the Mac monotonic arrival clock for display. Phone timestamps are carried; there is no cross-clock latency subtraction, clock synchronization or audio yet.

## Wire format v2

The receiver first writes 64 lowercase hex token bytes plus LF. The phone allows up to five wrong keys per Start (stale keys after reinstall, stray local connections) before ending the session. Server authentication occurs before camera open. All following integers are unsigned, network byte order. Header size is exactly 32 bytes:

| Offset | Size | Meaning |
|---|---|---|
| 0 | 4 | ASCII `PCAM` |
| 4 | 2 | Version 3 since 0.2.0 (version 2 in 0.1.0). A different version is rejected with a clear app/plugin mismatch error |
| 6 | 2 | Type: 1 configuration, 2 frame, 3 audio (0.6), 4 control text (0.6, both directions) |
| 8 | 4 | Payload byte length, 1..4 MiB (configuration 20..65536; audio 9..65536; control 1..4096) |
| 12 | 4 | Flags: bit 0 keyframe; bits 1–2 clockwise quarter turns to display the frame upright; bit 3 mirror the upright picture horizontally ("Mirror in OBS"); higher bits rejected. Configuration flags must be 0 |
| 16 | 8 | Monotonic frame identity, starting at 0; gaps expose dropped frames |
| 24 | 8 | Encoder PTS in phone microseconds; increasing, signed-64-bit representable |

Configuration is sent once before frames, with header flags/sequence/PTS zero. Payload starts with four u32 values: codec (1 AVC, 2 HEVC), width, height, initial rotation (0/90/180/270; frames carry the live value). Remaining bytes are Annex-B parameter sets. Dimensions must be even and within 128..4096, and agree with encoded parameters. Frames contain one Annex-B access unit; the receiver converts start codes to four-byte lengths for VideoToolbox. Since 0.4.1 a second configuration on the same connection (camera or quality switch) makes the receiver rebuild its decoder. New connections reset sequence/decoder state.

### Two-way link (0.6)

- **Hello:** a 0.6 plugin sends one control message right after the key line: `hello`, then `features=control,audio,screen` (one `key=value` per line, UTF-8, at most 4096 bytes). A 0.6 phone reads it within 500 ms and answers with its own `hello` (`features=…`, `app=<version>`). Older phones never read it; with an older plugin a 0.6 phone gets no hello and sends only types 1 and 2.
- **Phone → computer control:** `status` with `key=value` lines (`camera`, `cameras`, `quality`, `qualities`, `torch` on/off/none, `mirror`, `route`, `battery`, `charging`, `mic`, `mode=screen`, `warning`) whenever something changes; and `timings` once per session (tap → OBS connected → camera open → first frame), written to the OBS log.
- **Goodbye (0.7):** when the user stops on purpose (the phone's Stop, its notification or small window, or OBS's Stop), the phone sends `bye` with `reason=stopped` just before closing. OBS can then stop its own recording if the user ticked that option. A lost cable or Wi-Fi sends nothing, so a hiccup never stops a recording.
- **Computer → phone control:** one command per message: `camera next`, `quality <Best|High|Balanced|Smooth>`, `torch on|off`, `mirror on|off`, `stop`. Unknown commands are ignored. OBS cannot start live: only the phone's record button does.
- **Audio (type 3):** flags 0; sequence counts audio chunks; PTS is the first sample's time in phone microseconds on the same clock as the video frames. Body: u32 sample rate (8000..96000), u16 channels (1–2), u16 format (1 = signed 16-bit little-endian), then the samples. The phone sends 10 ms chunks at 48 kHz mono, only to a 0.6 plugin and only when the user turned the microphone on. With an older plugin the phone refuses the microphone with a message instead of dropping it silently.
- **Timing in OBS:** without audio, pictures are stamped with their arrival time (lowest delay). While audio flows, pictures and sound are both stamped with the phone's own time mapped to the computer's clock by one offset: the smallest (arrival − phone time) seen, renewed every 30 s.

## Acceptance still required

- Actual phone image in the real OBS preview at the chosen settings.
- User Stop, background/lock, source hide/delete and reconnect checks.
- Sustained frame-rate, resource/thermal and queue measurements; then the two-hour/20-reconnect reliability gate.
- External high-frame-rate optical measurement, reporting p95 after warm-up. Target remains below 100 ms on the same quality-approved profile. This document contains no measured optical result.
- Controlled quality review remains unfinished. Do not label any profile Verified or claim near-stock quality.

The tests in `tools/test_native.py` synthesize AVC/HEVC packets and exercise real local VideoToolbox hardware decoding, sequence-gap recovery, malformed headers and keyframe mismatches. `native/protocol_test.cpp` runs under address/undefined-behavior sanitizers. OBS module loading is checked against the installed library. None of these substitute for the physical acceptance items above.

## Troubleshooting

- **Source missing:** verify OBS 33.0 or newer, rerun the native build, open OBS outside safe mode and restart it. The local plugin is ad-hoc signed, not a notarized release.
- **Black preview:** keep the source visible; read the source's Status line; choose settings before Start. Another receiver or OBS source may already own the one session.
- **Timeout/connection lost:** tap Stop if necessary, unlock/re-authorize the phone, and tap Start again; the source reconnects by itself. No source can bypass the explicit phone action.
- **Hardware decoder or unsupported-format error:** use a declared SDR 1080p profile. AVC and HEVC must each be qualified separately. No silent software fallback is used.
- **ADB port conflict (command-line receiver only):** the preparation tool refuses to replace another device/destination's forward. Inspect `adb forward --list`; explicitly remove the unwanted forward, then prepare again. The OBS source asks ADB for a free port, so it does not conflict.
- **Need to uninstall:** remove only `~/Library/Application Support/obs-studio/plugins/phonecam-usb.plugin`; remove `~/Library/Application Support/phonecam-obs/live.json` and the selected device's `tcp:27183` ADB forward. Phone benchmark evidence is untouched.
