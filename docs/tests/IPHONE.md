# Test checklist: iPhone (after v1.0)

Ticket: #TBD. Status in [COMPATIBILITY.md](../COMPATIBILITY.md): After v1.0.

**After v1.0. The app doesn't exist yet.** Nothing in this file has been built or tested. It is a list of what an iPhone app must do and how to check it, written now so the work later follows the docs. Do not start testing until the owner says there is an iPhone build.

The owner decided on 22 November 2026 that iPhone support comes after v1.0, for Mac and for Windows. On a Mac, Apple's own **Continuity Camera** already lets OBS use an iPhone: in OBS, add a **Video Capture Device** and choose the iPhone (macOS 13 or newer, iOS 16 or newer). A PhoneCam iPhone app for the Mac is only worth it for what Continuity Camera lacks (see below). On Windows, there is no such built-in way.

## The order

1. **Wi-Fi first.** The iPhone app talks to the OBS plugin over the local network with the protocol that already exists, **PCW1**, written down in [Wi-Fi without a cable](../WIFI.md). Nothing in the Mac or Windows plugin should need to change to receive its video.
2. **USB second.** Apple's phones use a different cable path from Android's `adb`: Apple's `usbmuxd`. The plugin needs a second transport for it, and on Windows it needs Apple's device software installed.

## What the app must do

### Rules it shares with the Android app

These come from [AGENTS.md](../../AGENTS.md) and apply to every PhoneCam app:
- The iPhone is only a live camera for OBS. It saves nothing on the phone during live.
- Capture starts only when the user taps the record button. OBS may stop or control a live session but never start one.
- Hardware H.264 or H.265 only. Ask the phone at run time what it can do, offer only that, and fail clearly if a choice cannot work.
- No microphone unless the user turns it on for that session. Refuse, rather than silently send video without the sound the user asked for.
- No accounts, no analytics, no servers on the Internet. The network is used only to reach the computer the user paired.
- A notification or visible sign whenever the camera is in use, and everything stops cleanly on errors.

### Phase 1: Wi-Fi

