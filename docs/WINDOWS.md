# Windows plugin plan (v0.4)

Started 11 November 2026 on `feat/v0.4-windows`. The owner has no Windows PC yet, so every step is built and tested automatically on GitHub's Windows machines (`windows-latest`, x64), and nothing is released until a real Windows PC with OBS and a real phone pass the same checks as the Mac ([phase tracker](PHASES.md)).

The phone app does not change: the Windows plugin speaks the same PCAM v3 messages over the same three connections (USB through adb, Wireless debugging, direct Wi-Fi).

## Decisions

| Topic | Mac | Windows | Why |
|---|---|---|---|
| OBS SDK | Pinned OBS 33.0.1 source headers | Same headers, plus an import library generated from the official OBS 33.0.1 x64 `obs.dll` | No need to build OBS itself; checksums pinned |
| Video decoding | VideoToolbox (hardware) | FFmpeg from OBS's own pinned `obs-deps` (2026-10-23), D3D11VA hardware decoding with software fallback | OBS already ships these FFmpeg DLLs and uses them in its own camera source. Media Foundation would need the paid-or-store HEVC extension for H.265 and is missing on Windows Server test machines |
| Rotation and mirror | VTPixelRotationSession | CPU rotation of NV12 in tiles, split across threads; GPU later if the per-step timing shows the copies are the bottleneck | 4K portrait was too slow on the first real laptop (45–60 ms per frame) |
| adb | `posix_spawn` | `CreateProcess` with pipes, no shell; if no adb server is listening, the plugin starts one in foreground mode (`server nodaemon`), ended with OBS by a job object | Same automatic discovery, pairing key and forward. adb's own background start hung on a laptop with Avast |
| Direct Wi-Fi crypto | CommonCrypto + Security | Windows CNG (`BCrypt`): SHA-256, HMAC, AES (CTR built on ECB), ECDH P-256 | Built into Windows; same test vectors as Mac and phone ([Wi-Fi](WIFI.md)) |
| Bonjour | `dns_sd` | `DnsServiceRegister` (Windows 10 1809+) | Built into Windows |
| Pairing card | Core Graphics, Core Image QR | GDI, Project Nayuki's QR Code generator (MIT, v1.8.0, pinned by SHA-256, fetched at build time) | No QR encoder is built into Windows |

## Phases

| Step | Contents | Proof |
|---|---|---|
| W1 | Shared portable headers; Windows build of the plugin; OBS loads it on a Windows test machine | **Passed in CI on 11 November 2026** (run 43105615013): MSVC `/W4 /WX` protocol and adb tests, official OBS 33.0.1 x64 libobs loaded the skeleton source. Not a physical check |
| W2 | FFmpeg decoding, rotation and mirror; same synthetic streams as the Mac tests | **Passed in CI on 11 November 2026**: H.264 and H.265 decode at the right size, portrait rotation swaps dimensions, mirror swaps left/right brightness exactly (software decoding on the GPU-less test machine; D3D11 hardware decoding unproven) |
| W3 | adb discovery, pairing key, forward and reconnection | **Passed in CI on 11 November 2026**: discovery, pairing key, forward, retry after an unstarted phone, receive and forward removal, against a compiled fake adb |
| W4 | Direct Wi-Fi: listener, Bonjour, pairing card, BCrypt crypto | **Passed in CI on 16 November 2026** (run 43861827194): Windows CNG matches the shared vectors and rejects off-curve keys; the pairing card draws and its QR code decodes to the pairing link; the phone's Kotlin client was refused with a wrong code, then paired and streamed encrypted H.264 that the Windows server decoded. Same server code as the Mac (`wifi_server.cpp`). Loopback only, not a physical check |
| W5 | Installer or zip with steps; USB driver guidance | A real Windows PC, OBS 33 and a phone: the same checklist as the Mac. **Partly checked on 15 November 2026** ([first laptop test](research/WINDOWS_FIRST_LAPTOP_TEST.md)): USB live at 1080p worked with D3D11 hardware decoding; 4K too slow; adb needed foreground mode. Not passed |

Physical gate: until W5 passes on real hardware, the Windows plugin is a test build only and is not attached to any release.

## Testing on a real Windows PC

The test build is attached to each CI run on `feat/v0.4-windows` (GitHub → Actions → the latest run → Artifacts → `phonecam-obs-windows-test`). Checklist: [Windows test day](WINDOWS_TEST.md).
