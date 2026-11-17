# Remediation log

The status of each finding in [FINDINGS.md](FINDINGS.md). "Fixed in code" means fixed and tested by automation. A fix that also needs a phone or Windows PC says so; physical checks are recorded in `docs/research/` when done.

## Stage 1 (16 November 2026)

| Finding | Status | What changed | Automated evidence | Still needs |
|---|---|---|---|---|
| AUDIT-001 Phone USB listener stays bound after Stop | Fixed in code | `LiveTransport.stopListening()` shuts the listener down and connects to it once, so a waiting `accept()` returns before the socket is closed. Used by Stop, the timeout and a Wi-Fi win. Accepted USB sockets are shut down before closing (020a) | Android build, lint, unit tests | **Phone check P1/P2** (TEST_AND_VALIDATION_PLAN §5): Start → Stop while waiting → Start must not say "Address already in use" |
| AUDIT-003 A Invalid UTF-8 phone name aborts OBS (macOS) | Fixed (A) | `cleanName` keeps only valid, printable UTF-8 and never cuts a character; macOS store conversion is nil-safe | New `wifi_test.cpp` cases; the scratch reproduction now saves without error | B (authenticate the name) waits for a protocol bump |
| AUDIT-004 Uncaught exceptions abort OBS | Fixed in code | Top-level catches in every plugin thread (USB worker, Wi-Fi server, Wireless-debugging pairing) and every OBS button/properties callback; `Server::run` never throws; `dropForward` never throws; one store lock for the whole process | New damaged-store test (Mac; Windows CI) exits cleanly and keeps the file | — |
| AUDIT-006 Wi-Fi listener always on; switch not applied | Fixed (A + most of B) | The switch starts/stops the server at once (both plugins). The server listens only while a phone is paired or a pairing window is open. Connections from outside private/link-local/loopback ranges are closed without a reply | `Listening before pairing: 0` asserted on Mac and Windows | mDNS still announces on every interface while listening (B, remaining part) |
| AUDIT-016 A Kit over plain HTTP; verbose reports | Fixed (A) | The PC refuses a `kit.zip` whose SHA-256 differs from the Mac's copy. Reports drop the phone serial, send only PhoneCam/error/warning/frame-statistics lines from the OBS log, and replace the user name and profile path. `serve` and the docs say "trusted network only" and to revoke USB debugging afterwards | `test_windows_kit.py` (29 tool tests) | B/C for untrusted networks |
| AUDIT-015 A Build paths in the released Mac binary | Fixed (A) | `build_native.py` strips debug symbols before signing and fails the build if `/Users/` remains | `nm`: 0 SO/OSO entries; signature valid | History rewrite (B) is the owner's decision |
| AUDIT-021a Lockout left the window advertised | Fixed | After 20 failures the server withdraws the `pair` flag and the card is hidden ("Pairing window closed…") | Code path | — |
| AUDIT-021e Store wiped on read failure; accept before save | Fixed | Unreadable files are reported and kept; a new pairing is saved before the phone is told | Damaged-store tests | — |
| AUDIT-021g / 022f Unlocked mDNS handle / `wifi` pointer | Fixed | mDNS is touched only by the server thread; the `wifi` pointer is guarded by `wifiLock` in both plugins | TSan run of the native suite: no reports | — |

**Runs after Stage 1** (macOS 27.2): Android unit tests 44/0 failed (1 skipped by design), lint 0 errors; tool tests 29 OK; Mac build and sanitizer unit tests pass; `test_native.py` 16 OK; the same suite under ASan+UBSan and TSan, no reports. Windows CI run 43946715250 (commit 72f5c6d): all jobs green, including the new damaged-store and "no port before pairing" checks, the replug test and Kotlin ↔ Windows Wi-Fi interop.

## Physical check, 17 November 2026 (Mac, vivo V2437)

The app built from 72f5c6d was installed, release-signed, over the published 0.3.0-alpha.5 build, keeping app data. The version string is unchanged.

| Result | What happened |
|---|---|
| Public Wi-Fi | Three pairing windows were opened; no connection from the phone reached the Mac. This is most likely the network's client isolation. It is not proven: the new local-network filter ignores outside addresses silently, so the two causes can't be told apart from the log. Follow-up: log ignored connections, rate-limited. |
| Phone hotspot (Mac joined it) | Pairing succeeded, then three Wi-Fi sessions in a row, at 1080×1920 with hardware decoding: Start → Stop → Start → Stop → Start, each connecting within about 2–6 s. The user reported good quality, seamless video and a few dropped frames. |
| AUDIT-001 | **Supported on hardware.** With no USB cable, every session is won by Wi-Fi. That is the path where the old code left the USB listener bound, and the next Start would most likely have failed with "Address already in use". Three consecutive Starts connected. The plain USB Stop-while-waiting case (TEST_AND_VALIDATION_PLAN P1) uses the same `stopListening()` and is still worth one explicit run. |

## Next (Stage 2, not started)

AUDIT-002 (hide blocks the OBS video thread), 005, 010, 011, 012, 013, 014, 017, in the order given in [PRIORITIZED_REMEDIATION_ROADMAP.md](PRIORITIZED_REMEDIATION_ROADMAP.md).
