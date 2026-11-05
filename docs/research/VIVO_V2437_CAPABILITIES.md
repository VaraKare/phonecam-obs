# Vivo V2437 capability scan

Collected 5 November 2026 at 18:24 UTC after the user enabled USB debugging, authorized the Mac, installed the probe, granted camera access and completed Scan camera capabilities.

Raw local evidence: `benchmarks/raw/20261105T182405Z-6254ff/capabilities.json`. Collection completed successfully. Raw device metadata remains gitignored.

| Observation | Scan result |
|---|---|
| Manufacturer / model | vivo / V2437 |
| Android / API | 16 / 36 |
| Enumerated cameras | Rear ID `0`, front ID `1` |
| Declared candidate profiles | 26 |
| Scanner errors | None |
| Hardware-advertised AVC encoder | `c2.mtk.avc.encoder`, surface input, declared bitrate range 1–100,000,000 bit/s |
| Hardware-advertised HEVC encoder | `c2.mtk.hevc.encoder`, surface input, declared bitrate range 1–100,000,000 bit/s |
| Rear camera target sizes | 1920×1080 and 3840×2160 at declared minimum frame duration 33,333,333 ns |
| Fixed 30-fps camera request range | `[30, 30]` available on both cameras |
| Processing | FAST and HIGH_QUALITY sharpening/noise-reduction modes advertised |
| Current support classification | Compatible, unverified |

These are advertised capabilities, not validated capture, visual quality, stable thermal performance, or optical latency. Camera session configuration and actual encoder output must be tested.

## First recording configuration

- Rear camera `0`, 1920×1080, fixed 30 fps.
- H.264 using `c2.mtk.avc.encoder`, 10,000,000 bit/s.
- FAST processing; HIGH_QUALITY checkbox off.
- Limited-mode checkbox off.
- Scene `well_lit_detail`; duration 15 seconds; landscape orientation.
- User initiates recording through the phone's Record benchmark button.

After collection, verify successful timed completion, MP4 playback and dimensions/codec/frame timing, plus requested versus observed processing. This initial recording establishes the capture path only; stock/scrcpy comparisons and the complete repeated-scene gate follow separately.
