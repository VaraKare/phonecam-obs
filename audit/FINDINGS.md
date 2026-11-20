# Findings

Commit `9f0b0cb`. All paths are relative to the repository root; Kotlin files are under `app/src/main/java/dev/phonecam/obs/`.

How to read each finding:
- **Severity:** P0 critical, P1 high, P2 medium, P3 low.
- **Confidence:** Confirmed (by a test or direct code evidence on both sides), High, Medium or Low.
- **Likelihood vs impact:** both are stated where they differ.
- **Options:** "A / B / C" are the minimal, balanced and architectural fixes. Where only one approach is sensible, only one is given.
- Nothing here has been implemented.

Index:

| ID | Sev | Conf | Category | Title |
|---|---|---|---|---|
| 001 | P1 | High | Correctness | Stop while waiting leaves the phone's USB listener bound |
| 002 | P1 | Confirmed | Concurrency | Hiding the source blocks OBS's video thread |
| 003 | P2 | Confirmed | Crash/Security | Invalid UTF-8 phone name aborts OBS on macOS; name not authenticated |
| 004 | P2 | Confirmed | Crash | Uncaught exceptions on thread entry points and OBS callbacks |
| 005 | P2 | Confirmed | Security | Unauthenticated LAN hosts flood the status/log and block the listener |
| 006 | P2 | Confirmed | Security | Wi-Fi listener on by default on all interfaces; switch not applied |
| 007 | P2 | High (device check) | Security | USB key sent to an unauthenticated endpoint |
| 008 | P2 | Medium | Security | `adb connect` to any advertised Wireless-debugging address |
| 009 | P2 | Confirmed | Performance | Receiver never sheds work when it is the bottleneck |
| 010 | P2 | Confirmed | Concurrency | Windows adb server start unbounded, under a global lock |
| 011 | P2 | Confirmed | Correctness | Several sources or several phones not handled |
| 012 | P2 | Confirmed | Correctness | adb restart triggered by one bad device; kills other tools' servers |
| 013 | P2 | Confirmed | Correctness | "Live ended" reasons overwritten within 1–2 s |
| 014 | P2 | High | Correctness | Unhandled configuration changes silently end live on the phone |
| 015 | P2 | Confirmed | Security/Build | Personal details in public Git history and in the released Mac binary |
| 016 | P2 | Confirmed | Security | Windows kit: script over plain HTTP; reports hold more personal data than documented |
| 017 | P2 | Confirmed | Testing gap | No macOS CI although the Mac plugin is the released product |
| 018 | P2 | Confirmed | Build/Release | Third-party licence notices missing from shipped binaries |
| 019 | P2 | Confirmed | Documentation | Docs contradict the manifest and code |
| 020 | P3 | various | Android | Group of smaller Android lifecycle/UI issues |
| 021 | P3 | various | Security | Group of smaller Wi-Fi/pairing issues |
| 022 | P3 | various | Native | Group of smaller native receiver/plugin issues |
| 023 | P3 | various | Build/Tools | Group of release, kit, CI and maintainability issues |

---

## AUDIT-001: Stop while waiting leaves the phone's USB listener bound; the next Start fails

**Severity:** P1 · **Confidence:** High (code path read on both ends; one phone check confirms the platform behaviour) · **Category:** Correctness / lifecycle

**Affected files:**
- `LiveTransport.kt:91-113` (listener thread);
- `LiveTransport.kt:122` (Wi-Fi win closes the listener);
- `LiveTransport.kt:167-172` (`close()`);
- `LiveStreamer.kt:288-299`;
- `MainActivity.kt:337, 355-366`.

**Current implementation:**
- After Start, the `phonecam-usb-writer` thread binds `LocalServerSocket("phonecam-live-v1")` and blocks in `listener.accept()` (91, 99).
- Stop, the 30-second timeout, or Wi-Fi winning the race all call `close()`. That calls `server?.close()` (169) and assumes this ends the wait; the comment at 111 says so.

**Evidence and problem:**
- On Linux/Android, closing a listening socket's descriptor from another thread does not wake a thread blocked in `accept()` on it. Android's `LocalServerSocket.close()` is such a close.
- The kernel socket and its abstract name therefore stay bound until some connection arrives. When one does, the stale thread drops it and exits (101).
- The next session's `LocalServerSocket(NAME)` (91) then throws "Address already in use". Because `closed` is false and `active` is null for the new session, line 112 reports `Live stopped: Address already in use` and ends the session.
- This matches the 16 Nov report (`docs/research/WINDOWS_SECOND_LAPTOP_TEST.md`, "Other findings"):
  1. The cable was unplugged, so the plugin's 1-second retries stopped arriving.
  2. The user tapped Start (waiting), then Stop.
  3. The user tapped Start again and got "already in use".

**Trigger conditions:**
- Stop, or the 30 s timeout, while waiting, with no USB connection attempt arriving before the next Start.
- Any session won by Wi-Fi (122), because the USB thread is still in `accept()`.

**Consequences:**
- Over USB: the next Start fails until the cable is reconnected with OBS running, or the app is force-stopped.
- Wi-Fi-only (cable-less) use: every second Start in the same app process fails, and also cancels the Wi-Fi attempt (112).
- On the BEST quality setting with zero frames, MainActivity adds an unrelated hint ("choose Quality: High", 360-361), which misdirects the user.

**Solution A, minimal:**
- In `close()`, wake the waiting `accept()` before closing: call `Os.shutdown(server.fileDescriptor, OsConstants.SHUT_RDWR)`, or connect a throwaway `LocalSocket` to `NAME`. Line 101 already discards such a connection.
- Also call `shutdownInput/Output` on an accepted `LocalSocket` (fixes the related stalled-write case in AUDIT-020).
- Second-order:
  - `shutdown` on a listening Unix socket must be confirmed to wake `accept` on the vivo kernel (it does on mainline Linux).
  - The self-connect must not count as a failed key; it doesn't, because `closed` is checked first.

**Solution B, balanced:**
- A, plus wait (bounded, about 500 ms) for both writer threads to exit before posting STOPPED.
- Treat a bind failure as "USB unavailable" rather than fatal when a Wi-Fi attempt is running.
- Second-order: Stop takes a few hundred ms longer, and the join needs a timeout so a stuck thread can't freeze Stop.

