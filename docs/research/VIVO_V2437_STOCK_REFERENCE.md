# Vivo V2437 first stock-camera reference

Collected 6 November 2026 following the user's report that they had just recorded the requested stock-camera video. The recent-video query was restricted to DCIM videos added within the preceding 30 minutes; only one result was returned. Only that specific file was copied. The phone's original was preserved.

Local file: `benchmarks/raw/stock-20261106-004603/video_20261106_004603.mp4`. Technical report: `stock-report.json` in the same directory. Original media and metadata remain gitignored.

| Check | Result |
|---|---|
| Dimensions | 1920×1080 |
| Video codec / profile | HEVC / Main |
| Duration | 15.608944 seconds |
| Decoded frames | 469 |
| Average frame rate | 30.04687 fps |
| Video bitrate | 8,625,415 bit/s |
| B-frames reported | 0 |
| Color | Limited-range BT.709 SDR |
| Original PTS/DTS | Strictly increasing |
| Packet interval range | 33.277–33.389 ms |
| Full video decode preserving original timebase | Passed without reported errors |
| SHA-256 | `e9dd0da4feee200c7abe3c85e9a1efd529e449a3bb07a4b60260cebadd8c12c5` |

Default FFmpeg null-output timing initially produced one non-monotonic DTS warning. Inspection of every original video packet showed strictly increasing PTS and DTS; a video-only decode using the demuxer timebase completed without warnings. The original file was not remuxed or altered.

A representative frame shows the Mac screen. This is an exploratory monitor reference, not the full controlled four-scene benchmark. The older probe clips use AVC and were made at a different time/framing; do not treat them as a matched visual comparison.

Next request: rear camera `0`, 1920×1080 at 30 fps, HEVC at requested 10 Mb/s, FAST processing, 15-second probe capture. Keep framing and lighting fixed against the stock reference where possible. If the scene has changed, collect a new matched stock/probe pair before scoring. The complete quality and optical-latency gates remain pending.

## Follow-up capture and profile correction

The next collection, `benchmarks/raw/20261106T005129Z-e09570`, contained four probe sessions. The latest timed clip, `4b9173cb-6ccd-4bba-96b8-ad987448029b.mp4`, was successfully finalized and decoded, but still used the default AVC request at 5,000,000 bit/s instead of the instructed HEVC request at 10,000,000 bit/s.

It contains 454 frames over 15.133056 seconds, averaging 30.00055 fps; maximum encoded PTS gap 33.425 ms, no non-monotonic PTS, no processing mismatches/unconfirmed frames, and no errors during full timestamp-preserving decode. Actual video bitrate is 7,116,731 bit/s. A representative frame has wider/different framing from the stock reference; it cannot establish a matched visual comparison. Local structured results: `benchmarks/results/vivo-v2437-latest-probe-check.json`.

An additional older clip in that collection, `2cd22158-9a9a-4511-9cc6-18e0e89fc32d`, stopped when the app was backgrounded after roughly 2.4 seconds. Its 25.48-fps average and 133.237-ms maximum gap do not qualify; exclude it from the timed benchmark. The MP4's successful finalization flag does not imply benchmark completion.

To remove ambiguity in selecting the long profile list, the assistant opened the idle app's profile selector and selected **Camera 0 · 1920×1080 · H.265 · 10 Mb/s · unverified** through ADB UI input. The selected label was then read back and confirmed. No Record action was sent. The user must initiate the next recording on the phone. HEVC probe capture and the quality gate remain unverified until that new clip is collected.
