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

**Follow-up done (17 Nov):** ignored outside connections are now logged at most once a minute, without the address (`[phonecam] Ignored a Wi-Fi connection from outside the local network…`), on Mac and Windows. If a public network shows that line, the filter refused the phone; if it shows nothing, the network isolated the phone.

## Stage 2, partly done on 17 November 2026 (Windows laptop night)

Evidence: [docs/research/WINDOWS_THIRD_LAPTOP_TEST.md](../docs/research/WINDOWS_THIRD_LAPTOP_TEST.md).

| Finding | Status | What changed | Evidence |
|---|---|---|---|
| AUDIT-002 Hide blocks the video thread | Fixed (B for USB) | Mac gets the Windows Link hand-off; both wait at most 0.5 s; autoReceive checks stop between adb calls (293c81e) | Laptop: 7 hide/show cycles without freeze, OBS exit 3 s |
| AUDIT-005 LAN connections overwrite status | Fixed | Non-PhoneCam connections closed after 3 s with a log note, never a status (4bb280c) | Confirmed on the laptop before the fix; CI and Mac tests with empty and junk clients |
| AUDIT-006 B (remaining) | Fixed on hardware | Switch off closes the port (Stage 1) | Laptop check 11: `nc` refused |
| AUDIT-008 adb mDNS connect | Partly | No Wireless-debugging search while a USB phone is unauthorized or offline (7871251) | Code path |
| AUDIT-022a Watchdog status stays | Fixed (Windows) | The replaced status returns when progress resumes (7871251) | Code path |
| New: worker threads blocked by properties refresh (Windows) | Fixed | All status updates go through an OBS UI task (17e7698, 0b53c03) | Laptop: pairing twice, 13-min run, 3 s exit |
| New: DNS-SD on the accepting thread | Fixed | Announcer thread (01aa7db) | Mac TSan clean; CI |
| New: first Start drops (decoder warm-up) | Fixed (phone) | 4 s writes in the first 5 s (80513ef) | Laptop: first Start held |

## AUDIT-015 follow-up (19 November 2026)

- **Released Mac plugin (v0.3.0-alpha.5):** its debug map held the build path, including the Mac's user name. Replaced on GitHub with the same binary run through `strip -S` and re-signed ad hoc. The machine code (`otool -t`) and the section layout are byte-identical, libobs loads it, and the user name and debug entries are gone. `SHA256SUMS.txt` was updated and the release notes say so. The local unstripped original is kept outside Git.
- **Git history:** checked again on all branches. The 11 Nov rewrite had already replaced the real values with stand-ins (`/Users/owner/`, made-up `192.168.1.x` addresses). There is no real user name, home address or personal e-mail. History is **not** rewritten again (owner's decision, 19 Nov): it would break existing clones for little gain.

## Stage 2, 20 November 2026 (after v0.4.0-alpha.1)

| Finding | Status | What changed | Evidence | Still needs |
|---|---|---|---|---|
| AUDIT-010 A Windows adb server start | Fixed in code | Process creation has a 5 s bound on a helper thread (an abandoned start has its process ended); the server lock is timed (2 s), and the port wait checks `stop` (b82ce8b) | Windows CI build and existing fake-adb tests | Hang test with a blocking fake `server nodaemon` (not written) |
| AUDIT-012 A adb restart from one device | Fixed | A device that doesn't answer is skipped with "The phone isn't answering over USB…"; only a server-level call that also times out counts as a stall (508d1d7). The old code did not actually restart adb here (the healthy `devices` call reset the count) but looped with no status | New test with a hanging second device: no `kill-server`, status shown; fails on the old code | — |
| AUDIT-013 A End reason overwritten | Fixed | Properties keep "Last session ended: …" until the next live (a959611) | Builds; phone + Mac 21 Nov: the line showed after a session ended | — |
| AUDIT-014 A+B Config changes end live | Fixed | `configChanges` covers dark mode, keyboard, font scale, density, locale; a stop reason that arrives after the screen is gone shows on the next screen (a959611) | Android build, lint; phone 21 Nov: dark mode switched from quick settings while live, live kept going | Leaving the app still ends live (v0.5 background live) |
| AUDIT-011 A Several sources | Fixed (A) | One PhoneCam source at a time uses the phone (USB and Wi-Fi); others say "Another PhoneCam source is using the phone…" in yellow and take over when it is hidden or removed (ae36df3) | Builds (Mac, Windows CI). A libobs test could not drive show/hide without a video thread. Mac OBS 21 Nov, two sources: the second waits with the message; hiding the first (keep-live off) hands the phone over; showing it again makes it wait; removing the other gives it back. Wording made exact (bf02ea9) | Windows OBS look. Two phones still go to the first found (B/C with multi-camera) |
| AUDIT-017 No macOS CI | Fixed | `mac-plugin` job on macos-latest (Apple Silicon): official OBS 33.0.1 (SHA-256 checked), `build_native.py` (sanitizer tests, plugin, OBS load) and `test_native.py`. Decode tests skip only if the VM has no hardware decoder (aa356e4) | First CI run | — |
| New: OBS crash on Forget paired phones | Fixed | A button's status update reloaded Properties inside the click (OBS runs a UI task at once on the UI thread), freeing the button being clicked. UI refreshes now hop off the UI thread first; Forget needs a second click within 5 s (f71cc5a) | Mac OBS 21 Nov: crashed twice before; after the fix one click asks again, no crash; pairing works | Windows OBS look |
| New: switching restarts the whole connection | Fixed | Camera or quality changes keep the connection; the receivers rebuild the decoder; older plugins fall back to a full restart (4e8e8c0) | New mid-stream switch test (Mac, Windows CI fixture); phone + Mac 20 Nov: 5 switches on one connection, no "Live ended" | Exact timing (the phone did not keep its log lines) |

## Next (Stage 2 remainder)

The Wi-Fi part of AUDIT-002 and AUDIT-008; then AUDIT-010's hang test and two-source/two-phone handling (AUDIT-011 B/C) with multi-camera.
