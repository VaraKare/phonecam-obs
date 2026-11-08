# Phase tracker

Update evidence and status together. A build or synthetic test cannot pass a physical gate.

| Milestone | Status | Evidence required |
|---|---|---|
| Repository and tooling | Software implemented and locally checked | APK, tests, lint; CI configured, not yet run remotely |
| Capability scanner | Physical scan and rear-camera 1080p30 AVC and HEVC captures succeeded on Vivo V2437 / Android 16 | Raw reports and decoded recordings collected; other profiles remain unverified |
| Camera-quality research | HEVC FAST and HIGH_QUALITY processing confirmed at approximately 30 fps; controlled stock-quality comparison pending | Four scenes, three winning repeats, blind review, frame timing |
| USB to OBS feasibility | Prototype implemented; physical live/optical test pending, quality remains inconclusive | Same-profile optical p95 below 100 ms |
| Video reliability | Not started | Two hours, 20 reconnects, no accumulating resources |
| Audio | Not started | Within 33 ms for one hour |
| Broader device qualification | Not started | Vivo plus two manufacturers |
| Public alpha | Not started | Clean-Mac install and distribution tests |

Current gate decision: **inconclusive — AVC/HEVC capture and requested HIGH_QUALITY processing work in short tests; stock/probe framing and scene conditions are not matched, so visual quality remains unqualified**. An interrupted short clip is excluded. The user elected on 6 November 2026 to begin USB/OBS research despite this unresolved comparison. This is an explicit sequencing exception, not a gate pass. See [USB live](USB_LIVE.md) and the [implementation handoff](research/USB_IMPLEMENTATION_STATUS.md).

Physical evidence: [Vivo V2437 capability scan](research/VIVO_V2437_CAPABILITIES.md). No quality or latency gate has passed.

Capture evidence: [First recording results](research/VIVO_V2437_FIRST_RECORDINGS.md). The actual initial profile requested 5 Mb/s, not the planned 10 Mb/s.

Stock reference: [First stock-camera reference](research/VIVO_V2437_STOCK_REFERENCE.md). Stock uses HEVC; next capture a matching 1080p30 HEVC probe clip before drawing visual conclusions.

HEVC capture evidence: [First HEVC probe recording](research/VIVO_V2437_HEVC_CAPTURE.md). Next collect FAST and HIGH_QUALITY clips of the same fixed, well-lit physical detail scene, followed by a matched stock baseline. This qualifies neither visual quality nor latency yet.

Processing comparison: [FAST and HIGH_QUALITY settings confirmed at 30 fps](research/VIVO_V2437_PROCESSING_COMPARISON.md). Next establish a fixed physical target before recording the matched stock/probe pair.

## Current working folder

The repository moved on 5 November 2026 to `/Users/owner/Desktop/Projects/Camera Max Camera Mix`. Git history and the `research/camera-quality` branch are preserved. USB work is on `research/usb-obs-latency`, branched from the camera research implementation; no quality-pass merge to main has been made.

## Product direction (7 November 2026)

The user chose: phone as live camera only, USB first then ADB Wireless debugging, phone preview, 4K/60 fps, any orientation, and optional audio. See [plan](PLAN.md#product-direction--7-november-2026). Physical USB proof comes first.

| Milestone | Status | Evidence required |
|---|---|---|
| Physical USB live proof | **First real OBS image** (rear 1080p, hardware decode); user reported Stop/background/hide checks and a ~1-minute zero-drop run; sustained run and front camera (deferred) pending — see [first sessions](research/VIVO_V2437_USB_LIVE.md) | Real OBS image, lifecycle checks, ~5-minute run, front camera |
| Any orientation, LIVE banner, phone preview (wire v2) | **Physically checked 8 Nov 2026**: 4K portrait live with preview; user confirmed upright in OBS through both landscapes and back ([v0.1 session](research/VIVO_V2437_USB_LIVE.md#v01-preview-session-8-november-2026)) | Portrait and both landscapes upright in OBS while rotating live; preview visible |
| 60 fps, best-quality default, automatic reconnection | Best-quality default and automatic pairing/forward physically working (8 Nov). The vivo advertises 30 fps for normal capture; experimental 1080p60 via 120 fps high-speed capture implemented on `feat/v0.2-camera-controls`, physical check pending | Measured fps within 1%; no manual prepare after USB reset |
| Camera controls (v0.2): zoom, tap focus, brightness, white balance, torch, mirror in OBS (wire v3) | Implemented on `feat/v0.2-camera-controls`; unit and synthetic mirror tests pass; physical check pending | Each control visibly changes the OBS picture while live; mirror correct in OBS |
| Optional audio | Not started | Permission only when selected; sync within 33 ms |
| ADB Wireless debugging | Not started | Wi-Fi fps/drops/stability compared with USB |

## Next phase steps

1. Start an explicit phone-to-OBS live session using [USB live setup](USB_LIVE.md), initially rear 1080p30 HEVC at 10 Mb/s with HIGH_QUALITY selected to match the recent capture experiment.
2. Confirm visible decoded video, hardware decode, Stop/background behavior, and a short sustained connection. Record physical results separately from synthetic tests.
3. Measure optical latency externally. Complete the controlled quality comparison before making any combined premium-quality/latency claim.
4. Advance to reliability/audio only after documenting the feasibility decision. Two-hour/reconnect, audio, broader-device and public-distribution gates remain outstanding.
