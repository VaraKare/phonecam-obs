# Roadmap

Decided with the project owner on 7 November 2026. Each version is a tagged GitHub Release (`v0.1.0-alpha.1`, …) with a ready-made Android app and OBS plugin download, so a visitor does not need developer tools. A version is released only after it works on a real phone with real OBS. A successful build is not enough (see [phase tracker](PHASES.md)).

The goal: anyone with an Android phone and a laptop can use the phone's rear or front camera as a high-quality OBS source for live streams or recordings, choosing camera and quality on the phone, with masks, filters and recording done in OBS.

## Repository

- **Private now; public at v0.1.** History is pushed to a private GitHub repository. It becomes public when v0.1 is tested and the README is ready.
- **README for end users**: step-by-step screenshots of the phone settings (Developer options, USB debugging, the app) and the OBS buttons to press. The owner supplies the images; the README has marked places for them.
- Every release lists what was tested, on which phone and Mac, and what is still unverified.

## Versions

### v0.1 — First preview: easy USB on Mac

- Redesigned phone app (portrait and landscape): full-screen preview, LIVE badge with fps/drops/temperature, Rear/Front/Wide chooser, **Quality: Best** by default, one big Start/Stop button. Research tools move into Settings.
- Automatic camera scan on first open; the last working profile is remembered.
- **No terminal in daily use:** the OBS plugin finds the phone, creates the USB link, and reconnects after unplugging or a USB reset.
- **Automatic pairing**, nothing to type: the plugin reads the connection key through the authorized USB debugging shell (`PairingProvider`). This replaced the developer-only key read, which does not work with a public app build. (The one-time code planned earlier was not needed.)
- Picture follows the phone's rotation live (already implemented in app 0.3.0).
- Release-signed Android app (signing key kept outside Git) and a plugin zip with install steps.
- Requirements stated clearly: Android 10+, Apple Silicon Mac, OBS 33, Android platform-tools (ADB), USB debugging switched on once.

### v0.2 — Camera controls