**Solution C, architectural:**
- One listener for the app process's lifetime. It hands authenticated sockets to whichever session is current and refuses them when idle.
- Second-order:
  - A socket name exists while not live. The camera still starts only after Start, but the idle attack surface changes (see AUDIT-007).
  - The same authentication and failure limits must apply.

**Comparison:**
- A fixes the root cause with a few lines and no protocol change.
- B removes the remaining race of two sessions overlapping.
- C is only worth it together with a USB protocol bump (AUDIT-007).

**Recommended:** A now; B in the same change if it stays small.

**Regression testing:**
- Instrumented test: `start()`, `close()` with no client, `start()` again; expect no bind error. Repeat after a Wi-Fi-won session.
- JVM test: put the listener behind an interface and assert `close()` returns only after the accept thread exits.
- Manual (phone, 30 s, before and after):
  1. Unplug USB.
  2. Tap Start, wait 3 s, tap Stop.
  3. Tap Start again and read the message.

**Effort:** Small. **Dependencies:** none.

---

## AUDIT-002: Hiding (or deleting) the PhoneCam source blocks OBS's video thread

**Severity:** P1 · **Confidence:** Confirmed mechanism; the size of the stall needs OBS · **Category:** Concurrency / lifecycle

**Affected files:**
- `native/plugin.mm:91-97, 120-121`;
- `native/windows/plugin_win.cpp:137-163, 198-199`;
- libobs 33.0.1 `obs-source.c:1419-1423` (show/hide called from `obs_source_video_tick`);
- `obs-video.c:82` (`tick_sources`, the video thread);
- `native/adb_session.cpp:55-59, 151`;
- `native/wifi_server.cpp:130`;
- `native/windows/wifi_win.cpp:56-62`;
- `native/receiver.mm:113, 177-178`.

**Current implementation:** OBS calls `info.hide` on its video (graphics) thread when the source stops showing (scene switch, eye icon, closing a preview). `end()` then waits for the workers:

| Platform | What `end()` does | Worst case |
|---|---|---|
| macOS | `worker.join()` with no limit | adb calls are bounded individually (5 s each) but don't check `stop` between them: `devices` + `mdns services` + up to 8 × `connect`, or N × `content query`, then 3 s for `dropForward`. The VideoToolbox waits (`VTDecompressionSessionWaitForAsynchronousFrames`, `VTPixelRotationSession` paths) have no timeout. |
| Windows | Waits up to 6 s, then hands the thread off | 6 s + 2 s for DNS-SD deregistration in the Wi-Fi join + a 200 ms poll ≈ 8 s |
| Both | Even in the normal case, `autoReceive` ends with `dropForward()`, which spawns an adb process | ~one adb run per hide |

**Problem:** blocking work on the thread that renders OBS's program output. libobs does not call show/hide from the UI thread.

**Trigger conditions:**
- Any hide or delete. The worst case needs a slow or hung adb (stale Wireless-debugging address, the 16 Nov stuck server) or a stuck VideoToolbox call.

**Consequences:**
- A dropped-frame hitch in stream/recording on each hide.
- Multi-second freezes when adb is slow.
- On the Mac, a permanent OBS hang (the 16 Nov failure class) if a call never returns.

**Solution A, minimal:**
- macOS: check `stop` between adb calls in `autoReceive` (cheap and shared).
- Windows: shorten the wait in `end()` to about 200 ms, then hand off as it already does.
- Second-order: more threads left running briefly after hide. They are safe on Windows because callbacks are gated by `Link::owner`.

**Solution B, balanced:**
- Move the Windows `Link` hand-off (shared state, owner gating, bounded wait, detach) into shared code, use it on macOS, and give the Wi-Fi server the same treatment.
- Second-order:
  - Detached threads may outlive OBS shutdown briefly. libobs 33 never unloads plugins (`obs-module.c:873` has `os_dlclose` commented out), so that is safe today. The existing `keepLoaded()` pin covers Windows.
  - A strong or weak `obs_source_t` reference should be held in `Link` (AUDIT-022).

**Solution C, architectural:**
- One module-level connection manager owns adb, the USB link and the Wi-Fi server. Sources attach and detach as viewers and never join anything.
- This also solves AUDIT-011 (several sources).
- Second-order: a larger refactor of both plugins; needs new lifecycle tests.

**Comparison:**
- A reduces the common cost but leaves the macOS unbounded case.
- B removes the hang class on both platforms with moderate change.
- C is the right long-term shape when multiple sources or phones arrive.

**Recommended:** B, with A's `stop` checks included.

**Regression testing:**
- Headless libobs test (extend `smoke.mm` / `smoke_win.cpp`): create the source, toggle showing 50 times with a fake adb that sleeps 30 s, and assert every hide returns in under 50 ms.
- Manual: OBS Stats → "Frames missed due to rendering lag" across 20 scene switches.

**Effort:** Medium. **Dependencies:** none; pairs naturally with AUDIT-004.

---

## AUDIT-003: Invalid UTF-8 phone name aborts OBS on macOS; the name is not authenticated

**Severity:** P2 (impact: OBS crash; likelihood: low, needs an on-path attacker during the 3-minute pairing window or a modified client that knows the code) · **Confidence:** Confirmed (reproduced) · **Category:** Crash / Security

**Affected files:**
- `WifiLink.kt:118-121` (name sent after `tagP`);
- `native/wifi_crypto.hpp:96-105` (the name is not in `t1` or the key hash);
- `native/wifi_server.cpp:186-202`;
- `native/wifi_store.hpp:33-36` (`cleanName` keeps bytes ≥ 0x80);
- `native/wifi.mm:52` (`@(p.name.c_str())`).

