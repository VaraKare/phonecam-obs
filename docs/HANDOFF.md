# Hand-off: where PhoneCam OBS stands and what comes next

For a new chat or another computer: read this file, then [AGENTS.md](../AGENTS.md) (rules) and [audit/REMEDIATION_LOG.md](../audit/REMEDIATION_LOG.md) (fix status). Last updated 19 November 2026.

## State

- **Work branch:** `feat/v0.4-windows`. Everything is pushed; `main` and the public release are still v0.3.0-alpha.5. Ask the owner before every merge, tag or release.
- **Proven on hardware:**
  - **Windows laptop, plugin from 0b53c03 (17 Nov):** USB live, replug, hide/show, Wi-Fi pairing twice, Wi-Fi live, LAN noise, a 13-minute run and a clean exit. See [WINDOWS_THIRD_LAPTOP_TEST.md](research/WINDOWS_THIRD_LAPTOP_TEST.md).
  - **Mac (18–19 Nov):** USB live, keep live while hidden, Wi-Fi pairing after the deadlock fix (07b6c52), quick exit, and the design v1 phone screens. Switching camera or quality during live restarts in about 3 s.
- **Built and tested by CI only:** later Windows plugin changes (the lock rule from 07b6c52 and the design v1 Properties). The Windows laptop has been returned.
- **Design v1 (812432a):** see [UX_PLAN.md](UX_PLAN.md). **Not tested yet** beyond switching during live on the Mac. Recording, Paired computers, friendly errors, the OBS sections and Wi-Fi are all still to test (list below).
- **v0.4 prepared, not released:** version 0.4.0-alpha.1, with draft notes in [releases/v0.4.0-alpha.1.md](releases/v0.4.0-alpha.1.md). Candidate files are built locally into `dist/0.4.0-alpha.1/` (outside Git) by `python3 tools/release.py`. Tomorrow's tests use those exact files.
- **Rule for tomorrow (owner, 19 Nov):** test, and when something fails, fix it following this plan without deviating. Rebuild, retest the failed check, then continue.
- **Product direction (owner, 19 Nov):** a niche product, the best phone camera for OBS. No 3D. The earning ideas wait until there are users.

## 20 November: where testing stopped

- **All 6 design v1 checks passed** with the candidate files (results in [releases/v0.4.0-alpha.1.md](releases/v0.4.0-alpha.1.md)). Fixes found on the way are in 71025e1: icon, OBS layout, short help, no address shown, no restart for an unchanged quality, no "install the app" during a USB reset.
- **Wi-Fi:** college Wi-Fi blocked pairing, because it keeps devices apart. The phone's hotspot worked, which is the first hotspot check. The pairing errors now suggest the hotspot. This change is committed but **not built into the candidate yet**.
- **Open question:** one Wi-Fi restart at 15:20:39 had no touch logged. Ask the owner whether it was a tap. If not, investigate before release. vivo did not keep the app's own log lines that minute.
- **Next:**
  1. Rebuild with `python3 tools/release.py`.
  2. Install the rebuilt files.
  3. Quick retest: a pairing error message, and one quality change on the front camera, which should not restart.
  4. Decide on Windows.
  5. Ask the owner, then merge, tag and publish.
  6. Then faster switching (step 2 below).

## Tomorrow's plan (owner's order)

1. **Test design v1 on the Mac and phone, one check at a time,** with the release candidate files: install `PhoneCam-0.4.0-alpha.1.apk` on the phone and the plugin from the zip into OBS.
   1. Record in OBS for 20 s with one camera switch, then play the file. The picture should freeze for about 3 s, with no black frames and no stop.
   2. Change quality while recording (1080p to 4K and back). The picture stays fitted.
   3. Phone Settings → Paired computers: the count, the list, and Forget on one computer.
   4. A pairing error shows plain words. For example, scan an expired code.
   5. OBS Properties: the status box turns yellow on a problem (unplug during live), the help icons appear, and the sections read well.
   6. Wi-Fi live from a fresh pairing. Turn the phone's Wireless debugging off first, or adb Wi-Fi takes the connection.
2. **Faster switching:** when the phone restarts on purpose, reuse the existing adb forward instead of setting it up again. Target: under 1 s. `native/adb_session.cpp`, the `catch (std::exception)` after `receive`.
3. **The rest of Stage 2:** AUDIT-010, 011, 012, 013, 014 and 017, in the order of [PRIORITIZED_REMEDIATION_ROADMAP.md](../audit/PRIORITIZED_REMEDIATION_ROADMAP.md). Then the Wi-Fi part of AUDIT-002 and AUDIT-008.
4. **v0.4 release.** Proposed steps:
   - build the release files from one commit;
   - prove the exact Mac plugin and APK on the phone and OBS;
   - decide with the owner how to ship the Windows plugin (CI-proven only since 0b53c03, or wait for another Windows PC);
   - then tag, after the owner says yes.
5. **Product plan:** phases and features for the niche, a competitor comparison checked against sources, and earning options.
6. **Later:** refine each part, one function and screen at a time.

## Setting up another computer

- Clone `github.com/VaraKare/phonecam-obs` (clone your own repository; a fork is a copy under another account) and check out `feat/v0.4-windows`.
- **Tools:**
  - Android Studio (or the Android SDK with JDK 17);
  - Xcode command-line tools (Mac);
  - OBS Studio 33.0.1;
  - `ffmpeg` and `gh`, logged in.
- **Checks before any hand-off:**
  - `./gradlew testDebugUnitTest lintDebug assembleDebug`
  - `python3 -m unittest discover -s tools/tests`
  - `python3 tools/build_native.py --install && python3 tools/test_native.py` (Mac)
  - Windows builds run in CI (`tools/build_windows.py`).
- **Release signing key:** only needed for release APKs. It lives outside Git in the owner's home folder, with a backup in the owner's iCloud Drive. Never commit it.
- **Debugging tips:**
  - A hung OBS on the Mac: `sample <OBS pid> 3` shows where it waits.
  - Windows: the PhoneCam log lines, plus the "Wi-Fi server: no progress" and "Not responding for 15 s while …" watchdog lines.
  - Borrowed Windows PCs: [WINDOWS_TEST.md](WINDOWS_TEST.md) and `tools/windows_kit.py`.
