# Repository coverage

## Baseline

| | |
|---|---|
| Branch | `feat/v0.4-windows` (8 local/remote branches, 1 tag `v0.3.0-alpha.5`, 46 commits) |
| Commit | `9f0b0cba207804846bdbf5b610415b7d3336bf07` |
| Working tree at start | Clean |
| Submodules | None |
| Tracked files | 131 text files (11,636 lines) plus 5 binaries: `gradle-wrapper.jar` and 4 `.pcam` test fixtures |
| Languages | Kotlin (Android app, ~2,900 lines incl. tests), C++/Objective-C++ (native, ~3,600 lines), Python (tools, ~2,100 lines), PowerShell (kit, 159), Gradle KTS, YAML, Markdown docs (~1,260) |
| Build systems | Gradle 8.13 / AGP 8.13.0 / Kotlin 2.3.0 (Android); `tools/build_native.py` (clang++ directly, macOS arm64); `tools/build_windows.py` (MSVC `cl` directly, CI); no CMake |
| Targets | Android 10+ (minSdk 29, target 36); macOS 13+ on Apple Silicon with OBS 33.0.x; Windows x64 with OBS 33.0.1 (test build) |
| Generated / vendored | None vendored. OBS, SIMDe, FFmpeg (obs-deps), qrcodegen and platform-tools are downloaded, pinned and SHA-256 checked into ignored `native/build/` |

## Component inventory

