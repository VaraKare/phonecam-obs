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

The assistant reopened the idle probe, confirmed Camera 0 / 1080p / H.265 / 10 Mb/s, enabled the HIGH_QUALITY checkbox through ADB UI input, and read back `checked=true`. No Record tap was sent. The next user-initiated recording must retain the same codec/profile and capture observed sharpening/noise-reduction mode 2 before a HIGH_QUALITY comparison can be made.