1. **Camera:** capture with AVFoundation and encode in hardware with VideoToolbox. Rear and front, and quality choices (Best, High, Balanced) built from what the iPhone reports. Any orientation, with the picture upright in OBS.
2. **Speak PCW1 exactly** as [WIFI.md](../WIFI.md) describes:
   - the phone connects out to the computer's listener (TCP port 47183, or the port in the QR code);
   - the handshake (ClientHello, ServerHello, ClientFinish) with the same key steps;
   - video as PCAM v3 messages in encrypted records (AES-256-CTR with a 16-byte tag);
   - the 0.6 two-way link: the hello, then status and commands (see [USB live](../USB_LIVE.md#two-way-link-06)).
3. **Pairing:** scan the QR code (`phonecam://pair?...`) or type the 16-character code; store the long-term key on the phone only (not in backups); list paired computers with **Forget**.
4. **Finding the computer:** Bonjour `_phonecam._tcp`, and the addresses from the QR code. iOS asks the user for **Local Network** permission the first time. If the user says no, the app must say so in plain words and point to the iPhone's Settings.
5. **Same results as the Android app on the same test data:** the Android and the iPhone code must give the same numbers for the reference vectors used in `native/wifi_test.cpp` and the Android `WifiCryptoTest`, and an interoperability test against the real plugin server must pass before any physical test.
6. **Features to match:** live badge with fps, drops and heat; mirror; zoom, tap to focus, torch; control from OBS (camera, quality, torch, mirror, stop) and OBS hotkeys; heat and low-battery warnings (from iOS thermal state and battery level); an alert when live ends while the user is away; the optional microphone with its own permission.

### Things to decide before promising them

iOS limits what an app can do in the background, so these need research first and may end up different from Android:
- Keeping live going when the user leaves the app, and picture-in-picture.
- Sharing the iPhone's screen (iOS does this through a separate broadcast extension).
- How testers get the app (TestFlight needs the Apple Developer Program, $99 a year) and what App Store review needs.

### Phase 2: USB

- A transport through Apple's `usbmuxd` that carries the same PCAM messages, with the same kind of key protection as the Android USB link (a key that only the cable connection can read, or the same pairing as Wi-Fi). The design comes first and goes into [USB live](../USB_LIVE.md).
- Mac: `usbmuxd` is part of macOS. Windows: it comes with Apple's own device software, which the user must install.

## What you need to test (when the app exists)

- An iPhone and its iOS version (the lowest iOS version the app supports is not decided yet), and the cable (Lightning or USB-C).
- A Mac with Apple Silicon (and Continuity Camera, for the comparison), and a Windows PC. If you have both, do the list on both.
- OBS Studio 33 and the PhoneCam plugin for that computer.
- The iPhone build the owner gives you (a TestFlight invitation or similar). Never install an app from a source the owner has not named.
- A Wi-Fi network that the iPhone and the computer are both on.

## Checks

Use the numbered checks of [WINDOWS.md](WINDOWS.md) (checks 1 to 22: install, source, live, rotation, camera and quality, stop, Wi-Fi pairing and live, background, microphone, screen, warnings, replug, hide and show, quitting OBS, the 30-minute run). They are the same on every computer. For an iPhone the following change.

| Check | For the iPhone |
|---|---|
| 1 | Install the iPhone build instead of the APK. The first open asks for Camera permission and then Local Network permission. |
| 2 | The same plugin and source as for Android. |
| 3, 11, 19 | USB: **Phase 2 only.** Before Phase 2 these checks are **Skipped**, and write why. USB preferred over Wi-Fi (11) and replug (19) use Apple's `usbmuxd`. |
| 8, 9, 10 | Wi-Fi pairing by QR code and typed code, and live over Wi-Fi. This is **Phase 1** and the first thing to test. |
| 12, 13 | Background live and picture-in-picture: only if the design allows them (see above). Otherwise write what the app does when you leave it, and check that it says so and ends live cleanly. |
| 16 | Microphone: permission is asked only when the user turns it on, and refused with a message if denied. |
| 17 | Screen sharing: only if the design includes it. |
| 18 | Warnings use iOS's thermal state and battery level. |

### Extra checks for the iPhone

**I1. Local Network permission.** Install the app fresh. Go live over Wi-Fi. **Pass when:** iOS asks for Local Network permission once, with a clear reason, and the app works after **Allow**. Then turn it off in iPhone Settings and try again. **Pass when:** the app says in plain words what to change, and does not just fail.

**I2. Same protocol as Android.** Pair the same computer with an Android phone and an iPhone. **Pass when:** both appear in OBS's paired phones, either can go live, and the plugin needs no change or setting for the iPhone.

**I3. Comparison with Continuity Camera (Mac).** Record the same scene for one minute with Continuity Camera and for one minute with PhoneCam over Wi-Fi. **Pass when:** you can say what PhoneCam adds (controls from OBS, quality choice, the microphone, and so on). This decides whether the Mac version is worth keeping.

**I4. Windows without extra software over Wi-Fi.** On a Windows PC with nothing from Apple installed, pair and go live over Wi-Fi. **Pass when:** it works. (USB needs Apple's device software; Wi-Fi must not.)

**I5. Nothing saved.** After a live session, look in the iPhone's Photos and Files. **Pass when:** nothing from the live session is saved there.

**I6. Older iPhone.** Repeat checks 3 (or 10 if USB is not built), 6 and 22 on the oldest iPhone the app is meant to support. **Pass when:** they pass, or the app clearly tells the user that the iPhone is too old.

## Report

Use the **Report** section of [WINDOWS.md](WINDOWS.md#report), with these changes: write "iPhone model and iOS version" in place of "Android version", and add lines for I1 to I6. Remove IP addresses, names and pairing codes from the OBS log lines as that section says.
