# How PhoneCam OBS works

A guide for newcomers: what happens between the phone's camera and the OBS canvas, and what each file in the repository does. For installing and using the app, see the [README](../README.md). For building, see [Building from source](DEVELOPMENT.md).

## The big picture

```
Android phone (app/)                                   Mac (native/, an OBS plugin)
─────────────────────                                  ────────────────────────────
Camera2 camera ──► hardware video encoder ──► small    USB cable     adb forward
   │               (H.265 or H.264)          queue ──► (adb) ──────► tcp:<port> ──► VideoToolbox
   │                                         (2 frames)                            hardware decoder
   ▼                                                                                     │
phone preview                                                         rotate / mirror ◄──┘
                                                                            │
                                                                            ▼
                                                                   OBS video source
```

1. **Capture.** The phone app opens the camera with Android's Camera2 API and points it at two places: the on-screen preview and the input surface of a hardware video encoder (`MediaCodec`). Pixels never pass through app code, which is what makes 4K possible on a phone.
2. **Encode.** The encoder compresses frames to H.265 (HEVC) or H.264 (AVC) in hardware. Each compressed frame goes into a tiny queue that holds at most two frames. If the Mac falls behind, old frames are dropped instead of building up delay.
3. **Transport.** Over USB, the app listens on an Android *abstract local socket* (`phonecam-live-v1`), which the Mac reaches through `adb forward` over the cable (or over Wireless debugging). Over direct Wi-Fi, the phone connects to the plugin on the Mac and encrypts every message ([Wi-Fi](WIFI.md)). Both run when you tap Start; the first that connects is used.
4. **Pairing.** Before sending video, the Mac must present a secret key. Only the Mac's `adb shell` (the shell user that exists for an authorized USB debugging connection) can read that key from the app's `PairingProvider`. Other apps on the phone cannot, so they cannot connect to the camera socket. Wi-Fi uses a one-time QR or typed code instead, which sets up a long-term key on both sides.
5. **Decode.** The OBS plugin decodes frames with Apple's VideoToolbox (hardware), rotates them upright and mirrors them if asked (`VTPixelRotationSession`), and hands them to OBS as an asynchronous video source.

Masks, filters, recording, streaming and the Virtual Camera are all OBS's own features. PhoneCam only delivers the picture.

## The wire format (version 3)

