# Prioritized remediation roadmap

Nothing here is approved yet. Each stage lists:
- **order:** the sequence of work;
- **dependencies:** what must land first;
- **acceptance criteria:** what "done" means;
- **tests:** what must pass.

Effort estimates assume the current codebase and test harnesses.

## Stage 1: Immediate blockers (before any wider distribution or the v0.4 release)

| Order | Work | Findings | Effort |
|---|---|---|---|
| 1 | Wake the waiting USB listener on Stop/timeout/Wi-Fi win; `shutdown` accepted sockets on close | 001, 020a | S |
| 2 | Top-level catch in every plugin thread and OBS callback; pairings cached in memory under one lock | 004, 021e | S–M |
| 3 | Valid-UTF-8 phone names and nil-safe conversion | 003 A | S |
| 4 | Apply the Wi-Fi switch immediately; listen only when paired or pairing; private interfaces only | 006 | S |
| 5 | Kit: zip hash check, slimmer reports, honest docs, "trusted network only" note (before the next borrowed-PC session) | 016 A | S |
| 6 | Strip the Mac release binary; decide on rewriting Git history | 015 | S (+M if rewritten) |

**Dependencies:** none; items can land in any order.

**Acceptance criteria:**
- Phone test P1/P2 pass (TEST_AND_VALIDATION_PLAN §5).
- An unwritable store and a hostile name no longer abort.
- Toggling Wi-Fi closes the port.
- The kit refuses a modified zip.
- `strings`/`nm` show no home path.

**Tests:** the new unit and native tests from §3–4 for items 1–4; the existing suites stay green; Windows CI green.

## Stage 2: Core reliability

| Order | Work | Findings | Effort |
|---|---|---|---|
| 1 | Shared `Link` hand-off on macOS and Windows (including the Wi-Fi server); `stop` checked between adb calls | 002 | M |
| 2 | Bound the Windows adb server start and release its lock while waiting | 010 | S |
| 3 | Restart adb only for server-level stalls; probe before ending a foreign adb | 012 | S |
| 4 | Silent pre-auth failures; per-connection handshakes; "busy" reply | 005 | M |
| 5 | Sticky "Last session ended" status; watchdog status cleared on recovery; "adb could not be run" message | 013 A, 022a/b | S |
| 6 | One-owner registry for several sources | 011 A | S |
| 7 | Phone: wider `configChanges` and a persistent stop reason; "Starting camera" / "Stopping…" states; guard `getCameraCharacteristics` | 014, 020b/c | S |
| 8 | macOS CI job (portable tests, then the full build with a pinned OBS) | 017 | M |

**Dependencies:**
- 2 and 3 touch `adb_win.cpp` / `adb_session.cpp`; do them after 1.
- 8 should land early so items 1–7 are covered on the Mac.

**Acceptance criteria:**
- Hide/show 50× with a sleeping fake adb, each under 50 ms.
- 20 replug cycles on the Mac and on a Windows PC, without restarting OBS.
- No `kill-server` when only one device hangs.
- A port scan during live changes nothing.
- macOS CI runs and fails on a deliberate compile error.

**Tests:** §4 native tests; hardware P3, P4, P7; Windows checklist sections 1–4.

## Stage 3: Performance and compatibility

| Order | Work | Findings | Effort |
|---|---|---|---|
| 1 | Receiver skips copy/rotate/output when the next frame is already waiting | 009 A | S |
| 2 | Separate timing for the D3D11 map vs copy; explicit adapter choice | 009 B | S |
| 3 | Decide on GPU rotation / texture output from real-PC timings | 009 C | L (if chosen) |
| 4 | Windows colour range/matrix from the decoded frame | 022c | S |
| 5 | Writer signalled on `offer()` (no 10 ms polling); rolling fps; applied-bitrate badge | 020e/f | S |
| 6 | Per-frame autorelease pool on macOS; 2-hour soak | 022d | S |
| 7 | Phone handshake overall deadline; private-address check; stored addresses tried first | 021c/d | S |

**Dependencies:** item 3 needs the Stage 2 Windows timings from a real PC.

**Acceptance criteria:**
- 4K30 on the test laptop with under 1% late frames, or a documented decision that 4K is "fast PCs only".
- Memory flat over 2 h.
- Correct levels for a full-range test pattern.

**Tests:** §7 network stress, §8 long-duration, Windows checklist §5.

## Stage 4: Maintainability and architecture

| Order | Work | Findings | Effort |
|---|---|---|---|
| 1 | Shared plugin core (status, frame output, properties) for `plugin.mm` / `plugin_win.cpp` | Arch. weakness 2 | M |
| 2 | One download-and-verify helper; tests for build/release scripts | 023g | S |
| 3 | Release script: refuse dirty/untagged trees; version from `git describe`; plugins log their version | 023a | S |
| 4 | Licence notices in the APK (Settings row) and the Windows artifact | 018 | S |
| 5 | Documentation pass and a single permissions/data page | 019 | S |
| 6 | CI hardening: actions pinned by SHA, Dependabot, job timeouts, Gradle dependency verification, run provenance check in the kit | 023d | S |
| 7 | Windows hardening flags; drop the `C:\platform-tools` search root | 023e, 022g | S |

**Dependencies:** item 1 is easiest after Stage 2 has unified the lifecycle code.

**Acceptance criteria:**
- The duplicated lines between the plugins are gone.
- The release refuses a dirty tree.
- Notices are visible in the app.
- Docs match the manifest.

## Stage 5: Future readiness (only when the features are scheduled)

| Work | Enables | Findings |
|---|---|---|
| Protocol version bump bundling: authenticated phone name (PCW2), mutual USB authentication over the PCW handshake, a close-reason byte | Security parity USB/Wi-Fi; better errors | 003 B, 007 B, 013 B |
| Module-level connection manager; phone ↔ source mapping | Multi-camera, several sources | 002 C, 011 C |
| Audio clock mapping (already planned in docs/PLAN.md) | Audio | — |
| Platform key stores (Keystore, Keychain, DPAPI) for long-term keys | Stronger at-rest protection | 021f |

**Acceptance criteria:** decided per feature; each needs matched app and plugin releases and an upgrade note.
