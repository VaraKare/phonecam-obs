# Windows test day checklist

For the first real test on a Windows PC (W5 in the [Windows plan](WINDOWS.md)). Same phone and app as on the Mac. Nothing here is a release; it's to prove or disprove the test build.

## Before

- Windows 10 (1809+) or 11, 64-bit, with **OBS Studio 33.0.1** (the plugin is built against it).
- The phone with PhoneCam 0.3 and USB debugging on; a USB data cable.

## Install (about 10 minutes)

1. **adb:** download [SDK Platform-Tools for Windows](https://developer.android.com/tools/releases/platform-tools), unzip it to `C:\platform-tools` (the plugin looks there automatically).
2. **Plugin:** download the `phonecam-obs-windows-test` artifact from the latest GitHub Actions run on `feat/v0.4-windows`, unzip it, and copy `phonecam-obs.dll` to `C:\ProgramData\obs-studio\plugins\phonecam-obs\bin\64bit\` (create the folders). Start OBS.
3. **Phone driver:** plug the phone in. On the first test laptop Windows' own driver was enough (Device Manager showed "ADB Interface"), and antivirus (Avast) blocked adb's background start; the plugin now starts adb's server itself. If the phone never shows **Allow USB debugging?**, Windows needs the phone maker's USB driver (or Google's USB driver from Android Studio's SDK Manager). Check with `C:\platform-tools\adb devices`.
4. On the phone, tick **Always allow from this computer** and tap **Allow**.

## Checks (write down what you see)

| Check | Pass when |
|---|---|
| Source appears | Sources → + → **PhoneCam (phone camera)** exists |
| Status | Properties says "Ready. Tap Start live on the phone." |
| 1080p live | Rear, High, Start: picture in OBS; note the Status line (hardware or software decoding) |
| 4K live | Rear, Best: picture in OBS; note fps and drops on the phone badge after 30 s, and copy any "PC frame timing" lines from the OBS log (Help → Log Files) |
| Rotation | Portrait and both landscapes stay upright in OBS |
| Mirror | Front camera; Mirror in OBS on and off |
| Fit | The whole picture fits the canvas; **Fit to canvas now** works after moving it |
| Stop and unplug | Stop: OBS returns to Ready. Unplug and replug: it reconnects by itself |
| CPU | Task Manager: OBS CPU use during 4K (rotation runs on the CPU in this build) |
| Wi-Fi pairing | Unplug the phone. **Pair a phone over Wi-Fi** shows the QR card; Windows Firewall asks once (allow on private networks); the phone pairs by QR and by typed code |
| Wi-Fi live | Start live on the same Wi-Fi: "Live over direct Wi-Fi" in the Status line; note fps and drops at High after 30 s |

Then copy the OBS log (Help → Log Files → Show Log Files) lines that start with `[phonecam]` into the research notes.
