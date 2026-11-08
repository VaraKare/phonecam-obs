# Vivo V2437 USB live — first physical sessions

Date: 7 November 2026. Branch `research/usb-obs-latency`. Phone app `0.2.0-usb-research` (versionCode 2), OBS 33.0.1, Apple M1. The user started each session with **Start USB live**; nothing was started remotely.

## Results

| Time (GMT) | Observation | Source |
|---|---|---|
| 12:40–12:52 | OBS stayed black. The app's camera was never opened. The phone was in landscape at display rotation 270°; with rear sensor orientation 90° the initial build computes 180° and refuses Start. The refusal message appears at the top of the scrolled app screen and was missed. | Camera service history, display rotation, capabilities scan |
| ~12:42 | The ADB forward disappeared while the phone stayed attached (likely a USB connection reset). Re-running `tools/live.py prepare` restored it. | OBS log: `No ADB forward`; empty `adb forward --list` |
| 12:53:03–12:53:19 | Camera 0 opened for ~16 s with no OBS image. Cause not yet established (the user's phone status message was not captured). | Camera service history |
| 12:54:29–12:54:40 | **First live image in OBS**: 1920×1080 NV12, VideoToolbox hardware decode confirmed; the user confirmed visible video. The session ended after ~10 s because the user rotated the phone; the initial build deliberately stops on rotation. | OBS log, user report |

Phone thermal status 0 after the session. Settings requested by instruction: rear camera 0, 1920×1080, H.265, 10 Mb/s, HIGH_QUALITY checked (the checkbox was observed checked; the codec is not logged by the receiver).

## What this establishes

- The end-to-end path (Camera2 → hardware encoder → ADB → VideoToolbox → OBS) produces a real image on this phone and Mac.
- It does **not** establish sustained stability, lifecycle behaviour, front-camera operation, frame rate, quality or optical latency.

## Defects found

1. Only one landscape direction is accepted, rotation ends the session, and the refusal is easy to miss. → Phase 1d (any orientation) and a status/LIVE indicator at the top of the screen.
2. The ADB forward is lost on a USB reset and needs a manual command. → The plugin should recreate the forward itself.

## User-reported lifecycle checks (7 November 2026)

The user reported performing Stop, background and source-hide checks with the expected results, and a ~1-minute session with zero queue drops. Counts were not transcribed. These are user reports, not a sustained-stability measurement.

## v0.1 preview session (8 November 2026)

Branch `feat/v0.1-easy-usb` (commit 98ab826). Debug app `0.1.0-alpha.1` (versionCode 4), plugin `0.1.0-alpha.1` installed for the user, OBS 33.0.1 restarted to load it. The user tapped every Start and Stop; nothing was started remotely.

| Time (GMT+5:30) | Observation | Source |
|---|---|---|
| 11:13:57 | Source shown in OBS. The plugin found adb, read the pairing key over the shell-only provider and created its own forward (`tcp:61169 → localabstract:phonecam-live-v1`) with no commands typed. Status "Ready". | OBS log, `adb forward --list` |
| 11:14:07 | Rear camera, Quality **Best**: 2160×3840 (4K, portrait) H.265 60 Mb/s, HIGH_QUALITY, phone preview on. OBS: hardware decoding. Phone showed ● LIVE, preview, ~29–30 fps. | OBS log, phone screen |
| 11:14–11:17 | 57 dropped frames, all within the first seconds; the counter stayed at 57 across 30 s of later samples while fps held at 30. Thermal label went Cool → Warm after ~2 minutes at 4K. | Phone screen samples |
| ~11:17–11:20 | The user rotated live to both landscapes and back to portrait; the user confirmed the OBS picture stayed upright each time. | User report |
| 11:20:59 | User tapped Stop. Phone: "Stopped by you", camera off. Plugin back to "Ready" 1 s later (its log said "USB disconnected", misleading wording). | OBS log, phone screen |
| 11:23:27 | Quality **High** in landscape: 1920×1080, hardware decoding. Ended 1.6 s later ("Stopped") when the phone left the app for an incoming call — expected: leaving the app stops the camera. | OBS log |

The user reported nothing unexpected. Session length ~7 minutes in all; the 4K run lasted ~6.5 minutes.

What this establishes: automatic pairing/forward with a real OBS session, 4K portrait live with hardware decode, live rotation upright in OBS (user-confirmed), the new phone UI (LIVE badge, preview, stats), Stop and backgrounding behaviour.

Not established: fps measured on the Mac side, the cause of the startup drops, 60 fps (the encoder is still configured for 30), multi-hour stability, front camera, optical latency.

## 60 fps on this phone (8 November 2026)

The capability scan (normal Camera2 sessions) offers at most `[30, 30]` fps on both cameras, and a 33.33 ms minimum frame duration at 4K and 1080p. So 60 fps is **not advertised** for normal capture; the app correctly offers 30 fps only.

`dumpsys media.camera` shows the rear camera has `CONSTRAINED_HIGH_SPEED_VIDEO` with 1920×1080 at `[30,120]` and `[120,120]` (batch 4), plus 1280×720 at 120 fps. A possible 1080p60 path is a constrained high-speed session at 120 fps with the encoder told to drop to 60 (`KEY_MAX_FPS_TO_ENCODER`). It is unverified and has trade-offs: 1080p only, exposure limited to 1/120 s (darker indoors), restricted processing controls, and vendor HALs often misbehave. The scanner now records `highSpeedVideo` per camera so other phones can be compared.

## Still pending

~5-minute and two-hour runs with recorded frame/drop counts; front camera (Camera 1), deferred by the user.
