# Which phones and computers work

Last updated 8 November 2026. "Tested" means it worked on a real device with real OBS. "Expected" means the code supports it but no one has proven it yet.

## Today

| Phone | Computer | Status |
|---|---|---|
| Android 10 or newer | Mac with Apple Silicon (M1 or newer), macOS 13+, OBS 33 | **Works.** Tested on one phone (vivo V2437, Android 16) and one Mac (M1, OBS 33.0.1). Other Android phones are expected to work. |
| Android | Mac with an Intel processor | Not yet. The plugin is built for Apple Silicon only. |
| Android | Windows | Not yet. Planned for v0.4, after wireless. |
| Android | Linux | Not yet. Planned for later. |
| iPhone | Mac | Not supported by PhoneCam. Apple's built-in **Continuity Camera** already does this for free: in OBS, add a **Video Capture Device** and choose the iPhone (macOS 13+, iOS 16+). |
| iPhone | Windows | Not yet. Needs a separate iPhone app; planned for later. |

## Connections

| Connection | Works when | Notes |
|---|---|---|
| USB cable | USB debugging on, Mac allowed once | Fastest (≈140 Mb/s on a USB 2.0 phone) |
| Direct Wi-Fi | Paired once by QR or code; phone and Mac on the same network, or the Mac on the phone's hotspot | No Developer options for this part. Some routers keep devices apart (guest networks, some mesh or dual-band setups); use the same network name or the hotspot |
| Wireless debugging | Developer options; same Wi-Fi | ≈50–65 Mb/s through adb in our test; the bitrate lowers itself to fit |

## Why other Android phones should work, and what can differ

The app doesn't assume anything about the phone. On first open it asks the phone which cameras, sizes, frame rates and hardware encoders it has, and offers only those. So other phones get their own Best/High/Balanced choices. What varies by maker:

- **Extra lenses.** Some phones (like the vivo) hide their ultra-wide or telephoto cameras from other apps. PhoneCam can only offer lenses the phone exposes.
- **60 fps.** Many phones offer 60 fps only in their own camera app. PhoneCam shows 60 fps only where the phone offers it to other apps.
- **Heat.** Long 4K sessions warm some phones. The LIVE badge shows the temperature; choose High or Balanced if it gets hot.
- **USB debugging menus** are in slightly different places on each brand.

Whether it works or not, please send a **Device report** (GitHub → Issues → New issue → Device report). Each report builds the tested-phones list.

## What each missing combination needs

| Combination | Work needed | Size |
|---|---|---|
| Intel Mac | Build the plugin for both Intel and Apple Silicon ("universal") and test it on an Intel Mac. The code itself is portable. | Small |
| Windows + Android (v0.4) | A Windows OBS plugin: the same connection logic, with Windows' own hardware decoder (Media Foundation / Direct3D 11) instead of VideoToolbox, plus guided `adb` and USB-driver setup. The phone app stays the same. | Large |
| Linux + Android | A Linux plugin with VA-API or FFmpeg decoding. Same phone app. | Medium |
| iPhone + Windows | A new iPhone app (AVFoundation camera, VideoToolbox encoder) and a different USB transport (Apple's `usbmuxd`, which needs Apple's device software on Windows). Distribution through the App Store needs the Apple Developer Program ($99/year) and app review. The OBS plugin's decoder and display code can be shared. | Very large |
| iPhone + Mac | Covered by Continuity Camera. A PhoneCam iPhone app would only be worth it for features Continuity Camera lacks. | Later, if ever |

The phases are tracked in the [roadmap](ROADMAP.md).
