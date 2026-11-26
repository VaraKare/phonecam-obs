# Test checklists

One checklist for each way people will use PhoneCam OBS. Each one is written for someone who is not a developer: what you need, how to install, numbered checks with exact steps, and a clear pass line. The status of each setup is in [which phones and computers work](../COMPATIBILITY.md).

| File | Setup | Status | Ticket |
|---|---|---|---|
| [WINDOWS.md](WINDOWS.md) | Android phone + Windows PC | Tested on one laptop (25 Nov 2026); remaining checks open | [#4](https://github.com/VaraKare/phonecam-obs/issues/4) |
| [TABLET.md](TABLET.md) | Android tablet + Mac or Windows | To test in v0.7 | [#1](https://github.com/VaraKare/phonecam-obs/issues/1) |
| [INTEL_MAC.md](INTEL_MAC.md) | Android phone + Intel Mac | Needs a device (ticket) | [#3](https://github.com/VaraKare/phonecam-obs/issues/3) |
| [SECOND_ANDROID.md](SECOND_ANDROID.md) | A phone of another brand (Samsung, Pixel, and so on) + Mac or Windows | Needs a device (ticket) | [#2](https://github.com/VaraKare/phonecam-obs/issues/2) |
| [IPHONE.md](IPHONE.md) | iPhone + Mac or Windows | After v1.0; the app doesn't exist yet | [#5](https://github.com/VaraKare/phonecam-obs/issues/5) |

The Android + Mac (Apple Silicon) setup has already been tested; its record is in [v0.6](../versions/v0.6.md#tested-on-hardware) and the [research notes](../research/VIVO_V2437_V06_TESTS.md).

## The rules

- A check passes only when you did it on a real device with real OBS. A build, an emulator or a test video never counts.
- Write **Pass**, **Fail**, **Skipped** (and why) or **Not offered**. Never write **Pass** for something you did not do or see.
- A failure is useful news. Write what you saw and what you expected, then go on.
- Do not put names, IP addresses, pairing codes or QR codes in anything you share. Each file's **Report** section says how to clean the OBS log.

## The checks are the same in every file

Checks 1 to 22 are the same in all four Android files, so results compare across setups. Each file adds a few **extra checks** (S1, S2, and so on) for what is different about that setup, and the same two **optional checks** (O1, O2).

| # | Check | Features it covers |
|---|---|---|
| 1 | Install the app and open it | camera scan, quality choices |
| 2 | The plugin and the source in OBS | plugin loads, Status line |
| 3 | Live over USB | USB live |
| 4 | Rotation and fit | any orientation, fit to canvas |
| 5 | Switch camera, mirror and camera controls | camera switch, mirror, zoom, focus, torch |
| 6 | Quality choices | Best, High, Balanced, Smooth 60 fps |
| 7 | Stop | stop from the phone |
| 8 | Wi-Fi pairing by QR code | direct Wi-Fi pairing |
| 9 | Wi-Fi pairing by typed code, twice, and expiry | typed code, pairing again |
| 10 | Live over Wi-Fi | direct Wi-Fi live |
| 11 | USB is preferred when the cable is in | USB and Wi-Fi together |
| 12 | Live keeps going when you leave the app | background live |
| 13 | Picture-in-picture | floating window |
| 14 | The Phone section in OBS | control from OBS |
| 15 | OBS hotkeys | hotkeys |
| 16 | Microphone and audio tracks | microphone, audio tracks, refusal |
| 17 | Screen sharing | screen sharing |
| 18 | Warnings, dimming and the alert when away | heat, battery, away alert, dimming |
| 19 | Unplug and replug the cable | reconnecting |
| 20 | Hide and show the source | hide and show |
| 21 | Quit OBS while live | OBS closing |
| 22 | The 30-minute run | stability |

## Keeping these lists up to date

The checks follow the current version guide ([v0.6](../versions/v0.6.md) today). When a version adds or changes a feature, add or change the matching check in **all** the files, with the same number. If you change the wording of a check in one file, change it in the others too. When a test is done, the owner records the result in [COMPATIBILITY.md](../COMPATIBILITY.md) and the research notes, and never marks a setup **Tested** from a build alone.
