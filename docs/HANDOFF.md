# Hand-off: where PhoneCam OBS stands and what comes next

Read this first in a new session, then [AGENTS.md](../AGENTS.md) (rules) and [CHANGE_MAP.md](CHANGE_MAP.md) (what changes together). Keep this file short; older entries are in [HANDOFF_ARCHIVE.md](HANDOFF_ARCHIVE.md). Last updated 23 November 2026.

## State

- **Released:** v0.7.0-alpha.1 on 26 Nov 2026 (pre-release; main merged at cce7f82, tag v0.7.0-alpha.1). Files:
  - the app and the Mac .pkg and .zip, built from 17512b9;
  - the Windows installer, from CI run 45231935828 (24f89cb).

  These are the exact files tested on the vivo V2437 with the Mac (23 Nov) and with a Windows laptop (25 Nov). Ask the owner before every merge, tag or release.
- **Not released:** the universal (Intel) Mac plugin ([#3](https://github.com/VaraKare/phonecam-obs/issues/3)).
- **Open work:** GitHub issues #1–#11. Devices: tablet #1, other brands #2, Intel Mac #3, the remaining Windows checks #4. Later: iPhone #5, several phones #6, Linux #7. The owner's ideas: Wi-Fi when the cable is pulled #8, find adb in Downloads #9, update notices #10 (needs a rule and privacy-policy decision first), version messages in OBS #11.
- **Owner to do:** screenshots for the README `<!-- IMAGE -->` places (SmartScreen, `C:\platform-tools`, Allow USB debugging, firewall, Mac Open Anyway).
- **OBS 33.0.3** (the owner's Mac updated itself) loads the 33.0-built plugin: checked in the build's load test on 26 Nov.

## Next: v0.8, in a new session

- Start the new session from this file. Cut `feat/v0.8-…` from `main`. Patch versions (0.7.1) stay on `feat/v0.7-clients-install-reliability`.
- Pick v0.8's scope with the owner from the issues and the reliability list below. Suggested first: #9 (small, came from a real user test) and #8 (the owner's idea).
- After any change, run the helper agents: docs-keeper and privacy-guard before committing, check-runner for the checks, release-gate before a release.

## After v0.7 (owner's order)

- **Devices, when the owner has them:**
  - a Windows laptop: [tests/WINDOWS.md](tests/WINDOWS.md), with the installer from CI;
  - an Android tablet: [tests/TABLET.md](tests/TABLET.md);
  - a second Android phone: [tests/SECOND_ANDROID.md](tests/SECOND_ANDROID.md).
- **Reliability toward v1.0:** 2-hour run, 20 replugs, camera-to-OBS delay (`tools/latency_clock.html`), clap sync (`tools/sync_check.py`).
- **To plan in phases (owner, 23 Nov):**
  - **Update notices for people who download PhoneCam.** Suggested: the OBS plugin checks a small version file on GitHub Releases once a month, only if the user agrees, and passes the notice to the phone. The phone app stays offline. This needs the owner's rule change and a privacy-policy update.
  - **Messages in OBS itself,** not just the log: "works with OBS 33 and newer", and a warning when the phone app and plugin versions don't match.
  - **The computer side stays a plugin only:** no desktop app, no disk image.

## Setting up another computer

- Clone `github.com/VaraKare/phonecam-obs` and check out the current branch.
- **Tools:**
  - Android Studio (or the Android SDK with JDK 17);
  - Xcode command-line tools;
  - OBS 33.0 or newer: the plugin always builds against the pinned OBS 33.0.1 SDK;
  - `ffmpeg` and `gh`, logged in.
- **Checks before any hand-off:** the **check-runner** agent, or by hand:
  - `./gradlew testDebugUnitTest lintDebug assembleDebug`
  - `python3 -m unittest discover -s tools/tests`
  - `python3 tools/build_native.py && python3 tools/test_native.py` (Mac, with OBS quit)
  - Windows builds run in CI (`tools/build_windows.py`).
- **Release signing key:** outside Git in the owner's home folder, with a backup in the owner's iCloud Drive. `tools/release.py` uses it; never open or commit it.
- **Test rules:**
  - Install the plugin only with OBS quit.
  - One check at a time.
  - The vivo phone drops the app's own log lines, so read the `[phonecam]` lines in the OBS log.
  - College Wi-Fi keeps devices apart; use the phone's hotspot.
