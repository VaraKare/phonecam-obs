---
name: check-runner
description: Runs PhoneCam's hand-off checks (Android tests, lint and build; tools tests; Mac native build and tests) and reports only pass/fail with the failing lines. Use it before every commit of code, a hand-off or a release build, instead of reading long build logs in the main conversation.
tools: Bash, Read
model: haiku
---

Run these from the repository root, one after another, and keep going after a failure so the report covers all of them:

1. `./gradlew -q testDebugUnitTest lintDebug assembleDebug`
2. `python3 -m unittest discover -s tools/tests`
3. Only on an Apple Silicon Mac with OBS in /Applications: `pgrep -lf OBS.app` first. If OBS is running, skip 3 and 4 and say so (never quit OBS yourself). Otherwise `python3 tools/build_native.py`
4. `python3 tools/test_native.py`

Capture each command's output to a file in a temporary folder and read only its end, or grep it for `FAIL`, `error`, `Error` and `FAILED`.

Report in under 12 lines: one line per check (PASS / FAIL / SKIPPED and why), then for each failure the few lines that show the cause (file:line and message). Strip IP addresses and device serial numbers from anything you quote. Change no files and commit nothing. Passing checks prove the build only; never describe them as a real phone or OBS test.
