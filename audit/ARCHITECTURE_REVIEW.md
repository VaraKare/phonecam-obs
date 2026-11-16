# Architecture review

Reconstructed from the code at `9f0b0cb`, not from the docs. Line references are in [FINDINGS.md](FINDINGS.md) and the reviewer notes summarised there.

## Components

```mermaid
flowchart LR
  subgraph Phone["Android app (Kotlin)"]
    UI[MainActivity / Settings / Scan]
    LS[LiveStreamer<br/>Camera2 + MediaCodec]
    LQ[LiveQueue<br/>6 frames, 8 MiB]
    LT[LiveTransport<br/>USB listener + Wi-Fi client]
    WC[WifiCrypto / WifiLink / WifiDiscovery]
    PP[PairingProvider<br/>uid 2000 only]
  end
  subgraph Desktop["OBS plugin (macOS .mm / Windows .cpp)"]
    PL[plugin.mm / plugin_win.cpp<br/>OBS source]
    AS[adb_session.cpp<br/>find, pair, forward, restart]
    AP[adb.mm / adb_win.cpp<br/>process limits, server]
    RX[receiver.mm / receiver_win.cpp<br/>decode + rotate]
    ST[stream.hpp + protocol.hpp<br/>PCAM v3]
    WS[wifi_server.cpp + wifi_crypto*.hpp<br/>listener, handshake, records]
    WP[wifi.mm / wifi_win.cpp<br/>store, mDNS, names]
  end
  UI --> LS --> LQ --> LT
  LT --> WC
  PP -. token .-> AS
  LT == "USB: adb forward → localabstract" ==> RX
  WC == "Wi-Fi: TCP 47183, encrypted" ==> WS
  PL --> AS --> AP
  AS --> RX --> ST
  PL --> WS --> ST
  WS --> WP
  RX --> PL
```

## Video pipeline (as implemented)

```mermaid
flowchart TD
  A[Start tapped] --> B[LiveTransport waits for a connection<br/>USB accept + Wi-Fi connect in parallel<br/>30 s deadline]
  B -->|authenticated sink claims the session| C[LiveStreamer.prepare on capture thread<br/>encoder by name, CBR, 1 s GOP, no B-frames, BT.709 limited]
  C --> D[Camera2 session: encoder surface + optional preview<br/>TEMPLATE_RECORD repeating request]
  D --> E[MediaCodec callback on capture thread<br/>copy each access unit to a ByteArray]
  E -->|offer, never blocks| F[LiveQueue: 6 frames / 8 MiB<br/>overflow drops rest of GOP, resumes at key frame]
  F --> G[writer thread: config once, then frames<br/>write deadline 750 ms USB / 3 s Wi-Fi]
  G -->|USB| H[adb forward → 127.0.0.1:port]
  G -->|Wi-Fi| I[AES-256-CTR + HMAC record per message]
  H --> J[readExact / RecordReader<br/>50 ms polls, stop checked]
  I --> J
  J --> K[header: magic, v3, type, length ≤ 4 MiB, flags ≤ 15]
  K --> L[nals: Annex-B, ≤ 256 units]
  L -->|macOS| M[VideoToolbox decode, synchronous wait<br/>VTPixelRotationSession if turned/mirrored]
  L -->|Windows| N[FFmpeg D3D11VA → GPU→CPU copy<br/>CPU rotation in 2–3 async pieces]
  M --> O[obs_source_output_video<br/>unbuffered, arrival timestamp]
  N --> O
```

**Thread boundaries:**

| Side | Thread | Owns |
|---|---|---|
| Phone | main | UI, camera state callback |
| Phone | `phonecam-live-capture` | Camera, codec, encode callbacks, release in `finish()` |
| Phone | `phonecam-usb-writer`, `phonecam-wifi-writer` | Socket I/O |
| Phone | watchdog executor | 250 ms deadline checks |
| Desktop | One worker per visible source | adb loop, receive, decode, rotate, output |
| Desktop | One Wi-Fi thread per visible source | Listen, handshake, decode |
| Desktop | OBS video thread | Calls `show` / `hide` / `video_tick` |
| Desktop | OBS UI thread | Calls `properties` / `update` |

**Buffering:**
- The phone queue holds 6 frames.
- The desktop holds no frame queue: one message is in memory at a time, and the OBS source is unbuffered.
- Socket and adb buffers sit in between and grow only under back-pressure (AUDIT-009).

