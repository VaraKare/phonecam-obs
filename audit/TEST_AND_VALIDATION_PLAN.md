# Test and validation plan

## 1. Executed in this audit (macOS, no phone attached)

| Test | Result | Notes |
|---|---|---|
| Android unit tests (`testDebugUnitTest`), forced re-run | **Pass**: 44 run, 0 failed, 1 skipped | The skip is `WifiInteropTest`, which only runs when `test_native.py` drives it |
| Android Lint (`lintDebug`) | **Pass**: 0 errors, 73 warnings | Warning breakdown in REPOSITORY_COVERAGE.md |
| Android debug build (`assembleDebug`) | **Pass** | |
| Tool tests (`tools/tests`) | **Pass**: 28 | |
| Mac native build + 4 sanitizer unit tests + OBS module load (`build_native.py`) | **Pass** | |
| Mac integration (`test_native.py`) | **Pass**: 15 | Includes the new replug test and Mac ↔ Kotlin Wi-Fi interop |
| Same 15 under ASan+UBSan | **Pass**, no reports | |
| Same 15 under TSan | **Pass**, no reports | Only the command-line receiver; the OBS plugin threads are not covered |
| Stream fuzzing, 600 cases, ASan+UBSan | **Pass**: 0 crashes/hangs/reports | 582 rejected cleanly, 18 decoded |
| Clang static analyzer | 6 nullability warnings, no memory/logic defects | |
| Reproduction: invalid UTF-8 phone name → `savePairings` | **Fails** (abort) | AUDIT-003 |
| Windows CI run 43907577041 (same code) | **Pass** | Windows build, decode, adb start, replug, Wi-Fi interop, OBS load |

Nothing above is a physical-device result.

## 2. Tests that failed

Only the deliberate reproduction (AUDIT-003). No existing test failed.

## 3. Missing tests: unit / JVM (no hardware)

| Area | Test | Finding |
|---|---|---|
| `LiveTransport` lifecycle | start → close with no client → start: no bind error; after a Wi-Fi-won session; `close()` returns after the accept thread exits | 001 |
| `LiveTransport` | claim race, watchdog expiry, 5 wrong keys, stalled-write unblocked | 001, 020a |
| `LiveStreamer` | phase sequence with fakes; release on every exit path | 020b |
| `Capabilities` | fps range choice, `limited` rule, high-speed selection | 020k |
| `PairingLink.parse` | rejects public IPv4 addresses | 021d |
| `WifiClient` | overall handshake deadline against a trickling server | 021c |
| Header layout | Kotlin `header()` bytes parsed by `protocol.hpp` (shared vector file) | Contract drift |

## 4. Missing tests: native / integration (Mac and Windows CI)

| Test | Finding |
|---|---|
| Hostile phone name (`FF FE C0`) during pairing: server survives, name sanitised | 003 |
| `--wifi` with an unwritable `PHONECAM_WIFI_STORE`: status line, no abort | 004 |
| 100 bare connects + one silent held connection; no status callback; real pairing within 2 s | 005 |
| Toggle `wifi_direct`: port closed; no listener without pairings | 006 |
| 20 failed pairings: mDNS `pair` flag and card cleared | 021a |
| Fake adb: two devices; only `-s B shell` hangs → no `kill-server`, A streams | 011, 012 |
| Fake adb: `server nodaemon` blocks → second run reports within 15 s | 010 |
| `--auto` with an older sender: final status keeps the mismatch reason | 013 |
| Receiver skips output when frames are waiting (40 ms injected delay) | 009 |
| Headless libobs: hide 50× with a sleeping fake adb; each hide < 50 ms | 002 |
| Make `test_native.py` fail when the receiver or ffmpeg is missing (instead of skipping everything) | 017 |
| macOS CI job (portable tests first, then the full build) | 017 |

## 5. Hardware tests: phone (Mac, any day)

| # | Procedure | Expected | Finding |
|---|---|---|---|
| P1 | USB unplugged. Start, wait 3 s, Stop, Start again; read the message | Today: probably "Address already in use". After the fix: waits normally | 001 |
| P2 | Wi-Fi-only (no cable): Start → live → Stop → Start → Stop → Start | Every Start connects | 001 |
| P3 | During live, switch dark mode (or enable battery saver) | Today: live may end silently. After the fix: stays live, or shows why it stopped | 014 |
| P4 | Start, Stop within 1 s, 10 times | Never "already in use"; controls always unlock | 001, 020b |
| P5 | Pair with a phone whose device name contains emoji or non-Latin characters | Pairs; the name shows correctly in OBS | 003 (valid UTF-8 path) |
| P6 | 2-hour 1080p portrait session on the Mac; watch OBS memory with `footprint` | Flat after warm-up | 022d |
| P7 | Hide/show the source 20 times while recording; check Stats → "Frames missed due to rendering lag" | No growth beyond a few frames | 002 |

## 6. Hardware tests: Windows PC

See [WINDOWS_PC_CHECKS_TOMORROW.md](WINDOWS_PC_CHECKS_TOMORROW.md) for exact steps for the borrowed laptop.

## 7. Network stress

| Test | How | Expected |
|---|---|---|
| Wi-Fi congestion | 1080p30 at 20 Mb/s while another device downloads | Bitrate adapts; no freeze over 1 s; recovers |
| Network change | Switch the phone between two Wi-Fi networks during live | Live ends with a clear reason; Start works again on the new network |
| Port contention | Two PhoneCam sources with Wi-Fi on | Second uses another port; one clear "another source" message (after 011 A) |
| LAN noise | Port-scan the computer during live (`nc -z` loop) | No status changes, no log spam (after 005) |

## 8. Long-duration tests

| Test | Duration | Pass criteria |
|---|---|---|
| USB 1080p30 | 2 h | Zero receiver errors; drops < 0.1%; OBS memory flat; phone temperature recorded |
| USB 4K30 (Mac) | 1 h | Same, plus thermal state from the phone status |
| Wi-Fi 1080p30 | 1 h | Same |
| Replug cycles | 20 cycles, USB, Mac and Windows | Video returns each time without restarting OBS |

## 9. Release verification matrix

Run with the exact release files, never a debug build (project rule).

| Check | macOS | Windows | Android |
|---|---|---|---|
| Built from a clean, tagged tree; versions match | ☐ | ☐ | ☐ |
| Binary stripped; no home paths (`strings`, `nm`) | ☐ | ☐ (no PDB path) | n/a |
| Licence notices present in the package | ☐ | ☐ | ☐ |
| Clean-machine install from README steps | ☐ | ☐ | ☐ |
| USB live 1080p 10 min, rear and front, rotation, mirror | ☐ | ☐ | — |
| Replug 5× | ☐ | ☐ | — |
| Stop-while-waiting then Start (P1) | — | — | ☐ |
| Wi-Fi pair (QR + typed code) and live | ☐ | ☐ | ☐ |
| Wi-Fi switch off closes the port | ☐ | ☐ | — |
| Hide/show 20× without rendering lag | ☐ | ☐ | — |
| SHA256SUMS match the uploaded files | ☐ | ☐ | ☐ |
