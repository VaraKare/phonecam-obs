# PhoneCam OBS

Use your Android phone's camera as a high-quality video source in [OBS Studio](https://obsproject.com) on your Mac, over a USB cable or Wi-Fi. Choose the rear or front camera and the quality on the phone, then add masks, filters and overlays and record or stream in OBS.

> **Preview release.** It has been tested on one phone (vivo V2437, Android 16) and one Mac (Apple M1, OBS 33.0.1). Other phones are expected to work if they support hardware H.264/H.265 video, but they are not yet verified. Windows support is planned; see [which phones and computers work](docs/COMPATIBILITY.md) and the [roadmap](docs/ROADMAP.md).

- **Rear or front camera**, up to 4K where your phone allows it, encoded in hardware on the phone and decoded in hardware on the Mac.
- **Any orientation:** portrait or landscape, and turning the phone during a session keeps the picture upright in OBS.
- **Automatic connection:** plug in, open OBS, tap **Start live** on the phone. No commands to type.
- **Private:** the phone saves nothing during live sessions. No microphone, no Internet access, no accounts, no analytics.

## What you need

- An Android phone with Android 10 or newer, and a USB **data** cable (some cables only charge).
- A Mac with Apple Silicon (M1 or newer) and [OBS Studio](https://obsproject.com) 33.
- Android platform-tools (`adb`), a small free tool from Google that lets the Mac talk to the phone over USB.

## Install (one time)

### 1. Install adb on the Mac

With [Homebrew](https://brew.sh):

```sh
brew install android-platform-tools
```

Or download [SDK Platform-Tools for Mac](https://developer.android.com/tools/releases/platform-tools) and unzip it into `~/Library/Android/sdk/platform-tools`.

### 2. Turn on USB debugging on the phone

1. Open **Settings → About phone** and tap **Build number** seven times until it says you're a developer. On some phones it's under **Software information**.
2. Go back to **Settings → System → Developer options** (location varies by phone maker) and turn on **USB debugging**.
3. Connect the phone to the Mac. When the phone asks **Allow USB debugging?**, tick **Always allow from this computer** and tap **Allow**.

<!-- IMAGE: docs/images/android-build-number.png — tapping Build number -->
<!-- IMAGE: docs/images/android-usb-debugging.png — Developer options with USB debugging on -->
<!-- IMAGE: docs/images/android-allow-debugging.png — the Allow USB debugging prompt -->

Only allow debugging from computers you trust. You can turn it off again in Developer options at any time.

### 3. Install the phone app

Download `PhoneCam-<version>.apk` from the [latest release](../../releases) on the phone and open it. Android asks you to allow installing apps from your browser or file manager the first time; allow it for this install only.

<!-- IMAGE: docs/images/android-install-apk.png — installing the APK -->

### 4. Install the OBS plugin

1. Quit OBS.
2. Download `PhoneCam-OBS-plugin-macos-arm64-<version>.zip` from the [latest release](../../releases) and unzip it.
3. Copy `phonecam-usb.plugin` into `~/Library/Application Support/obs-studio/plugins/` (in Finder: **Go → Go to Folder…**, paste the path; create the `plugins` folder if it's missing).
4. The preview plugin isn't signed by Apple, so macOS blocks downloaded copies. Open **Terminal** and run:

   ```sh
   xattr -dr com.apple.quarantine ~/Library/Application\ Support/obs-studio/plugins/phonecam-usb.plugin
   ```

<!-- IMAGE: docs/images/mac-plugins-folder.png — the plugin in the OBS plugins folder -->

## Use it

1. Connect the phone with the USB cable and open **PhoneCam** on the phone. Allow camera access the first time.
2. Open OBS. In **Sources**, click **+** and choose **PhoneCam (phone camera)**, then **OK**.
3. On the phone, pick **Rear** or **Front** and a **Quality** (Best is the default), then tap **Start live to OBS**.
4. The phone shows **● LIVE** and your camera appears in OBS within a few seconds.
5. The picture fits your canvas automatically, in portrait and landscape. If you had already moved or resized the source, open its **Properties** and click **Fit to canvas now**. For full-screen vertical video, set the canvas to 1080×1920 in **Settings → Video**.
6. Add masks and filters as with any camera: right-click the source → **Filters** (for example **Image Mask/Blend**, **Crop/Pad**, **Color Correction**, **Chroma Key**). Then use OBS's **Start Recording** or **Start Streaming**.
7. Tap **Stop live** on the phone when you're done.

<!-- IMAGE: docs/images/obs-add-source.png — Sources, +, PhoneCam -->
<!-- IMAGE: docs/images/phone-live.png — the phone showing LIVE -->
<!-- IMAGE: docs/images/obs-bounding-box.png — Edit Transform, Bounding Box Type -->
<!-- IMAGE: docs/images/obs-filters-mask.png — adding an Image Mask/Blend filter -->

To use the phone in Zoom, Meet or Teams, add it to an OBS scene and click **Start Virtual Camera** in OBS.

### Without a cable: Wi-Fi (Wireless debugging)

Uses Android's **Wireless debugging** (Android 11 or newer). The phone and Mac only need to be on the **same Wi-Fi network**; no Internet access is needed. A home router with the Internet down works. Video goes straight from the phone to the Mac over your local network, with the same pairing key as USB.

**Pair once:**
1. On the phone, open **Developer options → Wireless debugging**, turn it on (allow the network if asked), then tap **Pair device with pairing code**.
2. In OBS, open the PhoneCam source's **Properties**. Type the **IP address & Port** and the **6-digit code** the phone shows, then click **Pair over Wi-Fi**.

**Every time after that:** turn **Wireless debugging** on (tip: in Developer options → **Quick settings developer tiles**, add the Wireless debugging tile), keep the phone on the same Wi-Fi, and tap **Start live**. The plugin finds the phone by itself and the Status line says **Live over Wi-Fi**. If the cable is also plugged in, USB is used.

Good to know:
- Wi-Fi carries less than a cable. Start with **High** (1080p). Use 4K **Best** only on a strong 5 GHz or Wi-Fi 6 network, close to the router.
- The phone isn't charging, so long 4K sessions drain its battery. Without a cable, the phone also no longer charges from the laptop.
- Android turns Wireless debugging off when the phone changes Wi-Fi network, and usually after a restart. Turn it on again; you don't need to pair again.
- A hotspot from the phone itself usually doesn't work: Wireless debugging needs the phone to be joined to a Wi-Fi network.

<!-- IMAGE: docs/images/android-wireless-debugging.png — Wireless debugging and Pair device with pairing code -->
<!-- IMAGE: docs/images/obs-wifi-pairing.png — the Pair over Wi-Fi fields in the source properties -->

### Quality choices

| Choice | What it picks | When to use it |
|---|---|---|
| **Best** | The largest size your phone offers (often 4K), H.265, highest bitrate | Recording, when your phone stays cool |
| **High** | 1080p, highest bitrate | Live streaming; if Best fails or gets hot |
| **Balanced** | 1080p at about 10 Mb/s | Older phones, long sessions |
| **Smooth 60 fps** | 1080p at 60 fps (experimental, only where offered) | Fast motion, good light |
| **Custom** | Any profile from **Settings → Custom quality** | Fine-tuning |

While live, the controls under the preview adjust the camera without interrupting OBS: **zoom** (or pinch), **brightness**, **white balance**, **torch**, **tap the preview to focus** on a point, and **Mirror in OBS**. Zoom, brightness, white balance and mirror are remembered for each camera.

<!-- IMAGE: docs/images/phone-controls.png — the control strip while live -->

**Smooth 60 fps (experimental)** appears only on phones that can capture 1080p at 120 fps; PhoneCam then sends 60 fps. It may look darker indoors.

**Settings** on the phone also has the phone preview switch and extra sharpening/noise reduction (on by default; turn it off if the frame rate drops).

## Troubleshooting

Open the OBS source's **Properties**. The **Status** line says what the plugin is waiting for.

| Status or symptom | What to do |
|---|---|
| "adb not found" | Install platform-tools (step 1). If it's somewhere unusual, put its full path in the source's **adb path** field. |
| "Connect the phone with a USB data cable … or turn on Wireless debugging" | Try another cable or USB port and check that USB debugging is on. For Wi-Fi, turn Wireless debugging on and check that the phone and Mac are on the same network (pair first). |
| "Wi-Fi pairing failed" | The address and code change every time you open **Pair device with pairing code**. Open it again and type the new ones. |
| Drops or stutter over Wi-Fi | Choose **High** or **Balanced**, move closer to the router, or use the cable. |
| "Unlock the phone and tap Allow" | Unlock the phone and accept the USB debugging prompt. |
| "Install the PhoneCam app" | Install the phone app (step 3). |
| "Ready. Tap Start live on the phone." but the phone says "Waiting for OBS" | Make sure the source is visible (eye icon on) and only one PhoneCam source is in use. |
| Live stops when you switch apps | Keep PhoneCam open on screen while streaming. Locking the phone or leaving the app stops the camera on purpose. |
| Phone says "Warm" or "Hot" | Choose **High** or **Balanced**, take the case off, or keep the phone out of the sun. |
| Only the middle of the picture shows in OBS | The source was moved or resized by hand. Open its **Properties** → **Fit to canvas now** (or right-click → **Transform → Reset Transform**, then Start live again). |
| Phone says "OBS stopped receiving" | Normal when you hide the source, quit OBS or unplug. Show the source and tap Start live again. |

Still stuck? Open an issue with your phone model, Android version, OBS version and the Status text. If it works, a **Device report** issue helps others with the same phone.

## Privacy

The phone app has only the Camera permission. It has no microphone, Internet, location or storage access. Live video goes over the USB cable to OBS on your Mac and is never saved on the phone. The connection is protected by a key that only an authorized USB debugging connection can read. The optional **Research tools** in Settings can save test clips on the phone, but only when you tap Record there.

## Uninstall

- Phone: long-press PhoneCam → **Uninstall**. Turn off USB debugging if you no longer need it.
- Mac: quit OBS and delete `~/Library/Application Support/obs-studio/plugins/phonecam-usb.plugin`.

## Project

- **New here?** [How PhoneCam works and what each file does](docs/ARCHITECTURE.md).
- [Which phones and computers work](docs/COMPATIBILITY.md), including iPhone and Windows plans.
- [Roadmap](docs/ROADMAP.md): camera controls, Wi-Fi, Windows, then audio.
- [Building from source and research tools](docs/DEVELOPMENT.md).
- [How the USB connection works](docs/USB_LIVE.md), [phase tracker](docs/PHASES.md), [plan](docs/PLAN.md), [branch workflow](CONTRIBUTING.md), [data handling](docs/DATA.md).

License: GPL-2.0-or-later. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
