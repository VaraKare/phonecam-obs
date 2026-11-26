# Which phones and computers work

Last updated 26 November 2026, for v0.7.0-alpha.1. Proven on real hardware: one phone (vivo V2437, Android 16) with one Mac (Apple M1, OBS 33.0.1), and with one Windows laptop (v0.7 installer, 25 November 2026).

**Tested** means it worked on a real device with real OBS and the result is written in the docs. **Expected** means the code supports it but nobody has proven it. A build, an emulator or a test video never counts as tested.

## Setups

Every setup has a test checklist in [docs/tests](tests/README.md). Tickets are on GitHub; the numbers are filled in by the project owner.

| Setup | Status | What is known | Checklist | Ticket |
|---|---|---|---|---|
| **Android + Mac (Apple Silicon)** | **Tested** | vivo V2437 (Android 16) with an Apple M1 Mac and OBS 33.0.1: USB, direct Wi-Fi, background live, OBS controls, microphone and screen sharing, up to 0.7.0-alpha.1 (21–23 November 2026), including the Mac installer, entire screen and one app sharing, phone sound and the recording options. Not yet: a 30-minute microphone run, clap-sync, the 2-hour run, camera-to-OBS delay, and any other phone. | The checks in [v0.6](versions/v0.6.md#tested-on-hardware) | none |
| **Android + Windows** | **Tested** (25 Nov 2026) | One Windows laptop, v0.7 installer: USB, direct Wi-Fi, recording options, screen sharing with phone sound, microphone, rotation, unplug and replug, leaving the app. 4K (Best) dropped frames on that laptop; Balanced added none. Not run: typed-code pairing, hotkeys, warnings, a 30-minute run, 4K timing. Details: [Windows v0.7 test](research/WINDOWS_V07_TEST.md). | [WINDOWS.md](tests/WINDOWS.md) | [#4](https://github.com/VaraKare/phonecam-obs/issues/4) |
| **Android tablet** | To test in v0.7 | Not tried on any tablet. The app asks the device what it has, so it should offer only what a tablet can do. The risks are picture orientation, few cameras and screen layout. | [TABLET.md](tests/TABLET.md) | [#1](https://github.com/VaraKare/phonecam-obs/issues/1) |
| **Android + Intel Mac** | Needs a device (ticket) | The universal plugin (one file for Intel and Apple Silicon) is built. It has never run on an Intel Mac. | [INTEL_MAC.md](tests/INTEL_MAC.md) | [#3](https://github.com/VaraKare/phonecam-obs/issues/3) |
| **Another Android phone (Samsung, Pixel and others)** | Needs a device (ticket) | Expected to work, not verified. Phone makers differ in extra lenses, 60 fps, background limits and menus. | [SECOND_ANDROID.md](tests/SECOND_ANDROID.md) | [#2](https://github.com/VaraKare/phonecam-obs/issues/2) |
| **iPhone + Mac** | After v1.0 | Owner's decision, 22 November 2026. Wi-Fi first, with the existing PCW1 protocol ([Wi-Fi](WIFI.md)), then USB. No iPhone app exists yet. On a Mac, Apple's built-in **Continuity Camera** already does this for free: in OBS, add a **Video Capture Device** and choose the iPhone (macOS 13+, iOS 16+). | [IPHONE.md](tests/IPHONE.md) | [#5](https://github.com/VaraKare/phonecam-obs/issues/5) |
| **iPhone + Windows** | After v1.0 | Same decision and plan as above. This is the case Apple does not cover. USB needs Apple's device software on Windows. | [IPHONE.md](tests/IPHONE.md) | [#5](https://github.com/VaraKare/phonecam-obs/issues/5) |
| **Linux** | Later | Needs a Linux plugin. Same phone app. | none yet | [#7](https://github.com/VaraKare/phonecam-obs/issues/7) |
| **Several phones as several cameras** | Later | Last on the list, after one phone works well for everyone. | none yet | [#6](https://github.com/VaraKare/phonecam-obs/issues/6) |

Status words: **Tested**, **To test in v0.7**, **Needs a device (ticket)** (a tester with that device is wanted), **After v1.0**, **Later**.

## What works, feature by feature

For the Mac and the vivo V2437, from the version guides ([v0.5](versions/v0.5.md), [v0.6](versions/v0.6.md)) and the [releases](releases/).

| Feature | Since | Verified on a real device? |
|---|---|---|
| USB live, rear and front camera, any orientation | 0.1 | Yes, Mac and one Windows laptop |
| Camera controls (zoom, focus, brightness, white balance, torch, mirror) | 0.2 | Yes, Mac. Smooth 60 fps is unchecked |
| Direct Wi-Fi with QR pairing | 0.3 | Yes: QR pairing and 1080p live on the Mac (also over the phone's hotspot) and on one Windows laptop. Typed-code pairing and 4K over Wi-Fi have no recorded result |
| Switching camera and quality while live | 0.4 | Yes, Mac |
| Background live and picture-in-picture | 0.5 | Yes, vivo. Not checked on Android 10 and 11, long sessions, or the "phone closed PhoneCam" message |
| OBS Phone section and hotkeys | 0.6 | Yes, Mac |
| Phone microphone with audio tracks | 0.6 | Yes, Mac. Clap-sync and an hour-long run are unmeasured |
| Screen sharing, entire screen or one app, phone sound | 0.6, 0.7 | Yes, Mac and one Windows laptop |
| OBS recording options (stop with the phone, start with the phone) | 0.7 | Yes, Mac and one Windows laptop. A pulled cable keeping the recording running was seen on Windows |
| Mac installer (.pkg) and Windows installer | 0.7 | Yes (Mac 22 Nov, Windows 25 Nov) |
| Heat and battery warnings, alert when away, dimming | 0.6 | Yes (owner's report) |
| Windows: USB, direct Wi-Fi, recording options, screen sharing with phone sound, microphone | 0.7 | Yes, one Windows laptop (4K dropped frames there) |
| Any of the above on a phone other than the vivo V2437 | | **No** |

## Connections

| Connection | Works when | Notes |
|---|---|---|
| USB cable | USB debugging on, computer allowed once | Fastest (≈140 Mb/s on a USB 2.0 phone). Preferred over Wi-Fi when the cable is in |
| Direct Wi-Fi | Paired once by QR or typed code; phone and computer on the same network, or the computer on the phone's hotspot | No Developer options for this part. Some routers keep devices apart (guest networks, some mesh or dual-band setups); use the same network name or the hotspot. Windows asks once to allow OBS through the firewall |
| Wireless debugging | Developer options; same Wi-Fi | ≈50–65 Mb/s through adb in our test; the bitrate lowers itself to fit |

## OBS versions and updates

- **Minimum: OBS 33.0.** Every plugin (Mac, Intel slice, Windows) is built against one pinned, checksummed OBS 33.0.1 SDK (`SDK_VERSION` in `tools/build_native.py`), never against whatever OBS the build computer has.
- **Newer OBS keeps working.** OBS loads a plugin built for its own version or an older one and refuses one built for a newer OBS (`libobs/obs-module.c`). Building against the oldest supported OBS is what keeps an OBS update from breaking PhoneCam, and keeps an update on the build computer from quietly raising the minimum for everyone. PhoneCam uses only OBS's C plugin interface (no Qt), and OBS's recording controls are looked up at run time, so if a future OBS drops them only the recording options switch off.
- **Checked every week.** `.github/workflows/newest-obs.yml` loads the plugin in the newest official OBS for Mac (OBS's Apple signature checked) and Windows every Monday. A failure there is an early warning; it never changes a release.
- **In the OBS log.** At start the plugin writes `[phonecam] Built for OBS 33.0 and newer; running in OBS <version>.` and a warning when OBS is a newer major version than the one it was tested with.
- Tested on hardware: OBS 33.0.0, 33.0.1 and 33.0.2 on the Mac. Raising the minimum (to use a newer OBS feature) is a deliberate change of `SDK_VERSION`, noted in the release notes.

## Why other Android phones should work, and what can differ

The app doesn't assume anything about the phone. On first open it asks the phone which cameras, sizes, frame rates and hardware encoders it has, and offers only those. So other phones get their own Best/High/Balanced choices. What varies by maker:

- **Extra lenses.** Some phones (like the vivo) hide their ultra-wide or telephoto cameras from other apps. PhoneCam can only offer lenses the phone exposes.
- **60 fps.** Many phones offer 60 fps only in their own camera app. PhoneCam shows 60 fps only where the phone offers it to other apps.
- **Heat.** Long 4K sessions warm some phones. The LIVE badge shows the temperature; choose High or Balanced if it gets hot.
- **Background limits.** vivo, Xiaomi, OPPO and some other phones close background apps. Settings → While live → Let PhoneCam run in the background points to the right Android settings.
- **USB debugging menus** are in slightly different places on each brand.
- **Android version.** The app needs Android 10 or newer. The Back button and picture-in-picture on Android 10 and 11 are unchecked.
- **Tablets** may have a different natural orientation, fewer cameras and no torch.

Whether it works or not, please send a **Device report** (GitHub → Issues → New issue → Device report), or the report from a [test checklist](tests/README.md). Each report builds the tested-phones list.

## What each missing combination needs

| Combination | Work needed | Size |
|---|---|---|
| Windows + Android: more PCs | Run the [Windows checklist](tests/WINDOWS.md) on a second PC. 4K drops frames on the laptops tried so far; measure it (check S3) and decide if it needs GPU rotation. | Small to medium |
| Intel Mac | The universal plugin is built. It needs a run on an Intel Mac with the [Intel Mac checklist](tests/INTEL_MAC.md). | Small |
| Tablet, other makers' phones | Testing only, with the [tablet](tests/TABLET.md) and [other phone](tests/SECOND_ANDROID.md) checklists. Fixes follow what the tests find. | Small each |
| Linux + Android | A Linux plugin with VA-API or FFmpeg decoding. Same phone app. | Medium |
| iPhone + Windows, iPhone + Mac (after v1.0) | A new iPhone app (AVFoundation camera, VideoToolbox encoder). Wi-Fi first, over the existing PCW1 protocol ([Wi-Fi](WIFI.md)), which needs no new plugin transport. USB second, through Apple's `usbmuxd` (needs Apple's device software on Windows). Distribution through the App Store or TestFlight needs the Apple Developer Program ($99/year) and review. The plugin's decoder and display code are shared. The list of what the app must do is in [IPHONE.md](tests/IPHONE.md). | Very large |
| iPhone + Mac | Covered today by Continuity Camera. The PhoneCam app is only worth it for what Continuity Camera lacks (control from OBS, quality choice, microphone and screen options). | Part of the iPhone work |
| Several phones as several cameras | Each phone as its own OBS source. Builds on the one-source rule. Last. | Large |

The phases are tracked in the [roadmap](ROADMAP.md).