**Evidence:**
- `cleanName` keeps any byte ≥ 32 except 127, so invalid UTF-8 passes. On macOS, `@(...)` of invalid UTF-8 returns nil, and the dictionary literal raises `NSInvalidArgumentException`.
- **Reproduced:** calling the real `cleanName` and `savePairings` with the name bytes `Pixel` + `FF FE` terminated the test process with exactly that exception, called from `phonecam::wifi::savePairings`.
- In OBS this runs on the Wi-Fi thread. `Server::run` catches only `std::exception`, so the process aborts.
- The Clang analyzer also flags the nil-able conversions (`wifi.mm:27, 33, 64`).
- The length byte and name are outside both handshake tags. An on-path attacker can rewrite them without failing the handshake. On Windows the same bytes reach the status line and the "Paired:" summary (spoofed text, no crash found).

**Consequences:** OBS crash during pairing on macOS; a spoofable phone name on both platforms.

**Solution A, minimal (no protocol change):**
- `cleanName` keeps only valid UTF-8 sequences (replacing others with "?").
- `wifi.mm` uses a nil-safe conversion (`[NSString stringWithUTF8String:] ?: @"Phone"`).
- Add `catch (...)` around `Server::run` (see AUDIT-004).
- Second-order: none; names already stored stay valid.

**Solution B, balanced:**
- Also bind the name length and bytes into `tagP` and the key hash.
- Requires PCW2: matching Android, Mac and Windows releases. Existing pairings keep working if only pairing mode changes.

**Recommended:** A now; B at the next protocol revision.

**Regression testing:**
- Native: pair with name bytes `FF FE C0`; the server survives and stores a sanitised name.
- Kotlin interop: a flipped name byte is refused (B).

**Effort:** Small (A), Medium (B). **Dependencies:** AUDIT-004 covers the general catch.

---

## AUDIT-004: Uncaught exceptions on thread entry points and OBS callbacks abort OBS

**Severity:** P2 · **Confidence:** Confirmed mechanism; triggers are environmental · **Category:** Crash

**Affected files:**
- `native/wifi_server.cpp:125, 129` (`announce()` / `closePairing()` outside any try; both call `loadPairings()`, which can save and throw: `wifi.mm:42, 56`, `wifi_win.cpp:41, 94-97`);
- `native/plugin.mm:88` and `plugin_win.cpp:133` (thread lambdas without a catch);
- `plugin.mm:127-130, 137` and `plugin_win.cpp:205-208, 215` (`showPairingCard` and `summary()` called from OBS property callbacks);
- `native/adb_session.cpp:141, 147, 151` (`dropForward()` inside catch handlers and after the loop; on Windows `runAdb` creates a thread and can throw `std::system_error`).

**Problem:** C++ exceptions escaping a thread function or an OBS C callback call `std::terminate`. Only "Forget paired phones" is guarded.

**Trigger conditions:**
- The pairing store can't be written: read-only folder, disk full, `APPDATA` unset, antivirus lock.
- Suspected on Windows first run: two simultaneous `loadPairings()` calls (UI `properties()` and the Wi-Fi thread) both create `wifi.json.tmp`, and one `MoveFileExW` fails.
- Thread creation failure in `dropForward` (rare).

**Consequences:** OBS exits mid-production with no message.

**Solution A, minimal:**
- `try { … } catch (const std::exception &) { status(...) } catch (...) {}` at the top of every thread lambda and every OBS callback that calls into PhoneCam code.
- Make `dropForward` non-throwing.

**Solution B, balanced:**
- A, plus `announce()` and `summary()` stop touching the file. `Server` caches the pairings in memory under its lock and loads/saves through one mutex-protected store (this also fixes the race in AUDIT-021).

**Recommended:** B (A alone leaves the race).

**Regression testing:**
- Run `--wifi` with `PHONECAM_WIFI_STORE` pointing at an unwritable path; expect a status line and exit code 0, not an abort.
- Open properties in a loop while pairing.

**Effort:** Small–Medium. **Dependencies:** none.

---

## AUDIT-005: Unauthenticated LAN hosts can flood the status line and log, and block the Wi-Fi listener

**Severity:** P2 · **Confidence:** Confirmed · **Category:** Security (availability)

**Affected files:**
- `native/wifi_server.cpp:126-138` (one connection at a time, handled inline in the accept loop);
- `wifi_server.cpp:147` (5 s hello wait) and `:180` (30 s finish wait in pairing mode);
- `stream.hpp:17` (`Closed` is a `runtime_error`);
- `plugin.mm:29-33` and `plugin_win.cpp:56-60` (every status is logged and refreshes properties).

**Problem:**
- Any TCP connect-and-close, stall or garbage hello ends in `status("Wi-Fi live ended: …")`. That overwrites the shared USB/Wi-Fi status, writes an OBS log line and triggers a properties refresh, with no rate limit.
- While one connection is being handled, nobody else can connect. A looping attacker holds the slot for 5 s at a time (30 s with a pairing-mode hello). The phone's 5 s read timeout then fails every session and pairing.

**Trigger conditions:**
- Any host that can reach the port (same Wi-Fi, guest network, VPN).
- A port scanner triggers the misleading status by accident.

**Consequences:**
- Unbounded log growth.
- A misleading "Tap Start live" status.
- Denial of Wi-Fi live and pairing. No confidentiality impact.

**Solution A, minimal:** connection attempts that fail before authentication complete are silent apart from a rate-limited counter line ("N unknown connections ignored").

**Solution B, balanced:**
- Handle each handshake on a short-lived thread (cap about 4 at once, total 3 s budget).
- Pass the socket to the single decode slot only after `tagP` verifies, and send status 3 ("busy", already in the spec and the phone) when occupied.
- Second-order: a little more threading; keep the cap small.

**Solution C, architectural:** a non-blocking event loop for the listener.

**Recommended:** A + B.

**Regression testing:**
1. Connect and close 100 times, then hold one connection open silently.
2. Assert no status callback fires and a real pairing completes within 2 s.

**Effort:** Medium. **Dependencies:** AUDIT-006 reduces exposure.

---

## AUDIT-006: The Wi-Fi listener is on by default on every interface, and its switch is not applied

**Severity:** P2 · **Confidence:** Confirmed · **Category:** Security (exposure)

