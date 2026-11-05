# Vivo V2437 first recording results

Collected 5 November 2026 at 18:27 UTC after the user enabled auto-rotate, held the app in landscape and explicitly recorded two clips. The earlier Record issue was the portrait-orientation preflight check; no camera session had started at that point.

## Evidence and actual configuration

Local collection: `benchmarks/raw/20261105T182758Z-3fa549`. Structured results: `benchmarks/results/vivo-v2437-first-recordings.json`. Raw media and private metadata remain gitignored.

Both clips used rear camera `0`, 1920×1080, fixed `[30,30]` FPS request, hardware-advertised `c2.mtk.avc.encoder`, H.264, FAST sharpening/noise reduction, stabilization off, no B-frames requested. The selected bitrate was **5,000,000 bit/s**, rather than the originally instructed 10,000,000 bit/s. Preserve this distinction in later comparisons.

| Check | Clip bd663208 | Clip 1528f512 |
|---|---|---|
| Finalized successfully | Yes | Yes |
| Stop reason | Timed duration | Timed duration |
| Actual MP4 dimensions / codec | 1920×1080 / H.264 | 1920×1080 / H.264 |
| MP4 duration | 15.133067 s | 15.099367 s |
| Decoded frame count | 454 | 453 |
| Actual average MP4 frame rate | 30.00053 fps | 30.00126 fps |
| Largest encoded PTS gap | 33.425 ms | 33.425 ms |
| Non-monotonic PTS | 0 | 0 |
| Processing mismatch / unconfirmed counters | 0 / 0 | 0 / 0 |
| Maximum reported Android thermal status | 0 | 0 |
| Color metadata | Limited-range BT.709 SDR | Limited-range BT.709 SDR |
| Full FFmpeg decode | Completed without reported errors | Completed without reported errors |

MP4 frame counts match encoder sidecars. ffprobe confirmed stream properties; FFmpeg decoded the complete files, and representative middle frames were inspected. Both show the Mac monitor. The sidecars' `well_lit_detail` label does not establish a controlled, well-lit benchmark scene.

The hardware output-format report lists a 6,000,000-bit/s bitrate/max-bitrate despite the 5,000,000-bit/s request. Observed encoded payload averaged approximately 6.58 and 6.14 Mb/s respectively. Record requested, hardware-reported and actual bitrate separately; do not assume the requested value was achieved.

## Decision

**Capture-path smoke test: pass for this specific 1080p30 H.264 profile.** This demonstrates successful recording/finalization, decodability and stable short-run frame timing. It does not qualify stock-camera visual similarity, long-session heat/memory, 4K, HEVC, or optical latency. Short-run thermal status and heap samples cannot pass the later stability gate.

**Camera-quality gate: inconclusive. Optical latency: not measured. Device support remains unverified.**

Next prepare a fixed, well-lit scene and record the stock Camera app baseline at 1080p30, rear 1× lens and normal SDR. Record a matched probe clip without moving the phone or changing lighting, and add a tuned direct-camera scrcpy baseline. Review quality before choosing the winning profile and completing all repeated scenes.