Every message on the socket has a small header: the magic bytes `PCAM`, the format version, a type, flags, a length, a sequence number and a timestamp. The Mac first sends the key. The phone replies with a configuration message (codec, width, height, rotation, frame rate and the codec's parameter sets) and then one message per encoded frame. Frame flags carry *keyframe* (bit 0), the number of clockwise quarter turns needed to make the picture upright (bits 1–2) and *mirror* (bit 3). Both ends refuse other versions, so an old app and a new plugin fail with a clear message instead of a broken picture. The full table is in [USB live](USB_LIVE.md).

## Files: phone app (`app/`)

All Kotlin, in `app/src/main/java/dev/phonecam/obs/`.

| File | What it does |
|---|---|
| `MainActivity.kt` | The only screen people normally see: full-screen preview, Rear/Front and Quality choices, the Start/Stop button, the LIVE badge with fps, drops and temperature, and the live control strip (zoom, brightness, white balance, torch, tap to focus, Mirror in OBS). Also asks for the camera permission and stops live when the app leaves the screen. |
| `Capabilities.kt` | Scans every camera and hardware encoder at runtime and builds the list of *profiles* (camera, size, codec, bitrate, frame rate) the phone says it supports. Nothing is assumed. A profile stays "unverified" until a capture actually works. |
| `Quality.kt` | Turns the profile list into the simple choices Best, High, Balanced, Smooth 60 fps and Custom. |
| `LiveStreamer.kt` | Runs one live session: opens the camera, creates the encoder, builds each capture request (frame rate, zoom, exposure, white balance, torch, focus point), feeds encoded frames to the queue and releases everything on stop, error or backgrounding. |
| `Controls.kt` | What the user adjusted (`Controls`), what the camera supports (`Abilities`), and the geometry that converts a tap on the screen into a focus point on the sensor. Pure functions, unit tested. |
| `LiveTransport.kt` | The socket server: checks the key, sends the configuration and frames in the wire format, and sets the frame flags. |
| `LiveQueue.kt` | The two-frame queue between encoder and socket. It drops the oldest frame rather than adding delay. |
| `PairingProvider.kt` | Gives the connection key to the Mac's `adb shell` and to nobody else. |
| `WifiCrypto.kt` | Direct Wi-Fi security: pairing codes, P-256 key exchange, handshake tags and the encrypted records ([Wi-Fi](WIFI.md)). Checked against the same reference numbers as the Mac. |
| `WifiLink.kt` | The phone's Wi-Fi client: pairing store, QR link parsing, the handshake and the encrypted sender. |
| `WifiDiscovery.kt` | Finds PhoneCam OBS sources on the network (Bonjour) and connects to a paired Mac when live starts. |
| `ScanActivity.kt` | Scans the pairing QR code shown in OBS (decoded in memory, never saved). |
| `BitrateController.kt` | Lowers the bitrate while the connection drops frames and raises it back later; shown on the LIVE badge. |
| `Orientation.kt` | Works out how many quarter turns the picture needs to be upright, from the sensor angle and how the phone is held. |
| `FrameStats.kt` | Bounded counters for frames per second and drops, shown on the LIVE badge. |
| `BenchmarkActivity.kt`, `BenchmarkRecorder.kt` | Research tools (Settings → Research tools): record short test clips on the phone, only when you press Record. Not used for live. |

`app/src/test/` holds the unit tests, which run on the computer without a phone. `AndroidManifest.xml` declares the Camera permission only (no Internet, microphone or storage). Icons are in `app/src/main/res/`.

## Files: OBS plugin (`native/`)

Objective-C++ for macOS, built without Xcode by `tools/build_native.py`.

| File | What it does |
|---|---|
| `plugin.mm` | Registers the **PhoneCam (phone camera)** source with OBS, shows the Status line in its properties and pushes decoded frames into OBS. |
| `adb.mm`, `adb.hpp` | Finds `adb`, finds the phone, reads the key, creates the `adb forward` and reconnects after unplugging. Produces the friendly status messages ("Unlock the phone and tap Allow", …). |
| `receiver.mm`, `receiver.hpp` | Connects to the forwarded port, sends the key, reads messages, decodes with VideoToolbox, rotates and mirrors. |
| `wifi.mm`, `wifi.hpp` | Direct Wi-Fi: listens on port 47183, announces itself with Bonjour, pairs phones and receives encrypted video into the same decoder. |
| `wifi_crypto.hpp` | The Mac's half of the Wi-Fi security, using only Apple's CommonCrypto and Security frameworks. |
| `pairing_card.mm` | Draws the QR code and pairing code that the source shows while pairing. |
| `wifi_test.cpp` | Checks the Mac's Wi-Fi crypto against shared reference numbers. |
| `protocol.hpp` | Parses and checks the wire-format headers. Rejects anything malformed or the wrong version. |
| `receiver_cli.mm` | A command-line receiver used by tests and `tools/live.py` to check the pipeline without OBS. |
| `protocol_test.cpp`, `adb_test.cpp`, `smoke.mm` | Native tests, run under sanitizers by the build script. |

## Files: tools (`tools/`)

Python 3 scripts for developers. Users never need them.

| File | What it does |
|---|---|
| `build_native.py` | Downloads pinned, checksummed OBS headers, builds and tests the plugin, and installs it with `--install`. |
| `test_native.py` | Synthetic end-to-end tests: encodes known pictures with `ffmpeg`, sends them through the real receiver and checks decoding, rotation, mirroring and version rejection. |
| `release.py` | Builds the release files (signed APK, plugin zip, checksums) into `dist/`. Creates the private signing key once with `--create-key`. Publishes nothing. |
| `phonecam.py` | Installs the debug app, runs capability scans and collects research clips from the phone. |
| `live.py` | Receives a live session in the terminal without OBS, for diagnosis. |
| `tests/` | Unit tests for the Python tools. |

## Docs (`docs/`)

| File | What it covers |
|---|---|
| [ROADMAP.md](ROADMAP.md) | What each version adds, and what is planned later. |
| [COMPATIBILITY.md](COMPATIBILITY.md) | Which phones and computers work today, and which are planned. |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Building, testing and making a release. |
| [USB_LIVE.md](USB_LIVE.md) | The connection and wire format in detail. |
| [PHASES.md](PHASES.md) | Which milestones are proven on real hardware, and the evidence. |
| [PLAN.md](PLAN.md) | The original research plan and acceptance gates. |
| `research/` | Dated records of what was measured on real devices. |

## Rules the code follows

- The phone is only a camera. Nothing is saved on it during live, and nothing runs in the background.
- Everything the phone claims to support is checked at runtime. Unsupported settings fail with a clear message and are never silently changed.
- Every camera, encoder, socket and thread is released on stop, error, permission loss or when the app leaves the screen.
- A physical feature counts as working only after it works on a real phone with real OBS. A green build is not enough.

Contribution workflow: [CONTRIBUTING.md](../CONTRIBUTING.md).