**Affected files:**
- `native/plugin.mm:118` and `plugin_win.cpp:191` (`wifi_direct` default true);
- `plugin.mm:83, 98-103` and `plugin_win.cpp:128` (read only at start);
- `plugin.mm:107-111` and `plugin_win.cpp:180-184` (`update()` reads only `adb_path`);
- `native/wifi_server.cpp:115` (`INADDR_ANY`).

**Problem:**
- Every visible source opens TCP 47183 on all interfaces (public Wi-Fi, VPN, Ethernet) and announces itself by mDNS, even with no paired phone and USB-only use.
- Unticking "Allow paired phones over Wi-Fi" does nothing until the source is hidden and shown, so the user believes it is off while it is listening.

**Consequences:**
- Avoidable exposure; it feeds AUDIT-005.
- On Windows the firewall prompt appears for users who never use Wi-Fi.

**Solution A, minimal:** in `update()`, start or stop the Wi-Fi server when `wifi_direct` changes.

**Solution B, balanced:**
- A, plus listen only while a phone is paired or a pairing window is open, and announce only on private (RFC 1918 / link-local) interfaces.
- Second-order: on Windows, the firewall prompt moves to the first pairing, which is better UX.

**Recommended:** B.

**Regression testing:**
- Toggle the setting and assert `listeningPort()==0` and a refused connection.
- With no pairings, assert nothing is listening.

**Effort:** Small. **Dependencies:** none.

---

## AUDIT-007: The computer sends the USB key to an unauthenticated endpoint; the phone never proves itself

**Severity:** P2 · **Confidence:** design gap Confirmed; exploitability needs a device check (SELinux rules for abstract sockets) · **Category:** Security

**Affected files:**
- `native/receiver.mm:225-228` and `windows/receiver_win.cpp:244-263` (token sent first);
- `native/adb_session.cpp:120-129`;
- `LiveTransport.kt:91, 141-152`.

**Problem:**
- The plugin connects to the phone's `localabstract:phonecam-live-v1` through `adb forward` and immediately writes the 64-hex key. It then trusts any PCAM data that comes back.
- PhoneCam binds that name only while live. A malicious app on the phone that binds it first while PhoneCam is idle could:
  - receive the key, because the plugin retries every second;
  - feed arbitrary video into OBS;
  - block PhoneCam's own bind.

**Preconditions:** a malicious app installed on the phone, and adbd allowed to connect to that app's socket (to be checked on the device).

**Solution A, minimal:**
- Mutual challenge-response: each side sends a random nonce and answers with `HMAC(key, nonce ‖ role)`. The key itself is never sent.
- Needs a USB protocol bump and a matching app and plugin.

**Solution B, balanced:**
- Run the existing PCW1 handshake over the USB socket with `K = token`. This gives encrypted, mutually authenticated USB on the code path Wi-Fi already uses, and also removes AUDIT-001's root cause with a single listener (AUDIT-001 C).

**Recommended:** B at the next protocol bump; until then document the assumption ("no untrusted apps").

**Regression testing:** a fake phone endpoint without the key; the receiver disconnects before parsing any PCAM, and the key never appears on the wire.

**Effort:** Medium. **Dependencies:** a protocol version bump.

---

## AUDIT-008: The plugin runs `adb connect` to every advertised Wireless-debugging address