**Clock domains:**
- Phone PTS is in microseconds and is forwarded unchanged; the desktop uses it only for ordering checks.
- OBS timestamps are taken at arrival (`os_gettime_ns`).
- No latency is inferred from encoder timestamps, which matches the project rule.

## Connection and session state

USB, desktop side (`adb_session.cpp`):

```mermaid
stateDiagram-v2
  [*] --> FindingAdb
  FindingAdb --> FindingAdb: adb missing (3 s)
  FindingAdb --> StartingAdb
  StartingAdb --> ListingPhones: prepareAdb (Windows may start nodaemon server)
  ListingPhones --> RestartingAdb: 2 unanswered calls
  ListingPhones --> SearchingWifi: no ready device (every 6 s)
  SearchingWifi --> ListingPhones
  ListingPhones --> ReadingKey: device(s) ready
  ReadingKey --> Forwarding: token found
  ReadingKey --> ListingPhones: no app / timeout
  Forwarding --> Linked: forward tcp:0
  Linked --> Linked: NotStreaming (phone not started), retry 1 s
  Linked --> ListingPhones: NotStreaming > 15 s (re-pair)
  Linked --> ListingPhones: connection refused / timeout (forward gone)
  Linked --> Live: config + first frame
  Live --> ListingPhones: any error → "Live ended", drop forward
  RestartingAdb --> FindingAdb
  Linked --> [*]: stop (hide/destroy) → drop forward
```

Phone side (`LiveStreamer` / `LiveTransport` / `MainActivity`):

```mermaid
stateDiagram-v2
  [*] --> Idle
  Idle --> Waiting: Start (permission granted)
  Waiting --> Waiting: wrong key (≤ 4)
  Waiting --> Stopped: 30 s, 5 wrong keys, bind error, Stop
  Waiting --> CameraStarting: first authenticated sink (USB or Wi-Fi)
  CameraStarting --> Live: first encoded frame (≤ 15 s)
  CameraStarting --> Stopped: camera/encoder error, timeout
  Live --> Stopped: Stop, pause/background, write stall, encoder stall 5 s, error
  Stopped --> Idle: live cleared
  note right of Waiting
    UI shows "Waiting" for both Waiting and CameraStarting.
    No explicit "Stopping" state.
    AUDIT-001: Stop here may leave the USB listener bound.
  end note
```

Wi-Fi pairing and session (both ends):

```mermaid
sequenceDiagram
  participant P as Phone
  participant C as Computer (plugin)
  Note over C: listens on 47183 (all interfaces), mDNS id + pair flag
  P->>C: ClientHello: PCW1, mode, computer id, phone id, P-256 point, nonce (118 B)
  alt unknown id / no window
    C-->>P: PCW1 + refusal code (5 B)
  else
    C->>P: ServerHello: PCW1, 0, point, nonce, tagM (134 B)
    Note over P: verifies tagM (code or long-term key) before sending anything derived
    P->>C: tagP + name length + name (name not authenticated: AUDIT-003)
    Note over C: verifies tagP (constant time)
    C->>P: 0 (accepted)
    alt pairing
      Note over P,C: both store HKDF(…, "pcam pairing") as the long-term key
    else session
      P->>C: encrypted PCAM records (u32 len, AES-256-CTR, HMAC-128 over seq‖len‖ct)
    end
  end
```

## Ownership and lifetime analysis

| Resource | Owner | Released by | Assessment |
|---|---|---|---|
| Camera, session, codec, input surface | Phone capture thread | `LiveStreamer.finish()`, idempotent and ordered | Good; a late `onOpened` is closed |
| USB listener / accepted socket | USB writer thread | `LiveTransport.close()` | Close does not wake a blocked `accept`/`write` (AUDIT-001, 020a) |
| adb forward | Desktop worker | `dropForward()` on drop/exit | Good; one spawn per hide (AUDIT-002) |
| adb server (Windows) | Plugin job object | OBS exit / `restartAdb` | Good; start not bounded (AUDIT-010) |
| VT session, pixel buffers | `receiver.mm` objects | RAII / CFRelease on all paths | Good (verified, plus ASan run) |
| AVFrame/AVPacket/hw device | Windows `Decoder` | Destructor | Good |
| Worker thread vs `Source` | macOS: join; Windows: `Link` hand-off | `end()` | macOS unbounded (AUDIT-002); Windows safe but slow |
| Pairing store | File per OS user | Load/save per call, no lock | Race and wipe on read failure (AUDIT-021e) |

