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

## Still pending

~5-minute and two-hour runs with recorded frame/drop counts; front camera (Camera 1), deferred by the user.
