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

## v0.1 release files and v0.2 session (8 November 2026, afternoon)

Release-signed files from `tools/release.py`. The user tapped every Start and Stop; nothing was started remotely. Times are the Mac's local time.

**v0.1.0-alpha.1 release files** (APK and plugin zip from commit a168158):

| Time | Observation | Source |
|---|---|---|
| 16:57:16 | Rear, Best: 2160×3840 H.265 60 Mb/s, hardware decoding. | OBS log |
| 16:57:16–16:57:27 | 188 frames dropped, about 24 a second from 4 to 11 s, while the user handled the phone; then **no drops** for the remaining 75 s. | Phone `PhoneCam` drop log |
| 16:58:42 | Stop: plugin said "Live ended: the phone stopped sending …" and returned to Ready 1 s later. | OBS log |

Cause of the drops, measured afterwards: the phone links at **USB 2.0** (`Device Speed = 2`, High Speed), and `adb exec-out` carried 150 MiB in 8.4–9.8 s, about **17 MB/s (≈140 Mb/s)**. 4K Best averages 60 Mb/s but the variable-bitrate encoder bursts when the camera moves. The 2-frame phone queue (66 ms) overflowed, which discards frames until the next keyframe, and the immediate keyframe request (the largest frame type) overloaded the link again. Fixed in v0.2: constant bitrate where the encoder offers it, a 6-frame queue (~200 ms, filled only while the link is behind) and at most one early keyframe request a second.

**v0.2.0-alpha.1 release files** (built from `feat/v0.2-camera-controls` plus the fixes above), installed with `adb install -r` **over** the v0.1 release app: same signing key, no uninstall, data kept. This proves the update path for users.

| Time | Observation | Source |
|---|---|---|
| 17:13–17:14 | Three Starts failed with "different PCAM versions": OBS was still running the old v0.1 plugin because it had not been restarted (operator error: the check for a running OBS was wrong). The v0.2 app worked as designed. | OBS log |
| 17:17:22 | Encoder `c2.mtk.hevc.encoder`, 60 Mb/s, **constant** bitrate (observed). Rear Best 2160×3840, hardware decoding. | Phone log, OBS log |
| 17:18:40 | 1080×1920 H.265 20 Mb/s constant bitrate, hardware decoding (the user tested the front camera in this session). | Phone log, OBS log |
| 17:17–17:21 | User report: **no dropped frames**; rear and front cameras, controls and Mirror worked ("everything works fine … all things"). | User report |
| 17:21:59 | After **Reset Transform** on the scene item, the plugin's automatic fit set "Scale to inner bounds" at the 1920×1080 canvas; the user confirmed the whole picture was visible. | OBS log, user report |
| after 17:22 | Hiding the source with the eye button stopped live as intended, but the phone said "Broken pipe". Reworded in v0.2: "OBS stopped receiving: the PhoneCam source was hidden …". | User report |

Framing finding: the scene item had been moved and scaled by hand earlier (position −1230, −1050; scale 1.028; no bounding box), so OBS showed only the middle of the 4K portrait picture. Automatic fitting deliberately skips items the user has adjusted; v0.2 adds a **Fit to canvas now** button in the source properties for this case.

Not established in this session: Smooth 60 fps (no 60 fps session appears in the logs), each control individually observed by the operator (user-reported as a whole), Mac-side measured fps, long runs, optical latency. Laptop battery drained quickly during USB sessions; the phone also charges from the laptop over USB.

## Still pending

~5-minute and two-hour runs with recorded frame/drop counts; front camera (Camera 1), deferred by the user.
