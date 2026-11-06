# Vivo V2437 processing comparison status

Collected 6 November 2026 at 00:59 UTC after the user reported FAST and HIGH_QUALITY takes. Source collection: `benchmarks/raw/20261106T005954Z-184992`. The collection and subsequent private app-directory listing contained six sessions in total; both HEVC recordings requested FAST and observed FAST. There is no recorded HIGH_QUALITY request yet. Do not infer a device processing failure from a mode that was not requested.

## Latest timed FAST take

Session `941da007-5945-4ecd-9197-c7b72d25b1a2`, rear camera 0, HEVC 1920×1080 at requested 10 Mb/s, fixed 30 fps, FAST sharpening/noise reduction. It finalized on the duration timer and fully decoded with the original timebase without errors or warnings.

- 454 decoded frames, matching the sidecar.
- MP4 duration 15.133078 seconds; average 30.000507 fps.
- Maximum encoded PTS gap 33.425 ms; no non-monotonic PTS.
- Zero processing mismatches and zero unconfirmed frames.
- Actual last camera metadata confirms sharpening mode 1 and noise mode 1 (FAST).
- Local structured result: `benchmarks/results/vivo-v2437-processing-comparison.json`.
- A middle frame still shows the Mac display and surrounding room, so a fixed physical detail scene is not yet established.

**Decision: this HEVC FAST capture passes the short capture-path checks. The FAST/HIGH_QUALITY comparison is inconclusive because the HIGH_QUALITY sample is missing.** Stock visual quality, sustained stability, and optical latency remain unqualified.

## Next action

The assistant reopened the idle probe, confirmed Camera 0 / 1080p / H.265 / 10 Mb/s, enabled the HIGH_QUALITY checkbox through ADB UI input, and read back `checked=true`. No Record tap was sent. The following user-initiated recording was then collected and verified below.

## HIGH_QUALITY take verified

Collected 6 November 2026 at 01:03 UTC from `benchmarks/raw/20261106T010302Z-ba27df`. Session `a47d51e8-b4c0-4a21-87f5-a210233345d9`; profile `a73f60085c483b01b5fa110e00f9dcb642718f18cbbbe6eb1e8498ba69f79c04`. Local structured result: `benchmarks/results/vivo-v2437-hq-capture-check.json`.

| Check | FAST 941da007 | HIGH_QUALITY a47d51e8 |
|---|---|---|
| Camera / dimensions / codec | Rear 0 / 1920×1080 / HEVC | Rear 0 / 1920×1080 / HEVC |
| Requested nominal bitrate | 10 Mb/s | 10 Mb/s |
| Actual MP4 duration | 15.133078 s | 15.199489 s |
| Decoded frames | 454 | 456 |
| Actual average MP4 fps | 30.000507 | 30.001009 |
| Requested/observed sharpening mode | 1 / 1 (FAST) | 2 / 2 (HIGH_QUALITY) |
| Requested/observed noise-reduction mode | 1 / 1 (FAST) | 2 / 2 (HIGH_QUALITY) |
| Processing mismatch / unconfirmed counters | 0 / 0 | 0 / 0 |
| Largest encoded PTS gap | 33.425 ms | 33.425 ms |
| Non-monotonic PTS | 0 | 0 |
| Timed finalization | Successful | Successful |
| Full timestamp-preserving decode | No reported errors | No reported errors |

The HQ stream is HEVC Main, BT.709 SDR, with no B-frames reported. Actual video bitrate is 11,807,257 bit/s despite the nominal 10-Mb/s request. Maximum sampled Android thermal status was 1; battery temperature samples were 30.0°C and 29.9°C. These short observations do not establish long-session thermal or memory behavior.

**Processing and short-run frame-rate check: pass.** The camera accepted and reported both requested HIGH_QUALITY modes while output remained approximately 30 fps. This demonstrates access to these standard controls, not access to Vivo's complete stock-camera processing.

**Visual-quality comparison: still inconclusive.** Representative FAST and HQ frames show the laptop from different angles with display reflections; the stock reference also has different framing. Exposure/scene conditions differ. No improvement score, stock parity, PSNR/SSIM result, or optical latency claim can be inferred from these takes.

Next establish a fixed physical detail target (book page, printed text or product label) under steady room lighting and support the phone in landscape. Keep the phone/subject/light positions fixed while recording a new stock reference and probe take. Match field of view, resolution and frame rate before human quality scoring. Do not expand to USB/OBS until the quality gate has real evidence.
