# Security audit

Scope:
- the direct Wi-Fi pairing and encryption protocol (phone and both desktop platforms);
- the USB key and its provider;
- local storage of secrets;
- the network listener;
- adb handling;
- the Windows test kit;
- supply chain.

This document is defensive analysis only. It contains no exploit code and no secret values.

## Threat model

**Assets:**
- the live camera picture (confidentiality);
- what appears in the OBS output (integrity);
- OBS availability during a stream;
- Wi-Fi long-term keys;
- the USB key;
- the borrowed Windows PC used for testing.

**Trust boundaries:**
1. Local network to the plugin's TCP listener (port 47183, all interfaces).
2. Other apps on the phone to the abstract socket `phonecam-live-v1` and the exported `PairingProvider`.
3. Local processes and users on the computer to adb, the forwarded port and `wifi.json`.
4. The developer's Mac to a borrowed Windows PC (test kit over HTTP).
5. Upstream downloads to the build (OBS, SIMDe, FFmpeg, qrcodegen, platform-tools, Gradle, Actions).

| Attacker | Can | Cannot |
|---|---|---|
| Same-LAN passive | See mDNS names and ids, handshake public keys, record lengths | Decrypt video; test pairing-code guesses offline without an active exchange |
| Same-LAN active | Flood or occupy the listener (AUDIT-005). Get one `tagM` per connection while a pairing window is open: an offline test of an 80-bit random code, infeasible. Rewrite the unauthenticated phone name, crashing OBS on the Mac (AUDIT-003). Offer fake Wireless-debugging services (AUDIT-008). Tamper with the Windows kit script (AUDIT-016) | Impersonate a paired phone or computer; inject or replay records |
| Malicious app on the phone | Possibly take the abstract socket name while PhoneCam is idle and receive the USB key (AUDIT-007, needs device check) | Read the key via the provider (uid check is sound); read app-private files |
| Local process on the computer (same user) | Read `wifi.json` and the USB key via adb (documented in `DATA.md`) | — |
| Stolen pairing file | Impersonate either side, because the key is symmetric (no key-compromise-impersonation resistance) | Decrypt past sessions (forward secrecy) |
| Upstream supply chain | Only via a compromised pinned artifact *and* a matching checksum | Swap a download unnoticed (SHA-256 pinned) |

## Attack surface

| Surface | Exposure | Pre-auth parsing |
|---|---|---|
| TCP listener (`wifi_server.cpp`) | Every visible source; all interfaces; on by default (AUDIT-006) | Fixed 118-byte hello, 33-byte finish, name ≤ 64; record length bounded before allocation |
| mDNS records | Instance name with computer name, `id`, `pair` flag | — |
| Abstract socket `phonecam-live-v1` | Only while Start is waiting/live (and after a stuck Stop, AUDIT-001) | 65-byte key line, 2 s, 5 failures |
| `PairingProvider` (exported) | Every app; answers only uid 2000 | Path check only |
| QR parser (`PairingLink.parse`) | Camera input | Length ≤ 512, strict fields |
| `wifi.json` parser (`wifi_store.hpp` / `NSJSONSerialization`) | Local file | Depth 16, ≤ 1 MiB, strict |
| `adb mdns services` → `adb connect` | LAN advertisements | Addresses validated |
| Kit HTTP server | LAN, token in URL | Token-gated routes, report name pattern |

## Authentication and encryption assessment

**Protocol:**
- PSK-authenticated ephemeral ECDH (P-256).
- PSK = SHA-256 of an 80-bit random code while pairing, then a 256-bit long-term key.
- HMAC-SHA-256 key confirmation, both directions.
- HKDF-Expand key schedule.
- AES-256-CTR with a truncated 128-bit HMAC-SHA-256 per record.

| Property | Result | Evidence |
|---|---|---|
| Mutual authentication | Yes | `tagM` checked by the phone before it sends anything derived (`WifiLink.kt:116`); `tagP` checked by the computer (`wifi_server.cpp:189`); both constant-time |
| Forward secrecy | Yes | Fresh ephemerals per handshake on all three platforms |
| Transcript binding | Mostly: mode, both ids, both points, both nonces and status are bound; **the phone name is not** | `wifi_crypto.hpp:96-105` |
| Replay / reorder / tamper | Rejected: record counter in the MAC and IV; tag checked before decryption | `wifi_crypto.hpp:108-127` |
| Nonce uniqueness | Unique IV per record under a fresh per-session key; 64-bit block counter cannot wrap at 4 MiB | `wifi_crypto_apple.hpp:22-33`, `wifi_crypto_win.hpp:48-66` |
| Hand-built pieces | HKDF-Expand matches RFC 5869; the BCrypt CTR-from-ECB keystream matches CommonCrypto's; the BCrypt raw secret is reversed to big-endian | Verified by shared vectors and Kotlin ↔ Windows interop in CI |
| Public-key validation | Format and length checked, then platform import (BCrypt/SecKey/JCA reject off-curve points; tested) | `wifi_test.cpp`, `WifiCryptoTest.kt` |
| Pairing code strength | 80 bits from the OS CSPRNG, 3-minute single-use window | Equivalent to TLS 1.3 PSK-DHE / Noise NNpsk0 for this use. A PAKE (CPace/SPAKE2) would remove the offline oracle and allow a shorter code; not needed at 80 bits |
| USB authentication | One-way: the receiver reveals the key to whatever answers | AUDIT-007 |

