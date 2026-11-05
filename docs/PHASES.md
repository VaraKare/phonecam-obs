# Phase tracker

Update evidence and status together. A build or synthetic test cannot pass a physical gate.

| Milestone | Status | Evidence required |
|---|---|---|
| Repository and tooling | Software implemented and locally checked | APK, tests, lint; CI configured, not yet run remotely |
| Capability scanner | First physical scan succeeded on Vivo V2437 / Android 16; capture validation pending | Actual phone report collected; supported capture still required |
| Camera-quality research | Probe and review tooling implemented; experiment pending | Four scenes, three winning repeats, blind review, frame timing |
| USB to OBS feasibility | Blocked by quality gate | Same-profile optical p95 below 100 ms |
| Video reliability | Not started | Two hours, 20 reconnects, no accumulating resources |
| Audio | Not started | Within 33 ms for one hour |
| Broader device qualification | Not started | Vivo plus two manufacturers |
| Public alpha | Not started | Clean-Mac install and distribution tests |

Current gate decision: **inconclusive — first device scan collected, recording and comparison footage pending**. Do not build later milestones around an assumed pass.

Physical evidence: [Vivo V2437 capability scan](research/VIVO_V2437_CAPABILITIES.md). No quality or latency gate has passed.

## Current working folder

The repository moved on 5 November 2026 to `/Users/owner/Desktop/Projects/Camera Max Camera Mix`. Git history and the `research/camera-quality` branch are preserved.

## Next phase steps

Complete these in order, recording evidence before advancing:

1. **Confirm device and capabilities:** connect and authorize the Vivo, run the host doctor, install the probe, scan capabilities, and collect the actual model/OS/camera/encoder report.
2. **Prove recording:** make a timed landscape H.264 benchmark at a declared 1080p30 profile; check that the MP4 plays and the sidecar shows successful completion, observed processing, and frame timing. Test 4K30 only where supported.
3. **Run the quality experiment:** follow `docs/BENCHMARKS.md`, select a winning configuration, record the four scenes three times, and complete the matched stock/scrcpy blind review.
4. **Record the gate decision:** pass permits USB-to-OBS latency research; fail permits the single bounded tuning cycle; inconclusive requires the missing evidence. No later phase starts on an assumed pass.