**Severity:** P2 · **Confidence:** Medium (depends on adb's TLS behaviour, not established in this repository) · **Category:** Security

**Affected files:** `native/adb_session.cpp:89-99, 108-129`.

**Problem:**
- With no ready device, every 6 s the plugin runs `adb mdns services` and connects to up to 8 advertised `_adb-tls-connect._tcp` endpoints.
- The code comment assumes only paired phones succeed. If a rogue responder can appear as a `device`, it gets a key query and a forward, and can inject video (as in AUDIT-007).

**Solution A:** connect only to services whose instance name matches a phone previously seen authorised (store the names), plus AUDIT-007's mutual authentication.

**Regression testing:** a fake adb reporting an unknown wireless serial; assert no key query and no forward.

**Effort:** Small. **Dependencies:** AUDIT-007 for full protection.

---

## AUDIT-009: The receiver never sheds work when it is the bottleneck; the phone answers by cutting bitrate

**Severity:** P2 · **Confidence:** Confirmed by code; size measured only roughly (Windows 4K ≈ 31 ms per frame, 4% over 33 ms on 16 Nov) · **Category:** Performance / video quality

**Affected files:**
- `native/stream.hpp:31-68`;
- `windows/receiver_win.cpp:149-177`;
- `LiveTransport.kt:65`; `LiveQueue.kt:17-20`;
- `BitrateController.kt:39-49`; `LiveStreamer.kt:83-87`.

**Problem:**
- Every frame is decoded, copied from the GPU, rotated and handed to OBS in series. When that takes longer than the frame interval, TCP/adb back-pressure fills the phone's 6-frame queue.
- The phone then drops the rest of the group of frames (up to 1 s) and lowers the bitrate, although the network is fine.
- Latency also grows by the queue and socket buffers.

**Consequences:** periodic freezes of up to ~1 s and lower picture quality at 4K on slower PCs.

**Solution A, minimal:** after decoding, if another frame is already waiting on the socket, skip the GPU copy, rotation and OBS hand-off for this one (decoding must continue for references).

**Solution B, balanced:**
- A, plus time the D3D11 staging map separately; it may include waiting for the GPU decode.
- Choose the adapter explicitly.

**Solution C, architectural:**
- Rotate on the GPU with the D3D11 video processor, or output a texture-backed source and avoid the read-back.
- Second-order: large, Windows-only, and needs a GPU-equipped test machine.

**Recommended:** A, then decide on C from the timing lines on a real PC.

**Regression testing:** a 4K fixture at 30 fps with an injected 40 ms output delay; assert end-to-end delay stays under 2 frames and that the receiver, not the phone, skips frames.

**Effort:** Small (A), Large (C).

---

## AUDIT-010: The Windows adb server start is not covered by the new time limits and runs under a global lock

**Severity:** P2 · **Confidence:** Confirmed by code; trigger suspected (antivirus-dependent) · **Category:** Concurrency

**Affected files:** `native/windows/adb_win.cpp:116-118, 134-149, 231-256`.

**Problem:**
- `startServer`'s `CreateProcessW` runs on the worker inside `prepareAdb` while holding `serverLock`, without the abandonable helper thread that `runAdb` uses for the same risk.
- `prepareAdb` can also hold the lock for about 20 s while waiting for the port, and never checks `stop`.

**Consequence:** if process creation hangs (the Avast behaviour seen on 15 Nov), every later worker (re-show, other sources) blocks on the lock. Each hide then costs the full wait and leaks a thread until OBS restarts.

**Solution A:** run `startServer` through the same helper-thread bound, and release the lock while waiting for the port. Check `stop` in the wait loop.

**Solution B:** a dedicated adb-server supervisor thread with try-lock and timeouts.

**Recommended:** A.

**Regression testing:** a fake adb whose `server nodaemon` blocks before listening; a second `--auto` run in the same process still reports within 15 s.

**Effort:** Small.

---

## AUDIT-011: Several PhoneCam sources, or several phones, are not handled

**Severity:** P2 · **Confidence:** Confirmed · **Category:** Correctness

**Affected files:**
- `native/plugin.mm:78-89, 174` (`OBS_SOURCE_DO_NOT_DUPLICATE` only blocks duplication);
- `native/adb_session.cpp:108-113`;
- `LiveTransport.kt:74, 98-105`;
- `native/wifi_server.cpp:116-120`.

**Problem:**
- Two separately added sources each create their own forward to the same phone. The phone accepts one; the other sits in the listen backlog and reports "Live ended: video stalled for 15 seconds" in a loop.
- With two phones, the first ready device with the app always wins, and there is no serial setting. Start on the other phone times out after 30 s.
- A USB phone and a Wi-Fi phone can feed one source at the same time.

**Solution A:** a module-level registry so only one source owns the USB link and the others say "Another PhoneCam source is connected".

**Solution B:** forward to every phone that returns a key, bind to whichever one starts, and add an optional "phone" property.

**Solution C:** the connection manager from AUDIT-002 C, with explicit phone-to-source mapping (needed for multi-camera).

**Recommended:** A now; C when multi-camera is planned.

**Regression testing:** a fake adb with two devices; two receivers in one process.

**Effort:** Small (A).

---

## AUDIT-012: adb restart is triggered by one unresponsive device and kills servers other tools use

**Severity:** P2 · **Confidence:** Confirmed logic; real-world frequency unknown · **Category:** Correctness (introduced in 9f0b0cb)

**Affected files:**
- `native/adb_session.cpp:62-72, 107-116`;
- `native/adb.mm:47` (`kill-server`);
- `native/windows/adb_win.cpp:266-294` (`endStuckServer` ends any `adb.exe` listening on the port).

**Problem:**
- A `content query` timeout on any listed device counts as a server stall. Two in a row restart adb.
- A stale Wireless-debugging entry, a hung emulator or a locked second phone therefore makes the plugin kill the shared adb server about every 20 s, ending Android Studio or scrcpy sessions.
- On Windows the replacement server is tied to OBS's job object and dies with OBS.
- `endStuckServer` itself is a justified last resort (a hung server cannot answer `kill-server`), but it does not first confirm the server is actually hung.

**Solution A:**
- Count only server-level calls (`devices`, `forward`) as stalls; on a per-device shell timeout, skip that device.
- Before ending a foreign `adb.exe`, check the server socket directly with an adb `host:version` request; end it only if that also times out.

**Solution B:** use adb's own protocol on the socket (no client process) for health checks.

**Recommended:** A.

**Regression testing:** a fake adb where only `-s B shell` hangs; assert no `kill-server` and that device A still streams.

**Effort:** Small.

---

## AUDIT-013: "Live ended" reasons are overwritten within about 1–2 s; the phone shows an unrelated reason

**Severity:** P2 · **Confidence:** Confirmed · **Category:** Correctness (diagnosability)

**Affected files:** `native/protocol.hpp:16-17`; `native/adb_session.cpp:144-148, 126`; `LiveTransport.kt:35-39`; `docs/USB_LIVE.md:46`.

**Problem:**
- A version mismatch (or any decode error) produces "Live ended: … different PCAM versions …". After re-detection this is replaced by "Ready. Tap Start live on the phone."
- The phone sees only a closed socket and says "OBS stopped receiving: the source was hidden…".
- The promised clear mismatch error is never visible long enough to read.

**Solution A:** a sticky "Last session ended: …" line in the source properties, kept until the next successful live.

**Solution B:** the receiver sends a one-byte reason code before closing. Needs a protocol bump.

**Recommended:** A now, B with the next protocol bump.

**Regression testing:** extend `test_older_sender_rejected` to `--auto`; the final status still contains the mismatch text.

**Effort:** Small.

---

## AUDIT-014: Unhandled configuration changes recreate the phone's main screen and silently end live

**Severity:** P2 · **Confidence:** High · **Category:** Correctness / lifecycle

**Affected files:** `app/src/main/AndroidManifest.xml:11`; `MainActivity.kt:356, 578-588`.

**Problem:**
- `configChanges` lists only orientation and screen size.
- These recreate the activity:
  - a `uiMode` change (scheduled dark theme, battery saver);
  - a keyboard or font-scale change;
  - a display-size or locale change.
- `onPause` stops the stream, the STOPPED reason is discarded because `destroyed` is set (356), and the new screen shows idle.

**Consequence:** a long stream ends at sunset or at low battery with no explanation.

**Solution A:** add `uiMode|keyboard|keyboardHidden|navigation|fontScale|density|locale|layoutDirection` to `configChanges`. The UI is built in code with fixed colours, so nothing visible is lost.

**Solution B:** keep the last stop reason in a process-level holder and show it after recreation.

**Recommended:** A + B.

**Regression testing:** Robolectric or instrumented `recreate()` during LIVE; the stream survives, or the reason is shown.

**Effort:** Small.

---

## AUDIT-015: Personal details remain in public Git history and in the released Mac binary

**Severity:** P2 · **Confidence:** Confirmed · **Category:** Security / privacy / build

**Evidence:**
- Commit `7f396ba` removed macOS user-name paths, private IPv4 addresses, a phone identifier and a network name from the docs, but only from the working tree.
- `git log --all -G` finds them in 4 and 9 earlier commits respectively. Those commits are ancestors of `origin/main` and tag `v0.3.0-alpha.5` (verified: `merge-base --is-ancestor`).
- The released `dist/0.3.0-alpha.5` Mac plugin binary keeps a debug map, built with `-g` and not stripped (`tools/build_native.py:70`):
  - 6 object-file entries with temporary paths;
  - one string containing the project path under the builder's home folder.
- `tools/release.py:98-103` excludes only the `.dSYM`.
- Values are deliberately not reproduced here.

**Consequences:**
- The project's own rule (CONTRIBUTING.md: no device serials or personal details) is not met for anyone who clones the repository or downloads the plugin.
- Direct harm is low (private-range addresses) to moderate (identifier, user name).
- Build paths and zip timestamps also make the release checksums non-reproducible.

**Solution A, minimal:**
- Strip the binary before signing (`strip -S` or `-Wl,-S`) and add `-ffile-prefix-map=<root>=.`.
- Accept the history exposure and treat the identifiers as disclosed.

**Solution B, balanced:** A, plus rewrite history with `git filter-repo --replace-text`, force-push all branches, re-create the tag, and ask GitHub to purge cached views. This breaks existing clones and CI-run links in docs.

**Solution C:** publish a fresh, squashed repository and archive the old one privately.

**Recommended:** A immediately. The user decides between accepting or B, based on how sensitive the identifier and network name are.

**Regression testing:**
- `strings -a` and `nm -ap` on the released binary show no `/Users/` and no OSO entries.
- `git log --all -G'/Users/[a-z0-9]+/'` is empty (B).

**Effort:** Small (A), Medium (B).

---

## AUDIT-016: Windows kit: the PC runs a script fetched over plain HTTP, and its reports hold more personal data than documented

**Severity:** P2 (impact high on a third party's PC; likelihood low on a trusted home network) · **Confidence:** Confirmed by code · **Category:** Security / privacy

**Affected files:**
- `tools/windows_kit.py:117-118, 140, 144-146`;
- `tools/windows_kit.ps1:53-55, 62, 89, 91, 113-119, 156-158`;
- `docs/WINDOWS_TEST.md:12`.

**Problem:**
- `iex (iwr http://IP:PORT/TOKEN).Content` runs code fetched in clear text. The 6-character token (≈30 bits) travels in the URL.
- An active attacker on the same Wi-Fi (ARP spoofing, rogue access point) could replace the script or `kit.zip` and run code as the borrowed PC's user. A passive one can read the reports and post forged ones, including "Left behind: nothing".
- The docs say only Windows version, CPU, GPU, antivirus and PhoneCam log lines are sent. The script also sends:
  - `adb devices -l` (phone identifier);
  - the full OBS log;
  - user-profile paths;
  - USB device names and firewall rule names.

**Solution A, minimal:**
- Document "trusted home network only".
- `serve` prints the SHA-256 of the script and zip, and the PC-side script checks the zip's hash, which is embedded in the script.
- Send only `[phonecam]` lines, drop `-l`, replace the profile path with `~`.
- Correct the doc sentence.

**Solution B:** serve over HTTPS with an ephemeral certificate and pin its thumbprint in the typed line.

**Solution C:** carry the kit and script on a USB stick and keep reports on the stick.

**Recommended:** A before tomorrow's session (small). C for any session on a network you don't control.

**Regression testing:** a test in `test_windows_kit.py` asserts the script contains no `devices -l`, sends no whole OBS log, and checks the zip hash.

**Effort:** Small. **Note:** tomorrow's session runs on a home network. A is enough; see [WINDOWS_PC_CHECKS_TOMORROW.md](WINDOWS_PC_CHECKS_TOMORROW.md).

---

## AUDIT-017: No macOS CI, although the Mac plugin is the released product

**Severity:** P2 · **Confidence:** Confirmed · **Category:** Testing gap

**Affected files:** `.github/workflows/ci.yml:6-76` (ubuntu host-tools, ubuntu android, windows-plugin); `tools/build_native.py:64-68` (needs `/Applications/OBS.app`); `tools/test_native.py:24`.

**Problem:**
- `plugin.mm`, `receiver.mm`, `adb.mm`, `wifi.mm` and `pairing_card.mm` are never compiled in CI, and `test_native.py` never runs there.
- Since tag v0.3.0-alpha.5, the shared Mac code changed by about +1,000/−430 lines (Wi-Fi server moved to shared code, adb recovery). No physical Mac re-check is recorded, though this audit ran the full local Mac suite and it passed.
- `test_native.py` skips its whole class, still exiting 0, if `ffmpeg` or the receiver is missing, and the AGENTS.md hand-off command does not include it.

**Solution A:** a `macos-14` job that runs `build_native.py`'s portable sanitizer tests and syntax-checks the `.mm` files against the pinned headers.

**Solution B:** let `build_native.py` take libobs from a pinned, checksummed OBS dmg, then run the full build and `test_native.py` in CI. Fail on unexpected skips.

**Solution C:** move to CMake presets with the OBS plugin template.

**Recommended:** B (a hosted runner may lack VideoToolbox hardware decode; assert the expected skip set).

**Regression testing:** a deliberate compile error in `receiver.mm` turns CI red.

**Effort:** Medium.

---

## AUDIT-018: Third-party licence notices are missing from shipped binaries

**Severity:** P2 · **Confidence:** Confirmed (not legal advice) · **Category:** Build / release

**Evidence:**
- **APK:** the local 0.3.0-alpha.5 APK contains ZXing and the Kotlin standard library (Apache-2.0). It has no licence entry and no licences screen, and `NOTICE:8` calls Kotlin a development tool and omits ZXing.
- **Windows test artifact:** `ci.yml:71-76` ships the DLL and the VC runtime with only the qrcodegen notice, without the project's GPL text or `NOTICE`.

**Solution A:** add ZXing and Kotlin to `NOTICE`; add `LICENSE` and `NOTICE` to the Windows artifact.

**Solution B:** an "Open-source licences" row in the app's Settings that shows the bundled texts.

**Solution C:** generate notices from the dependency graph.

**Recommended:** A + B.

**Effort:** Small.

---

## AUDIT-019: Documentation contradicts the manifest and code

**Severity:** P2 · **Confidence:** Confirmed · **Category:** Documentation

| Claim | Where | Reality |
|---|---|---|
| "no Internet permission" | `docs/ARCHITECTURE.md:55`, `docs/DATA.md:3`, `docs/USB_LIVE.md:33` | Manifest declares INTERNET and ACCESS_NETWORK_STATE (`AndroidManifest.xml:5-6`, approved 11 Nov) |
| Logs hold no authentication material | `docs/DATA.md:11` | The pairing code is written to the OBS log (`plugin.mm:71` with `:31`; `plugin_win.cpp` same pattern) |
| Two-frame queue | `ARCHITECTURE.md:21, 44`, `DATA.md:11`, `USB_LIVE.md:35` | 6 frames (`LiveTransport.kt:65`) |
| "Wire format v2" | `USB_LIVE.md:39` | v3 |
| Software decoding rejected | `USB_LIVE.md:33, 70`; AGENTS.md | True on macOS; Windows falls back to software (`receiver_win.cpp:221-228`) |
| Windows uses Media Foundation | `ROADMAP.md:54`, `COMPATIBILITY.md:40` | FFmpeg D3D11VA (`WINDOWS.md:12`) |
| Pairing card "QR code decodes to the link" | `WINDOWS.md:26` | CI only counts pixels (`build_windows.py:69-79`); the decode was done once by hand |
| Phone connects only to the paired computer on the local network | `WIFI.md:11` | Any IPv4 from a QR is accepted (AUDIT-021) |
| Window closes after 20 failed attempts | `WIFI.md:35` | Only tag mismatches and closes count; the announcement and card are not updated (AUDIT-021) |
| Status 3 "busy" | `WIFI.md:42` | Never sent |

Plus:
- internal contradictions in `docs/PHASES.md` (several rows stale against ROADMAP and research notes);
- `ARCHITECTURE.md` describes `native/` as macOS-only;
- `DATA.md` lacks the Wi-Fi keys and the kit reports.

**Fix:** one documentation pass, then a single "permissions and stored data" page linked from the others. **Effort:** Small.

---

## AUDIT-020: Smaller Android issues (P3)

| # | Issue | Where | Conf. | Fix |
|---|---|---|---|---|
| a | A stalled USB write is not unblocked by `close()`; the thread and socket linger until adbd drops its end (the session itself ends correctly) | `LiveTransport.kt:26-27, 163-169` | High | `shutdownInput/Output` before `close()` |
| b | UI says "Waiting for OBS" while the camera is already starting; no "Stopping…" state. If `camera.close()` hangs, controls stay locked | `LiveStreamer.kt:97, 164, 288-298`; `MainActivity.kt:275, 289-292` | Confirmed | Post a "Starting camera" state; disable the button with "Stopping…"; a stop watchdog |
| c | Start can crash on an unguarded `getCameraCharacteristics` on the main thread (policy-disabled camera, external camera removed) | `MainActivity.kt:298, 351` | Confirmed code, Medium trigger | Catch it, show a message, rescan |
| d | Preview can be dropped silently if the surface is not ready, which conflicts with "fail clearly" | `MainActivity.kt:90, 120-122, 288`; `LiveStreamer.kt:199` | Low–Medium | Wait for the surface briefly or report "Preview unavailable" |
| e | The writer polls every 10 ms; `offer()` never signals (up to 10 ms extra latency, 100 wake-ups/s when idle) | `LiveTransport.kt:137, 159` | Confirmed | Notify in `offer()` |
| f | On-screen fps is a whole-session average; the bitrate badge shows the target even when `setParameters` failed | `FrameStats.kt:32`; `LiveStreamer.kt:83-90` | Confirmed | Rolling fps; show applied values only |
| g | The research recorder can leave the camera open after a very early Stop (late `onOpened` discarded) | `BenchmarkRecorder.kt:175-184, 266, 311` | High | Same main-thread callback pattern as LiveStreamer |
| h | A second `onOutputFormatChanged` with identical parameter sets, or a negative PTS, ends the session | `LiveTransport.kt:156`; `FrameStats.kt:15` | Low | Accept identical repeats; count bad PTS |
| i | A stopped session's Wi-Fi attempt can finish a handshake; USB-only errors cancel a viable Wi-Fi attempt | `LiveTransport.kt:104, 112, 118-120` | Confirmed | Keep and close the in-flight socket; scope USB errors |
| j | `LiveTransport.token()` unsynchronised between the provider thread and `onCreate`; file I/O on the main thread | `LiveTransport.kt:50-60`; `PairingProvider.kt:32` | Medium | `@Synchronized`, move I/O off main |
| k | Scope gap: 60 fps only as 1080p constrained high-speed; 4K60 never offered; controls in high-speed requests may be ignored | `Capabilities.kt:120-135`; `LiveStreamer.kt:245-262` | Confirmed | Product decision; document |

## AUDIT-021: Smaller Wi-Fi and pairing issues (P3)

| # | Issue | Where | Conf. | Fix |
|---|---|---|---|---|
| a | 20 failures clear the pairing secret, but the mDNS `pair=1` flag and the QR card stay up; timeouts don't count | `wifi_server.cpp:172-185, 128-129` | Confirmed | Call `closePairing()` and post the "closed" status on lockout |
| b | The pairing code is written to the OBS log (single-use and dead after 3 minutes) | `plugin.mm:71+31`; `plugin_win.cpp` same | Confirmed | Log a redacted line |
| c | The phone's handshake has a per-read timeout only; a trickling rogue responder can hold it past the 30 s start window and learn the phone id | `WifiLink.kt:86, 106, 114`; `WifiDiscovery.kt:76-86` | Confirmed | Total deadline ≈4 s; try stored addresses in parallel |
| d | The phone accepts any IPv4 from a QR (including public addresses), and there is no network binding to Wi-Fi | `WifiLink.kt:63-64, 74` | Confirmed | Accept private/link-local only; bind to the Wi-Fi network |
| e | The pairings file is regenerated (all pairings lost) on any read failure; there is a load-modify-save race; the accept byte is sent before the save | `wifi.mm:37-43`; `wifi_win.cpp:70-81`; `wifi_server.cpp:193-202` | Confirmed | Regenerate only when absent; one mutex; save then accept |
| f | Keys and token stored as plaintext hex (sandbox/0600/inherited ACL); no Keystore/Keychain/DPAPI; no "reset USB key"; "Forget" doesn't end a live session | `WifiLink.kt:40-48`; `LiveTransport.kt:50-60`; `wifi.mm:55-57`; `wifi_win.cpp:85-98` | Confirmed | Platform key stores; create files 0600 before rename |
| g | macOS `bonjour` handle used from two threads without a lock (Windows has one) | `wifi.mm:84-104` | Confirmed | Mutex as on Windows |

## AUDIT-022: Smaller native receiver and plugin issues (P3)

| # | Issue | Where | Conf. | Fix |
|---|---|---|---|---|
| a | The watchdog's "Not responding…" status can stay after recovery (the worker de-duplicates against its own last text); `Activity::set` stores the step before the time | `plugin_win.cpp:165-175`; `adb_session.cpp:29, 52` | High | Store time first; restore the worker's last status on progress |
| b | adb that cannot be started (blocked by antivirus) is reported as "Ready… connect a USB cable" | `adb_session.cpp:86-104`; `adb_win.cpp:200`; `adb.mm:68` | Confirmed | Report "adb could not be run" |
| c | Windows ignores the stream's colour range/matrix: YUVJ420P accepted, always output as BT.709 limited | `receiver_win.cpp:111`; `plugin_win.cpp:77-78` | Medium | Map the AVFrame colour fields |
| d | No per-frame `@autoreleasepool` on macOS; possible slow growth over long sessions | `receiver.mm:208-230` | Low | Pool per frame; check with a 2-hour soak |
| e | `leaked` keeps the `Source` object but not the `obs_source_t` | `plugin_win.cpp:148-151, 123` | Low | Hold a strong/weak source reference in `Link` |
| f | The `wifi` pointer is written on the video thread and read on the UI thread | `plugin.mm:84, 95, 60, 137`; `plugin_win.cpp:129, 161` | Confirmed | Guard it or use `shared_ptr` + atomic load |
| g | `C:\platform-tools\adb.exe` is searched before PATH and can be planted by any local user; `adb_path` from a scene file is run as given (no shell injection) | `adb_win.cpp:153-157`; `adb.mm:25` | Confirmed | Drop the fixed root path; prefer SDK/PATH |
| h | A Windows connect timeout is treated as "refused" and forgets the forward without removing it | `receiver_win.cpp:254`; `adb_session.cpp:136-138` | Confirmed | `dropForward` on timeout |
| i | USB worker and Wi-Fi server write one status string with separate de-duplication; each can leave the other's text stale | `plugin.mm:79, 84-86` | Confirmed | Two status lines or one owner |
| j | Duplicate PTS, or a key flag that disagrees with the NAL type, ends the session (strict by design; risky on unusual encoders) | `stream.hpp:47`; `receiver.mm:165` | Low | Keep strict; collect device reports |
| k | The Windows plugin links OBS's FFmpeg import libraries; an OBS with a new FFmpeg major will not load it | `build_windows.py:159-176, 345` | Confirmed | Document the supported OBS range; check on load |
| l | Clang analyzer: six nullability warnings on Objective-C conversions (three are AUDIT-003's root cause) | `receiver.mm:186, 201`; `wifi.mm:27, 33, 64`; `pairing_card.mm:11` | Confirmed | Nil-safe conversions |

## AUDIT-023: Release, kit, CI and maintainability (P3)

| # | Issue | Where | Fix |
|---|---|---|---|
| a | The release script only warns on a dirty tree and doesn't check the tag; HEAD is 17 commits past `v0.3.0-alpha.5` with the same version strings; `CFBundleVersion` fixed at "3"; no build id in plugin logs; checksums and tags unsigned | `release.py:77-79`; `app/build.gradle.kts:21-22`; `build_native.py:15, 87` | Refuse dirty/untagged trees; derive the version from `git describe`; log the version |
| b | Kit cleanup: the PC's existing `.android` adb key stays authorised on the phone; deletion by creation time inside pre-existing folders could remove a third party's files; PowerShell history keeps the typed line | `windows_kit.ps1:51, 64, 129-136` | Keep adb keys in the kit folder; marker files; tell the operator to revoke USB authorisations |
| c | Kit server binds 0.0.0.0, has no socket timeout, accepts a negative Content-Length (unbounded read after the token), and reads the 240 MB zip into memory per request | `windows_kit.py:118, 124-127, 144` | Bind the LAN address; validate length; timeout; stream the file |
| d | CI inputs: actions pinned by tag (incl. third-party `android-actions/setup-android`); unpinned runner images, MSVC, VC runtime; no Gradle dependency verification; no job timeouts; `gh run download` doesn't verify the run's commit/event | `ci.yml`; `windows_kit.py:169` | Pin by SHA; Dependabot; verify the run with `gh run view --json` |
| e | Hardening: Windows lacks `/guard:cf /sdl /CETCOMPAT`; the Mac plugin has an absolute rpath to `/Applications/OBS.app`; Android minify off, lint warnings not errors | `build_windows.py:325`; `build_native.py:75` | Add flags; review the rpath |
| f | Tests: fixed sleeps; a bind-then-close free-port race; `*.pcam` not marked binary in `.gitattributes`; the fake adb hangs only on `devices` | `test_native.py:189`; `WifiInteropTest.kt:44`; `build_windows.py:222, 273`; `fake_adb.cpp:26` | Readiness signals; `*.pcam binary`; broader fake |
| g | Maintainability: lines up to ~320 characters; `plugin.mm`/`plugin_win.cpp` share ~70 identical lines; two download-and-verify implementations; build/release scripts untested | various | Shared plugin core; one fetch helper; wrap long lines when touched |
