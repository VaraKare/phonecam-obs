# Hand-off: where PhoneCam OBS stands and what comes next

Read this first in a new session, then [AGENTS.md](../AGENTS.md) (rules) and [CHANGE_MAP.md](CHANGE_MAP.md) (what changes together). Keep this file short; older entries are in [HANDOFF_ARCHIVE.md](HANDOFF_ARCHIVE.md). Last updated 23 November 2026.

## State

- **Released:** v0.6.0-alpha.1 (22 Nov; app 2e2a0a6, Mac plugin 7f3293f). Windows users are still on the v0.4.0 plugin (laptop-tested, 0b53c03). Ask the owner before every merge, tag or release.
- **Working on:** v0.7.0-alpha.1 on `feat/v0.7-clients-install-reliability` (app versionCode 15, plugins 0.7.0-alpha.1). Pushed.
- **Release candidate files:** `dist/0.7.0-alpha.1/` (outside Git), built by `tools/release.py` from **17512b9**. The owner installed and used these exact files on 23 Nov. Later commits are docs and agents only, so ship these files; don't rebuild unless code changes (a rebuild means retesting).
- **v0.7 hardware results:** [research/V07_MAC_TESTS.md](research/V07_MAC_TESTS.md). Passed:
  - .pkg install, faster start, screen choice (entire screen or one app);
  - screen sharing capped at 30 fps, phone sound;
  - OBS 33.0.2 with the fixed OBS 33.0 SDK;
  - both recording options (start with the phone, stop with the phone).

  Failed and documented: automatic audio tracks (users tick tracks 1–6). Not run: option unticked / cable pull, clap sync, 2-hour run, 20 replugs, delay measurement, Windows PC, tablet, Intel Mac.
- **New on 23 Nov:**
  - OBS update safety: one pinned SDK, a log line, and a weekly newest-OBS CI check that runs once on `main`.
  - Helper agents in `.claude/agents/` (docs-keeper, check-runner, release-gate, privacy-guard).
  - [CHANGE_MAP.md](CHANGE_MAP.md).

## Tomorrow (24 Nov): finish and release v0.7

1. **docs-keeper** on this branch against `main`. Known gaps it should find:
   - the README install step still describes the old zip and Terminal command (now the .pkg);
   - the recording options, screen choice, phone sound and "OBS 33 or newer" are missing from the README and controls legend;
   - the in-app Privacy text needs checking for phone sound.

   Then **privacy-guard** on the same range.
2. **v0.7 docs:**
   - `docs/versions/v0.7.md`;
   - `docs/releases/v0.7.0-alpha.1.md`, which ships the app plus the Mac .pkg and zip. No Windows plugin and no universal Mac plugin, because neither has been hardware-tested;
   - ROADMAP.
3. **Optional two-minute checks with the owner:**
   - recording option unticked: phone Stop keeps OBS recording;
   - cable pulled during live: OBS keeps recording.
4. **GitHub issues** (owner approved), labelled `needs-device` or `later`, each linking its `docs/tests/` checklist:
   - Windows PC;
   - Android tablet;
   - second Android brand;
   - Intel Mac;
   - iPhone (after v1.0);
   - several phones (last);
   - Linux (later).

   Then fill the `#TBD` numbers in COMPATIBILITY.md.
5. **check-runner**, then **release-gate**. Then ask the owner to merge, tag and publish the exact files from 17512b9.
6. After v0.7 is out, a **new session for v0.8**. Patch versions (0.7.1) stay in the same session.

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
