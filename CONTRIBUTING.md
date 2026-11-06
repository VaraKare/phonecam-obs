# Contributing

Use short-lived branches from `main`, one purpose per pull request. Research questions and exit criteria belong in the PR before coding. CI is required; human hardware evidence is separate.

| Branch | Purpose |
|---|---|
| `research/camera-capabilities` | Camera and encoder enumeration |
| `research/camera-quality` | Capture and comparison experiments |
| `research/usb-obs-latency` | USB/native OBS feasibility; currently an explicit user-authorized research exception while quality remains inconclusive |
| `feat/<name>` | Production functionality after feasibility |
| `fix/<name>` | Focused corrections |
| `docs/<name>` | Evidence and decision records |

`main` is the buildable accepted foundation. No `develop` or release branch initially. Tag tested releases `v0.1.0-alpha.1` and later. Never issue a release tag merely because compilation succeeds.

If an experiment fails, submit its reproducible findings via a documentation PR; tag the experimental commit `experiment/<name>-failed` before deleting its branch. Merge working production code only after the applicable gate passes. Record **pass**, **fail**, or **inconclusive** explicitly. An inconclusive result does not authorize the next milestone.

Every change must maintain bounded memory/telemetry, explicit recording consent, hardware capability checks, full cleanup, clear errors, and truthful quality/latency claims. Do not commit raw media, device serials, signing secrets, or private review mappings. Local benchmark clips may contain personal footage; obtain consent before sharing.

On 6 November 2026 the user explicitly elected to proceed with USB research before completing the controlled quality comparison. That branch depends on the camera implementation and therefore starts at its research commit (`332f455`), rather than the docs-only main. Do not manufacture a quality-pass merge. See `docs/USB_LIVE.md` and `docs/PHASES.md`; acceptance gates and verified-profile requirements remain unchanged.