**Conclusion:** the cryptographic design is sound for its purpose, and the three implementations agree. The weaknesses are around it:
- the unauthenticated name;
- handling of unauthenticated peers;
- the always-on listener;
- the one-way USB key.

## Input validation

- **Wire parser:** bounded and strict. 600 mutated streams under ASan+UBSan produced no crash, hang or report.
- **Handshake reads:** fixed sizes with timeouts (5 s; 30 s in pairing mode). The timeouts are what an attacker can use to hold the single slot (AUDIT-005).
- **QR and discovery input:** strict on the phone, but any IPv4 address is accepted, including public ones (AUDIT-021d).
- **Pairing store:** robust parser; on a read failure the store is regenerated, losing pairings (AUDIT-021e).

## Security findings

| ID | Sev | Title |
|---|---|---|
| AUDIT-003 | P2 | Unauthenticated phone name; invalid UTF-8 aborts OBS on the Mac (reproduced) |
| AUDIT-005 | P2 | Any LAN host floods the status/log and blocks the listener |
| AUDIT-006 | P2 | Listener on by default on all interfaces; switch not applied |
| AUDIT-007 | P2 | USB key sent to an unauthenticated endpoint (device check needed) |
| AUDIT-008 | P2 | `adb connect` to any advertised Wireless-debugging address (Medium) |
| AUDIT-015 | P2 | Personal details in public Git history and the released Mac binary |
| AUDIT-016 | P2 | Kit runs a script over plain HTTP; reports exceed the documented data |
| AUDIT-021 | P3 | Lockout leaves the window advertised; pairing code logged; handshake without an overall deadline; any IPv4 accepted; store wiped on read failure; plaintext keys; mDNS handle unlocked on macOS |
| AUDIT-022g | P3 | Plantable `C:\platform-tools\adb.exe` searched before PATH |
| AUDIT-023c/d | P3 | Kit server hardening; CI actions pinned by tag; the kit downloads a CI artifact without checking which commit and event produced it |

Full details and fix options are in [FINDINGS.md](FINDINGS.md).

## Dependencies and secret handling

- **Downloads:** every one is pinned by version and SHA-256 and fetched over HTTPS; archive extraction rejects `..`, absolute paths and links. The CI token is read-only; no secrets are used in CI; no `pull_request_target`.
- **Release signing key:** lives outside the repository (`~/.phonecam-obs`), is created with mode 0600 and never replaced. One gap: `release.py` passes the key password to `keytool` on its command line (AUDIT-023a).
- **Repository history:** contains no keys, tokens or keystores. It does contain personal identifiers (AUDIT-015).
- **Advisory checks:** not performed (offline). Recommended before release: check AGP 8.13.0, Kotlin 2.3.0, ZXing 3.5.3, the FFmpeg in obs-deps 2026-10-23, and platform-tools r37.0.1.

## Recommended mitigations, in order

1. **AUDIT-003 A:** valid UTF-8 names and nil-safe conversion, plus top-level catches (AUDIT-004).
2. **AUDIT-006 B:** listener only when paired or pairing, private interfaces only, and the switch applied immediately.
3. **AUDIT-005 A+B:** silent pre-auth failures, per-connection handshake threads, a "busy" reply.
4. **AUDIT-016 A:** before tomorrow's Windows session: zip hash check, slimmer reports, a "trusted home network only" note.
5. **AUDIT-015 A:** strip the Mac binary. Then decide on rewriting history.
6. **Next protocol version (PCW2 / PCAM v4), together:**
   - authenticate the phone name (AUDIT-003 B);
   - mutual USB authentication (AUDIT-007 B);
   - a close-reason byte (AUDIT-013 B).

## Security regression tests to add

- Native Wi-Fi tests:
  - a hostile name;
  - 100 bare connects;
  - one silent connection held open while a real pairing must still succeed;
  - lockout updates the mDNS flag;
  - the toggle closes the port;
  - an unwritable store does not abort.
- Kotlin: `PairingLink.parse` rejects public IPv4 addresses; the handshake has an overall deadline.
- Receiver: a fake phone endpoint without the key causes a disconnect (after AUDIT-007).
- Kit: the script has no `devices -l`, does not send the whole OBS log, and checks the zip hash.
- Release: `strings` and `nm` on the Mac binary show no home paths.
