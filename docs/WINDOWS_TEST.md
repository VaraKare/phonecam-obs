# Windows test day checklist

For the first real test on a Windows PC (W5 in the [Windows plan](WINDOWS.md)). Same phone and app as on the Mac. Nothing here is a release; it's to prove or disprove the test build.

## Before

- Windows 10 (1809+) or 11, 64-bit, with **OBS Studio 33.0.1** (the plugin is built against it).
- The phone with PhoneCam 0.3 and USB debugging on; a USB data cable.

## Borrowed PC: portable kit (nothing installed)

For a PC that isn't yours. OBS runs in portable mode from one Desktop folder, which is deleted when OBS closes, together with anything adb created. The PC sends only its Windows version, processor, graphics, antivirus name and the PhoneCam log lines to your Mac.

On the Mac (same Wi-Fi as the PC; keep OBS on the Mac closed so the phone doesn't connect to it):

```bash
python3 tools/windows_kit.py build --run RUN_ID
```

```bash
python3 tools/windows_kit.py serve
```

`RUN_ID` is a green CI run on `feat/v0.4-windows` (`gh run list --branch feat/v0.4-windows`). `serve` prints one line. On the PC, open **Windows PowerShell** (not as administrator), type that line and press Enter. Plug the phone in when asked and tap **Allow** on it. OBS opens with the PhoneCam source; do the checks below, then close OBS. The window removes everything and the Mac receives "Left behind: nothing". Reports are in `native/build/windows-kit/reports/`.

- If macOS asks whether python3 may accept incoming connections, allow it.
- Wi-Fi is off in the kit unless you build it with `--wifi`. Answering Windows Firewall's prompt about OBS, even with Cancel, leaves a firewall entry that only an administrator can remove (the kit reports any it finds). Test Wi-Fi only on a PC whose owner agrees to that.
- Don't install anything Windows or the phone offers (drivers, "Mobile Assistant"); the phone may also show up as a CD drive, which can be ignored.

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
| Mirror | Front camera; the PhoneCam app's "Mirror in OBS" switch on and off (it is on the phone, not in OBS's source properties) |
| Fit | The whole picture fits the canvas; **Fit to canvas now** works after moving it |
| Stop and unplug | Stop: OBS returns to Ready. Unplug during live, wait 5 s, replug, tap Start live: video returns within about 15 s. Failed on 16 November 2026 (adb stopped answering); retest with the fix |
| CPU | Task Manager: OBS CPU use during 4K (rotation runs on the CPU in this build) |
| Wi-Fi pairing | Unplug the phone. **Pair a phone over Wi-Fi** shows the QR card; Windows Firewall asks once (allow on private networks); the phone pairs by QR and by typed code |
| Wi-Fi live | Start live on the same Wi-Fi: "Live over direct Wi-Fi" in the Status line; note fps and drops at High after 30 s |

Then copy the OBS log (Help → Log Files → Show Log Files) lines that start with `[phonecam]` into the research notes.