Status: physically checked on 8 November 2026 with release-signed files: zero drops, rear and front, controls and Mirror user-reported working, automatic fit confirmed ([session](research/VIVO_V2437_USB_LIVE.md#v01-release-files-and-v02-session-8-november-2026-afternoon)). Smooth 60 fps still unchecked.

- Zoom (slider and pinch) and lens switching. Extra lenses appear automatically as "Rear 2" etc. when the phone lets other apps use them; the vivo V2437 hides its ultra-wide from other apps, so it offers Rear/Front and zoom.
- Tap-to-focus (focus and exposure on a point; tap "Focus" to return to auto) and brightness.
- White balance presets and torch.
- "Mirror in OBS" switch (remembered per camera). The phone preview of the front camera is mirrored by Android, like a mirror.
- 60 fps: phones that only offer 60 fps through a high-speed mode (like the vivo, 120 fps at 1080p) get an opt-in "Smooth 60 fps (experimental)" choice; it is kept only if it works on a real phone.
- Battery and overheating warnings (temperature label exists since v0.1).

Order changed on 8 November 2026: wireless comes before Windows because it can be proven on the owner's phone and Mac now, while no Windows PC is available yet. Audio moved to later, because people already connect their own microphones and earbuds to OBS.

### v0.3 — Wireless (Mac)

**Released 11 November 2026 as `v0.3.0-alpha.5`, the first public preview.**

Status: step 1 implemented on `feat/v0.3-wifi` (8 November 2026): OBS pairing button, automatic reconnection to a paired phone, USB preferred when both are present, "Live over Wi-Fi/USB" status. Physical check pending.

- Step 1: ADB Wireless debugging. The plugin finds a phone paired over Wireless debugging just like a USB phone; guided pairing steps. Same pipeline and security, no cable.
- Step 2: direct Wi-Fi with QR-code or typed-code pairing and an encrypted connection; also works on the phone's hotspot. **Approved 11 November 2026** (Android network permission, local network only) after Wireless debugging could not connect on the owner's router. Implemented on `feat/v0.3-wifi` with a phone-to-Mac interoperability test. **Physically checked on 11 November 2026:** QR pairing and 1080p30 live over direct Wi-Fi with 0 drops on the vivo V2437 ([session](research/VIVO_V2437_USB_LIVE.md#first-direct-wi-fi-session-11-november-2026)); 4K over Wi-Fi, typed-code pairing and the hotspot path still unchecked. Spec: [Wi-Fi](WIFI.md).
- Automatic bitrate lowering when the connection can't keep up (any connection), shown on the LIVE badge.
- Measured on a real phone: frame rate, drops and delay compared with USB, on 5 GHz / Wi-Fi 6.

### v0.4 — Windows (phase 1: USB, then Wi-Fi)

Most laptop users run Windows, so Windows support is required, delivered in phases.
- Windows OBS plugin with hardware video decoding (Windows' Media Foundation / Direct3D 11 decoder, the Windows equivalent of the Mac's VideoToolbox).
- Bundled or guided ADB setup, including the phone maker's USB driver where Windows needs one.
- Same phone app; same pairing and automatic connection as on the Mac, then the v0.3 wireless connection.
- **Proof:** the code can be written now and built and tested automatically on GitHub's Windows machines (CI) with synthetic video. It is released only after a real Windows PC with OBS and a real phone pass the checks, either the owner's or a volunteer tester's device report.

### v0.6 — Polish

- Several phones as separate OBS sources; choosing or switching the phone camera from OBS.
- Windows phase 2: installer.
- Mac installer package (unsigned; see below) and a universal plugin that also runs on Intel Macs.

### v1.0 — Stable

- Two-hour sessions and 20 unplug/replug cycles without problems.
- Measured glass-to-OBS delay and a controlled quality comparison with the stock camera app.
- A list of tested phones (Vivo plus at least two other makers, including an older/lower-end phone).

### Later: optional audio

- Phone microphone, **off unless the user turns it on**. Permission requested only then.
- Synced with video in OBS (target within 33 ms over an hour). Mac and Windows.

### Later: more platforms

See [which phones and computers work](COMPATIBILITY.md) for the reasons and the size of each step.

1. **Linux plugin** (Android phones): same phone app, Linux hardware decoding.
2. **iPhone app for Windows** (and Linux): a new iPhone app and a different USB transport (`usbmuxd`); needs the Apple Developer Program. On a Mac, Apple's Continuity Camera already covers iPhones, so a PhoneCam iPhone app for the Mac comes last, if ever.
3. Using the phone directly as a Zoom/Meet webcam without OBS (with OBS, the built-in Virtual Camera already does this).

## Earning from the project (decide when usage grows)

The owner intends to earn from PhoneCam once many people use it. Reach comes first: Windows, wireless without Developer options, and a tested-phones list. Options, to be chosen later:

- **Donations and sponsorship** (GitHub Sponsors, Buy Me a Coffee) from the first public release. No change to the app.
- **Paid Play Store edition or one-time "Pro" unlock** for extras (for example several phones at once or native Wi-Fi), with the core USB camera staying free. Google Play billing requires a Play Store listing and the billing library; the app's privacy promises (no accounts, no analytics, no ads) stay.
- **Signed installers** for Mac and Windows as part of a paid or sponsored tier.

Groundwork to do before accepting outside code contributions: a contributor agreement that lets the owner offer paid editions alongside the GPL license. The owner currently holds all the copyright, so this is still open. Ads and data collection are ruled out; they contradict the privacy pitch.

## Future suggestion: Apple signing

Without the Apple Developer Program ($99/year), macOS warns that the plugin is from an "unidentified developer", and users must approve it manually (documented in the README). If enough people use the project, join the program and ship a signed, notarized installer. Revisit this after v0.1 feedback.

## Not planned

Saving a copy on the phone during live sessions (the phone is only the camera; record in OBS), background capture, analytics, and accounts or cloud services.
