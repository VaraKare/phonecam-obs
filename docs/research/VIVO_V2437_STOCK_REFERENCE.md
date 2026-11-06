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
