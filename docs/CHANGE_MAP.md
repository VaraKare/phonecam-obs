# What changes together

When one part of PhoneCam changes, other files must follow. The `docs-keeper` helper agent (`.claude/agents/`) checks every change against this table before a commit, and `release-gate` checks it again before a release. Add a row whenever something is missed.

| When this changes | Also update |
|---|---|
| **Anything users see or do on the phone** (buttons, screens, Settings, messages) | README "Use it" and the controls legend; the in-app "What the buttons do" (SettingsActivity); `docs/versions/vX.Y.md`; release notes; `llms.txt` and README "Questions people ask" if a common question's answer changes |
| **Permissions, network use, sound, background or anything stored** | AGENTS.md rules (owner's decision, with the date); docs/PLAN.md; README "Privacy"; the in-app Privacy text (SettingsActivity); docs/DATA.md; the store listing when there is one |
| **The OBS plugin** (Properties, hotkeys, log lines, behaviour) | The other platform's plugin (`native/plugin.mm` ↔ `native/windows/plugin_win.cpp`, shared code in `native/obs_common.hpp`); README OBS steps and Troubleshooting; docs/COMPATIBILITY.md "What works, feature by feature" |
| **The wire between phone and computer** (message types, hello, control text) | docs/USB_LIVE.md and docs/WIFI.md wire tables; `native/protocol.hpp` and its tests; the phone's LiveTransport; `tools/test_native.py`; old-peer behaviour (a new app with an old plugin and the reverse) |
| **Install, packaging or uninstall** (.pkg, Windows installer, APK) | README "Install" and "Uninstall"; release notes; docs/DEVELOPMENT.md; CI install checks; docs/tests checklists that install |
| **OBS versions** (`SDK_VERSION`, minimum OBS) | README "What you need"; docs/COMPATIBILITY.md "OBS versions and updates"; docs/DEVELOPMENT.md; release notes (a raised minimum is called out) |
| **A version number** | `app/build.gradle.kts` versionName/versionCode; `PLUGIN_VERSION` in tools/build_native.py; `docs/versions/vX.Y.md`; `docs/releases/vX.Y.Z-….md`; docs/ROADMAP.md; docs/HANDOFF.md |
| **A test on real hardware** | The research note for that test (`docs/research/`); "Tested on hardware" in `docs/versions/vX.Y.md`; docs/COMPATIBILITY.md setups table. Never mark a hardware check passed from CI, an emulator or a build |
| **A device or setup we can't test yet** | A checklist in `docs/tests/`; a GitHub issue labelled `needs-device` or `later`; its row in docs/COMPATIBILITY.md with the issue number |
| **Where the project stands** (end of a day, a release, a new minor version) | docs/HANDOFF.md (short: state, next steps, open questions); older entries move to docs/HANDOFF_ARCHIVE.md |
