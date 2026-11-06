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

## Next phase steps

1. Start an explicit phone-to-OBS live session using [USB live setup](USB_LIVE.md), initially rear 1080p30 HEVC at 10 Mb/s with HIGH_QUALITY selected to match the recent capture experiment.
2. Confirm visible decoded video, hardware decode, Stop/background behavior, and a short sustained connection. Record physical results separately from synthetic tests.
3. Measure optical latency externally. Complete the controlled quality comparison before making any combined premium-quality/latency claim.
4. Advance to reliability/audio only after documenting the feasibility decision. Two-hour/reconnect, audio, broader-device and public-distribution gates remain outstanding.
