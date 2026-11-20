# Patch decision matrix

Effort scale:
- **S:** under half a day, with tests.
- **M:** one to two days.
- **L:** more than two days.

Risk is the chance the fix itself breaks something.

| Finding | Priority | Root cause | Option A (minimal) | Option B (balanced) | Option C (architectural) | Recommended | Effort | Risk |
|---|---|---|---|---|---|---|---|---|
| 001 USB listener stays bound | P1 | `close()` does not wake a blocked `accept()` | `shutdown()` the listener fd (or self-connect) before close | A + bounded join of writer threads; bind failure non-fatal while Wi-Fi tries | One process-lifetime listener | **A**, plus the join if small | S | Low |
| 002 Hide blocks OBS video thread | P1 | Workers joined from `hide`; adb loop ignores `stop` | Check `stop` between adb calls; shorter Windows wait | Shared `Link` hand-off on both platforms, incl. the Wi-Fi server | Module-level connection manager | **B** (+ A's checks) | M | Medium (threading) |
| 003 Phone name crash / unauthenticated | P2 | `cleanName` keeps invalid UTF-8; `@()` nil; name outside MAC | Valid-UTF-8 filter, nil-safe conversion, catch-all | Bind the name into `tagP`/keys (PCW2) | — | **A** now, B at the protocol bump | S / M | Low / Medium |
| 004 Uncaught exceptions abort OBS | P2 | No top-level catch in threads/callbacks; store I/O in hot paths | Catch-all in every thread and callback | A + in-memory pairings under one lock | — | **B** | S–M | Low |
| 005 LAN flood / slot hold | P2 | Single inline handshake; pre-auth errors reach the status line | Silent pre-auth failures, rate-limited counter | Per-connection handshake threads, "busy" status 3 | Non-blocking event loop | **A + B** | M | Medium |
| 006 Listener always on | P2 | Default true; `update()` ignores it; `INADDR_ANY` | Apply the switch in `update()` | Listen only when paired/pairing; private interfaces | — | **B** | S | Low |
| 007 One-way USB key | P2 | Receiver sends the key first | Mutual HMAC challenge (protocol bump) | PCW1 handshake over USB with K = key | — | **B** at the protocol bump | M | Medium (compatibility) |
| 008 adb connect to any service | P2 | Connects to every advertised address | Only previously seen instance names | — | — | **A** | S | Low |
| 009 Receiver never sheds work | P2 | Serial decode→copy→rotate→output | Skip copy/rotate/output when the next frame is waiting | A + separate map timing, explicit adapter | GPU rotation / texture output | **A**, then decide C from timings | S / L | Low / High |
| 010 Windows server start unbounded | P2 | `CreateProcessW` under `serverLock` | Helper-thread bound; release the lock while waiting | Supervisor thread | — | **A** | S | Low |
| 011 Several sources / phones | P2 | Per-source connection ownership | Registry: one owner, clear message for others | Forward to all phones, optional phone property | Connection manager | **A** now | S | Low |
| 012 adb restart too eager | P2 | Device-level timeouts count as server stalls | Count only server calls; probe `host:version` before killing | adb socket protocol health checks | — | **A** | S | Low |
| 013 End reason overwritten | P2 | Status de-dup replaces the reason with "Ready" | Sticky "Last session ended" line | Reason byte to the phone (protocol bump) | — | **A**, B later | S | Low |
| 014 Config change ends live | P2 | Narrow `configChanges` | Add `uiMode`, `fontScale`, etc. | Keep the stop reason across recreation | Session owned outside the Activity | **A + B** | S | Low |
| 015 Personal data in history/binary | P2 | Edits without history rewrite; unstripped `-g` build | Strip the binary; accept the history | A + `git filter-repo` and force-push | Fresh public repository | **A**; user decides on B | S / M | Low / High (history) |
| 016 Kit over HTTP, verbose reports | P2 | Plain HTTP + `iex`; broad report capture | Hash check, slimmer reports, honest docs | HTTPS with pinned certificate | USB-stick kit | **A** before tomorrow | S | Low |
| 017 No macOS CI | P2 | Build needs installed OBS | Portable tests + syntax check on a macOS runner | Pinned OBS dmg, full build + `test_native.py` | CMake + plugin template | **B** | M | Low |
| 018 Missing licence notices | P2 | Notices not packaged | Update NOTICE, add files to the Windows artifact | Licences screen in Settings | Generated notices | **A + B** | S | Low |
| 019 Doc contradictions | P2 | Docs not updated with code | One doc pass | Single permissions/data page | — | **B** | S | None |
| 020–023 P3 groups | P3 | Various | See [FINDINGS.md](FINDINGS.md) tables | — | — | Fold into the stage that touches the same files | S each | Low |

## High-impact trade-offs

**AUDIT-002 (B vs C).**
- **B** keeps today's structure (each source owns its connection) and only changes how a source lets go of its threads. It is the smallest change that removes the OBS-hang class on both platforms.
- **Second-order effects of B:**
  - Threads may outlive a hidden source for a few seconds. Safe today: callbacks are gated by `Link::owner`, and libobs 33 never unloads plugins. An OBS that starts unloading plugins would need `keepLoaded()` on macOS too.
  - Two quick hide/show cycles can briefly run two connection threads. They compete for the same adb forward, so `dropForward` must remove only its own port, which it already does.
- **C** also fixes several sources (011), the shared status line (022i) and future multi-camera. But it changes every lifecycle path in both plugins; do it when multi-camera is actually scheduled.

**AUDIT-007 / 003 B / 013 B (protocol bump).**
- All three need matched app and plugin releases. Bundle them into one version (PCAM v4 / PCW2) so users re-install once.
- The existing Wi-Fi long-term keys can stay valid if the key schedule is unchanged and only the transcript gains the name.
- USB needs no re-pairing, because the key is re-read every time.
- The receiver already refuses older senders with a clear message (verified by `test_older_sender_rejected`), but that message is currently overwritten (013 A should land first).

**AUDIT-009 (A vs C).**
- **A** changes no output path; it only skips work the viewer would never see. The decoder still decodes every frame, because later frames reference it. The cost is that OBS shows fewer frames when the PC is too slow, which is better than freezes plus a lower bitrate.
- **C** (GPU rotation or texture output) may be needed for smooth 4K on low-end PCs, but it is Windows-only and should be driven by the per-step timing lines from a real PC.

**AUDIT-015 B (history rewrite).**
- **Benefit:** removes the identifiers from future clones.
- **Costs:**
  - existing clones and forks keep them;
  - all commit ids change, so CI run links and tag hashes in the docs go stale;
  - the published v0.3.0-alpha.5 release must be re-tagged.
- Whether the device identifier and network name matter enough is the owner's call.
