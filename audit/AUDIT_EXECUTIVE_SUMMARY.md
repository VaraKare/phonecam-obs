# PhoneCam OBS: audit executive summary

| | |
|---|---|
| Repository | VaraKare/phonecam-obs |
| Branch / commit | `feat/v0.4-windows` @ `9f0b0cba207804846bdbf5b610415b7d3336bf07`; working tree clean at the start |
| Date | 16 November 2026 |
| Scope | Android app, macOS and Windows OBS plugins, USB/adb transport, direct Wi-Fi protocol and crypto, build/CI/release tooling, Windows test kit, documentation |
| Method | Four read-only specialist reviews; findings re-checked against the code; local builds, tests, sanitizers, a static analyzer, stream fuzzing and one targeted reproduction (see [REPOSITORY_COVERAGE.md](REPOSITORY_COVERAGE.md)) |
| Production code changed | None. Only files under `audit/` were added |

## Overall health

**Good foundations, with lifecycle defects at the edges.**

The core video path is careful work:
- Every length from the network is bounded before memory is allocated.
- The protocol is strictly validated on both ends.
- The phone sends only hardware-encoded video, with a bounded queue that drops whole groups of frames and resumes at a key frame.
- Every adb call has a time limit.

On security, the direct Wi-Fi protocol:
- authenticates both sides;
- uses fresh keys every session (forward secrecy);
- uses encrypt-then-MAC records with replay and tamper protection.

It is consistent across Kotlin, Apple and Windows crypto.

None of these produced a single failure:
- 600 corrupted streams fuzzed under AddressSanitizer;
- the full native suite under ThreadSanitizer;
- all unit and integration suites.

The defects that matter are about **what happens when things stop**:
- stopping while waiting for the PC;
- unplugging the cable;
- hiding the source in OBS;
- a misbehaving device on the network.

Most of these were found by reading the code. One was reproduced here (AUDIT-003). One matches a failure seen on a real phone on 16 November (AUDIT-001).

## What was verified to work (by tests run in this audit)

| Area | Evidence |
|---|---|
| Android build, 44 unit tests, lint (0 errors) | `./gradlew testDebugUnitTest lintDebug assembleDebug` |
| Mac plugin build, protocol, adb parser, rotation and Wi-Fi crypto unit tests (with sanitizers), OBS module load | `tools/build_native.py` |
| VideoToolbox decode, rotation/mirror, sequence gaps, version rejection, auto-pairing, the new replug recovery, Mac ↔ Kotlin encrypted Wi-Fi | `tools/test_native.py`, 15 tests, also under ASan+UBSan and TSan: no reports |
| Parser and decoder robustness | 600 mutated streams, ASan+UBSan: 582 rejected with a clear message, 18 decoded, **0 crashes, hangs or sanitizer reports** |
| Windows build and the same tests on Windows | CI run 43907577041 (all jobs green) |

None of this is a physical-device result. Phone, OBS and Windows-PC behaviour remain as recorded in `docs/research/`.

## Major risks

| ID | Sev. | Risk |
|---|---|---|
| AUDIT-001 | P1 | Tapping **Stop while the phone waits for the PC** probably leaves its USB listener running. The next Start fails with "Address already in use". This likely explains the "already in use" seen on 16 Nov; it needs one phone check. In Wi-Fi-only use, every second Start would fail. |
| AUDIT-002 | P1 | **Hiding the PhoneCam source blocks OBS's video thread** until the connection thread stops. On the Mac this wait has no limit (the 16 Nov hang class still exists there). On Windows it is now bounded but can be about 8 s. Every hide costs at least one adb call. |
| AUDIT-003 | P2 | A phone name with invalid UTF-8 **crashes OBS on the Mac** when a pairing is saved (reproduced). The name is also not covered by the handshake's authentication. |
| AUDIT-004 | P2 | Several thread entry points and OBS callbacks have no top-level catch. A pairing file that cannot be written, among other environmental errors, **aborts OBS**. |
| AUDIT-005/006 | P2 | The Wi-Fi listener: |
| | | - is on by default on every network interface; |
| | | - can be kept busy by any device on the same Wi-Fi; |
| | | - its "Allow paired phones over Wi-Fi" switch has no effect until the source is hidden and shown. |
| AUDIT-007 | P2 | The computer sends the USB key to whatever answers on the phone's socket name, and the phone never proves itself. A malicious app on the phone could take the name. This needs a device check. |
| AUDIT-015 | P2 | Personal details removed from the docs (user-name paths, private addresses, a device identifier) remain in **public Git history**, and the released Mac plugin carries the build machine's paths in its debug map. |

Counts:
- **P0: 0.**
- **P1: 2.**
- P2: 17.
- P3: about 40, in 4 groups.

The full list is in [FINDINGS.md](FINDINGS.md).

## Release readiness

| Release | Assessment |
|---|---|
| v0.3 (published alpha, macOS) | Fine for its stated audience. Shipping a v0.3.1 Mac update is worth it for AUDIT-001 and AUDIT-003, plus the Wi-Fi switch (AUDIT-006). |
| v0.4 (Windows) | **Not ready.** W5 has not passed on hardware. The Windows replug fix is still unproven on a real PC. AUDIT-002, AUDIT-004 and AUDIT-010 affect Windows directly. The Windows test artifact lacks licence notices (AUDIT-018). |
| Any wider distribution | First fix AUDIT-001 to 007, rewrite or accept the Git history exposure (AUDIT-015), and add a macOS CI job (AUDIT-017). |

## Recommended immediate priorities

1. **AUDIT-001:** wake the phone's waiting listener on Stop. Small; confirm with a 30-second phone test before and after.
2. **AUDIT-002 + AUDIT-004:** never wait for the connection thread on OBS's video thread; use the Windows `Link` hand-off on both platforms, and give every thread and callback a top-level catch. Medium.
3. **AUDIT-003:** validate the UTF-8 phone name and convert it nil-safely. Small. Binding the name into the handshake can wait for a protocol bump.
4. **AUDIT-006 + AUDIT-005:** apply the Wi-Fi switch immediately, and keep unauthenticated connections from writing the status line or blocking the listener. Small to medium.
5. **AUDIT-015:** decide whether to rewrite history. Strip the Mac binary in `build_native.py` either way (small).

## Evidence limitations

- No phone, OBS user interface or Windows PC was used in this audit. Hardware-dependent findings are marked "needs device check".
- Steps for the Windows laptop are in [WINDOWS_PC_CHECKS_TOMORROW.md](WINDOWS_PC_CHECKS_TOMORROW.md).
- No online vulnerability-advisory lookup was done for dependencies; all are pinned (see the dependency table in REPOSITORY_COVERAGE.md).
- Severity reflects code evidence. Several P2 security items need an attacker on the same network or a malicious app on the phone, and are rated with that likelihood in mind.