| Component | Path | Language | Responsibility | Dependencies | Audit status |
|---|---|---|---|---|---|
| Camera and encoder | `app/.../LiveStreamer.kt`, `Capabilities.kt`, `Controls.kt`, `BitrateController.kt`, `Quality.kt`, `Orientation.kt`, `FrameStats.kt` | Kotlin | Camera2 capture, MediaCodec hardware H.264/H.265, controls, orientation, adaptive bitrate | Android SDK | Deep |
| Phone transport | `LiveTransport.kt`, `LiveQueue.kt`, `PairingProvider.kt` | Kotlin | USB abstract socket, token, bounded queue, watchdog | — | Deep |
| Phone Wi-Fi | `WifiCrypto.kt`, `WifiLink.kt`, `WifiDiscovery.kt`, `ScanActivity.kt` | Kotlin | Pairing, handshake, encrypted records, NSD discovery, QR scanning | ZXing 3.5.3 | Deep (security) |
| Phone UI | `MainActivity.kt`, `SettingsActivity.kt`, `Ui.kt`, `Feedback.kt` | Kotlin | Screens, state, permissions | — | Deep (MainActivity), full read (others) |
| Research tools | `BenchmarkActivity.kt`, `BenchmarkRecorder.kt` | Kotlin | Timed recordings for quality research | — | Full read |
| Manifest and backup rules | `AndroidManifest.xml`, `res/xml/data_extraction_rules.xml` | XML | Permissions, exported components, backup exclusion | — | Deep |
| Wire protocol | `native/protocol.hpp`, `stream.hpp` | C++ | PCAM v3 framing, NAL parsing, message loop | — | Deep + fuzzed |
| macOS receiver/plugin | `native/receiver.mm`, `plugin.mm`, `obs_common.hpp`, `pairing_card.mm`, `receiver_cli.mm`, `smoke.mm` | Obj-C++ | VideoToolbox decode, rotation, OBS source | libobs 33, VideoToolbox | Deep + ASan/UBSan/TSan + analyzer |
| USB/adb (shared) | `native/adb.hpp`, `adb_parse.hpp`, `adb_session.cpp` | C++ | Find adb, pair, forward, reconnect, restart | adb | Deep |
| USB/adb (platform) | `native/adb.mm`, `windows/adb_win.cpp` | Obj-C++/C++ | Process spawning with limits, Windows server lifetime | — | Deep |
| Wi-Fi server and crypto | `native/wifi.hpp`, `wifi_server.cpp`, `wifi_net.hpp`, `wifi_crypto*.hpp`, `wifi_store.hpp`, `wifi.mm`, `windows/wifi_win.cpp` | C++/Obj-C++ | Listener, handshake, records, pairing store, mDNS | CommonCrypto/Security, CNG | Deep (security) |
| Windows receiver/plugin | `native/windows/receiver_win.cpp`, `plugin_win.cpp`, `pairing_card_win.cpp`, `receiver_cli_win.cpp`, `fake_adb.cpp`, `smoke_win.cpp` | C++ | FFmpeg D3D11VA decode, CPU rotation, OBS source | OBS FFmpeg, D3D11 | Deep (code); not executed locally |
| Rotation | `native/rotate.hpp` | C++ | Tiled CPU rotation/mirror | — | Deep + unit test |
| Native unit tests | `native/protocol_test.cpp`, `adb_test.cpp`, `rotate_test.cpp`, `wifi_test.cpp` | C++ | Parser, rotation and crypto vectors | — | Run (sanitizers) |
| Android unit tests | `app/src/test/...` (9 files) | Kotlin | Queue, controls, orientation, quality, bitrate, crypto, link | JUnit 4 | Run |
| Build and release | `build.gradle.kts`, `app/build.gradle.kts`, `tools/build_native.py`, `build_windows.py`, `release.py` | Kotlin DSL/Python | Builds, pinned downloads, signing, packaging | — | Deep |
| CI | `.github/workflows/ci.yml` | YAML | Host tools, Android, Windows plugin | GitHub Actions | Deep |
| Developer tools | `tools/phonecam.py`, `live.py`, `make_fixtures.py`, `test_native.py`, `tools/tests/*` | Python | Device helper, receiver wrapper, fixtures, tests | adb, ffmpeg | Deep |
| Windows test kit | `tools/windows_kit.py`, `windows_kit.ps1`, `tools/tests/test_windows_kit.py` | Python/PowerShell | Portable OBS kit for borrowed PCs | OBS zip, platform-tools | Deep |
| Docs | `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `NOTICE`, `docs/*.md`, `docs/research/*.md` | Markdown | Plans, specs, research records | — | Full read, checked against code |
| Licence | `LICENSE` | Text | GPL-2.0-or-later | — | Header and "or later" clause only (standard text) |
| Gradle wrapper | `gradlew`, `gradlew.bat`, `gradle-wrapper.jar` | Script/binary | Wrapper | — | Not reviewed line-by-line (generated); jar validated in CI |
| Resources | `app/src/main/res/drawable/*`, `mipmap-anydpi/*` | XML | Icons | — | Listed only (no logic) |
| Issue template | `.github/ISSUE_TEMPLATE/device-report.yml` | YAML | Device reports | — | Full read |

Every tracked first-party file has a disposition above. Files not named individually are icons and the generated wrapper.

## Review depth by reviewer

| Workstream | Files fully read | Partial reads |
|---|---|---|
| Android camera/encoding/lifecycle | 21 source, manifest and test files | `WifiCrypto.kt` (Sealer only), `ScanActivity.kt` (camera lines), `WifiDiscovery.kt` (first 55 lines truncated at 240 columns) |
| Wi-Fi protocol and crypto | All Wi-Fi files on both ends, specs, tests | `MainActivity.kt`/`LiveStreamer.kt` (pairing lines), decoders (out of scope) |
| Native receiver/plugin/adb | All native sources and tests, sender `LiveTransport.kt`/`LiveStreamer.kt`, libobs 33.0.1 excerpts (show/hide threading, module unload) | Wi-Fi server for lifetime only |
| Build/CI/tools/docs | All build, CI and tool files, all docs, Git history scans | `LICENSE` body; other source by targeted grep |
| Lead auditor (re-checks) | `LiveTransport.kt`, `protocol.hpp`, `wifi_crypto*.hpp`, `wifi_server.cpp`, `wifi.hpp`, `wifi_net.hpp`, `WifiCrypto.kt`, `WifiLink.kt`, `PairingProvider.kt`, manifest, parts of `plugin.mm`, `wifi.mm`, `receiver.mm`, `adb_session.cpp`, `build_native.py`, `receiver_cli.mm`, libobs `obs-source.c`/`obs-video.c`/`obs-module.c` | — |

## Tools and tests executed (macOS 27.2, Apple clang 21, Python 3.13, ffmpeg 9.0.2; no phone attached)

| Command | Result |
|---|---|
| `./gradlew testDebugUnitTest lintDebug assembleDebug` (and a forced re-run of the tests) | Pass. 44 tests, 0 failed, 1 skipped (`WifiInteropTest`, which runs only when driven by `test_native.py`). Lint: 0 errors, 73 warnings (63 `SetTextI18n`; also `ExportedContentProvider` by design, `DiscouragedApi` for `scheduleAtFixedRate` and a fixed orientation, `DrawAllocation` ×3, newer Gradle/ZXing available) |
| `python3 -m unittest discover -s tools/tests` | 28 tests OK |
| `python3 tools/build_native.py` | Pass: plugin, receiver and smoke build with `-Werror`; protocol, adb, rotation and Wi-Fi unit tests under ASan+UBSan pass; OBS 33.0.1 loads the source |
| `python3 tools/test_native.py` | 15 tests OK (VideoToolbox decode, rotation, mirror, gaps, version, auto-pair, replug, Mac ↔ Kotlin Wi-Fi) |
| Same suite against an **ASan+UBSan** receiver (scratch build) | 15 OK; no sanitizer report files |
| Same suite against a **TSan** receiver (scratch build) | 15 OK; no race reports |
| **Stream fuzzing**: 600 mutated fixture streams (bit flips, header fields, lengths up to 4 GiB, truncation, dropped/duplicated/swapped messages, NAL length corruption, config fields) against the ASan+UBSan receiver, seed 20261116 | 582 rejected with a clear error, 18 decoded fully, **0 crashes, 0 hangs, 0 sanitizer reports** |
| Clang static analyzer (core, C++, security, unix, osx, nullability, optin) over all macOS/portable native sources | No memory or logic defects; 6 nullability warnings (AUDIT-022 l) |
| Targeted reproduction: invalid UTF-8 phone name through `cleanName` → `savePairings` (scratch program, scratch store) | `NSInvalidArgumentException` abort (AUDIT-003) |
| `strings`/`nm` on the released 0.3.0-alpha.5 Mac plugin | Debug map with 6 object entries and one home-folder path (AUDIT-015) |
| Git history scans (counts only) | User-name paths in 4 commits and private addresses in 9 commits, all reachable from `origin/main` (AUDIT-015); no keys, tokens or keystores ever committed |
| Windows CI run 43907577041 (preceding commit of this branch) | All jobs green, incl. the Windows replug test |

All scratch builds and fuzz inputs live outside the repository, in the session scratchpad.

## Not run, and why

| Check | Blocked by |
|---|---|
| Anything on the phone (camera, encoder, socket behaviour, SELinux) | No device attached; not authorised to start capture |
| OBS UI behaviour (hide hitch, properties re-entrancy, rendering lag) | Needs interactive OBS |
| Windows plugin execution, D3D11 path, firewall, adb under Avast | No Windows PC (see [WINDOWS_PC_CHECKS_TOMORROW.md](WINDOWS_PC_CHECKS_TOMORROW.md)) |
| Dependency vulnerability advisories | No network lookups made, by design |
| Kotlin static analysis beyond Android Lint (detekt etc.) | Not installed; nothing new installed |
| clang-tidy / cppcheck | Not installed |
| Windows static analysis (MSVC `/analyze`) | No Windows toolchain locally |

## Remaining coverage gaps

- **Gradle wrapper jar:** its checksum was not compared with Gradle's published list offline (CI validates it).
- **Windows code:** reviewed by reading only; compiled and tested only in CI.
- **Android UI flows:** settings screens and Feedback sounds were read but not exercised.