## Strengths

- **One wire format for USB and Wi-Fi.** Wi-Fi only wraps PCAM in records, so decoding, ordering and recovery are shared code (`stream.hpp`).
- **Strict validation.** Untrusted lengths are bounded before allocation, and configuration, dimensions, key flags and ordering are checked. Fuzzing confirmed it fails safely.
- **Fresh frames win under congestion.** The bounded phone queue drops whole groups of frames and resumes at a key frame, so old frames never pile up; the desktop holds no queue.
- **Portable crypto.** One handshake definition with three platform back-ends, cross-checked by shared test vectors and live interop tests (Kotlin ↔ Mac locally, Kotlin ↔ Windows in CI).
- **No dependency sprawl.** The phone uses only ZXing. The desktop uses only OS APIs plus OBS's own FFmpeg on Windows. All downloads are pinned and checksummed.
- **Honest gates.** Build and test output repeatedly states what is not physically proven.

## Weaknesses

1. **Lifecycle logic sits inside the OBS source.** Each source owns adb, forwarding, the Wi-Fi listener and decoding. This causes blocking on hide (AUDIT-002), multi-source conflicts (AUDIT-011) and two writers of one status line (AUDIT-022i). A module-level connection manager would separate "connection" from "display".
2. **Duplicated plugin code.** `plugin.mm` and `plugin_win.cpp` share about 70 identical lines and have already diverged: Windows has the `Link` hand-off and watchdog, macOS does not.
3. **USB authentication is one-way and sends the secret in clear**, while Wi-Fi has a full mutual handshake (AUDIT-007). Two security models for one product.
4. **Errors only travel one way.** The receiver cannot tell the phone why it closed (AUDIT-013).
5. **No shared schema for the wire format.** Header layout and limits are written separately in Kotlin (`LiveTransport.header`) and C++ (`protocol.hpp`). They agree today (checked field by field), but only the integration tests guard that.
6. **Platform files mix concerns.** `wifi.mm` / `wifi_win.cpp` hold storage, naming, addresses and mDNS together, with different locking.

## Cross-component contracts (checked field by field)

| Contract | Phone | Desktop | Agreement |
|---|---|---|---|
| Magic, version, type | `LiveTransport.kt:48-49` | `protocol.hpp:14-19` | Yes (v3, types 1/2) |
| Lengths | config ≤ 64 KiB, frame ≤ 4 MiB (`:155, 162`) | same (`:20-21`) | Yes |
| Flags | key, 2-bit turns, mirror (`:45`) | same (`receiver.mm:176`, `receiver_win.cpp:220`) | Yes |
| Byte order | big-endian `ByteBuffer` | `be32/be64` | Yes |
| Config payload | codec, w, h, rotation, Annex-B csd (`LiveStreamer.kt:141-145`) | same (`receiver.mm:118-123`) | Yes (no frame rate field, despite `ARCHITECTURE.md`) |
| Sequence / PTS | increasing; gap → drop to key frame | gap → wait for key frame | Yes |
| Token line | 64 hex + LF, 2 s, constant-time | sent first | Yes (one-way, AUDIT-007) |
| Wi-Fi handshake and records | `WifiCrypto.kt`, `WifiLink.kt` | `wifi_crypto.hpp`, `wifi_server.cpp` | Yes (shared vectors, interop tests); name outside the MAC |
| Error reporting | maps EPIPE to "OBS stopped receiving" | closes without a reason | One-way (AUDIT-013) |

**Recommendation:** add a small table-driven test that encodes headers from the Kotlin side and parses them with `protocol.hpp`, using the fixture generator, so the two definitions can't drift silently.

## Future compatibility (only what current design decisions obstruct)

| Future item | Obstacle in current design | Needed now? |
|---|---|---|
| Multiple sources / multi-camera | Per-source connection ownership; first phone always wins (AUDIT-011) | No; fix the conflict message now, build the manager later |
| Windows receiver (in progress) | Duplicated plugin code; GPU read-back cost (AUDIT-009) | Partly; see the roadmap |
| Audio | No reverse channel and arrival timestamps. A/V sync will need a shared clock mapping (already noted in `docs/PLAN.md`) | No |
| iPhone sender | Protocol is platform-neutral; USB depends on adb (no iOS equivalent) | No |
| Other transports | `stream.hpp`'s reader abstraction already supports them | No |
