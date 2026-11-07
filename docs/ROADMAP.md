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
- **One-time pairing code** shown on the phone and typed into the OBS source settings. This replaces the developer-only key read, which does not work with a public app build.
- Picture follows the phone's rotation live (already implemented in app 0.3.0).
- Release-signed Android app (signing key kept outside Git) and a plugin zip with install steps.
- Requirements stated clearly: Android 10+, Apple Silicon Mac, OBS 33, Android platform-tools (ADB), USB debugging switched on once.

### v0.2 — Camera controls

- Zoom and lens switching (wide / ultra-wide / telephoto, where the phone lets other apps use them).
- Tap-to-focus, focus lock, exposure lock and brightness.
- White balance presets and flashlight.
- Front-camera mirror toggle.
- 60 fps profiles where advertised and proven; battery and overheating warnings.

### v0.3 — Windows (phase 1: USB)

Most laptop users run Windows, so Windows support is required, delivered in phases.
- Windows OBS plugin with hardware video decoding (Windows' Media Foundation / Direct3D 11 decoder, the Windows equivalent of the Mac's VideoToolbox).
- Bundled or guided ADB setup, including the phone maker's USB driver where Windows needs one.
- Same phone app; same pairing and automatic connection as on the Mac.

### v0.4 — Optional audio

- Phone microphone, **off unless the user turns it on**. Permission requested only then.
- Synced with video in OBS (target within 33 ms over an hour). Mac and Windows.

### v0.5 — Wireless

- Step 1: guided ADB Wireless debugging (same pipeline, no cable).
- Step 2: native Wi-Fi with QR-code pairing and an encrypted connection, if step 1 is not reliable enough. Needs the Android Internet permission; approved separately.

### v0.6 — Polish

- Several phones as separate OBS sources; choosing or switching the phone camera from OBS.
- Windows phase 2: installer.
- Mac installer package (unsigned; see below).

### v1.0 — Stable

- Two-hour sessions and 20 unplug/replug cycles without problems.
- Measured glass-to-OBS delay and a controlled quality comparison with the stock camera app.
- A list of tested phones (Vivo plus at least two other makers, including an older/lower-end phone).

### Later

Linux plugin, iPhone app, and using the phone directly as a Zoom/Meet webcam without OBS (with OBS, the built-in Virtual Camera already does this).

## Future suggestion: Apple signing

Without the Apple Developer Program ($99/year), macOS warns that the plugin is from an "unidentified developer", and users must approve it manually (documented in the README). If enough people use the project, join the program and ship a signed, notarized installer. Revisit this after v0.1 feedback.

## Not planned

Saving a copy on the phone during live sessions (the phone is only the camera; record in OBS), background capture, analytics, and accounts or cloud services.
