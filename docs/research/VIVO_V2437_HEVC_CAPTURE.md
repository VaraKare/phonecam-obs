# Vivo V2437 first HEVC probe capture

Collected 6 November 2026 at 00:56 UTC after the user explicitly recorded using the selected HEVC profile. Source collection: `benchmarks/raw/20261106T005628Z-47511f`. Local structured result: `benchmarks/results/vivo-v2437-hevc-capture-check.json`.

Session `b13349f7-af15-4ac9-ba49-9e805fff53bc`, profile `b4bf07d20d970fca11298dc9dcf51e51513d4bcbd99b64f9a1bbf87b3c19d228`.

| Check | Result |
|---|---|
| Explicit request | Rear camera 0, 1920×1080, fixed 30 fps, HEVC, 10,000,000 bit/s, FAST processing |
| Encoder | `c2.mtk.hevc.encoder`, hardware-advertised |
| Successful finalization / stop | Yes / timed duration |
| Actual codec / profile / dimensions | HEVC / Main / 1920×1080 |
| MP4 duration / decoded frames | 15.099544 seconds / 453 |
| Actual average MP4 fps | 30.000905 |
| Actual video bitrate | 11,842,117 bit/s |
| Hardware output-format bitrate | 10,000,000 bit/s |
| Largest encoded PTS gap | 33.425 ms |
| Non-monotonic PTS | 0 |
| B-frames reported | 0 |
| Requested/observed sharpening and noise reduction | FAST / FAST |
| Processing mismatch / unconfirmed frames | 0 / 0 |
| Video and optical stabilization | Off, matching request |
| Color metadata | Limited-range BT.709 SDR |
| Full timestamp-preserving decode | Completed without errors or warnings |
| Short-run maximum Android thermal status | 0 |
| Battery temperature samples | 29.8°C at start and end |

Requested and observed codec, size, FPS, and processing agree. The actual variable video bitrate exceeds the nominal request; preserve measured bitrate when comparing codecs or applications. Thermal samples cover only this short recording and do not pass sustained stability requirements.

A representative middle frame was inspected. It shows an angled Mac display with reflections, rather than a controlled physical detail scene. Framing and actual bitrate differ from the stock reference. Do not assign a stock-quality score from this pair or run pixel-aligned metrics on it.

**Decision: HEVC capture-path smoke test passed for this specific profile.** Device support remains unverified; the camera-quality gate is inconclusive and optical latency has not been measured.

Next use a stable, well-lit printed page/book scene. Keep the phone position, lens, lighting, codec, resolution, bitrate and duration fixed. Record FAST first, wait for finalization, then enable HIGH_QUALITY and record again. Both takes must be user-initiated. Compare applied settings and frame timing before collecting the stock baseline and completing repeated-scene quality review.
