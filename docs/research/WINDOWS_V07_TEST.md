# v0.7 on a real Windows laptop (25 November 2026)

A friend's Windows laptop (borrowed for one session), the vivo V2437 (Android 16) with app 0.7.0-alpha.1 from `dist/0.7.0-alpha.1/` (built from 17512b9), and the Windows plugin installer `PhoneCam-OBS-plugin-windows-x64-0.7.0-alpha.1-setup.exe` from CI run 45231935828 (commit 24f89cb; the plugin code is the same as 17512b9). OBS freshly installed from obsproject.com. Google's platform-tools moved to `C:\platform-tools`. The owner ran each check, following [tests/WINDOWS.md](../tests/WINDOWS.md) in a shortened order because the laptop was only available briefly. Windows version, OBS version and hardware were not written down.

| Check (tests/WINDOWS.md) | Result |
|---|---|
| S1. The phone shows up on Windows | **Pass.** `adb devices` listed the phone as `device` after "Allow USB debugging"; Windows' own driver was enough. |
| 2. Installer, plugin and source | **Pass.** Double-clicking the installer (SmartScreen: More info → Run anyway, admin prompt) installed it; after restarting OBS the PhoneCam source was there. The log has the `Built for OBS 33.0 and newer; running in OBS …` line. |
| 3. Live over USB | **Pass.** Front and rear camera, no drops. |
| 5. Switching cameras | **Pass.** |
| 14. Phone section in OBS (camera, quality, torch, mirror, Stop live) | **Pass.** |
| 0.7 recording options: start with the phone, stop with the phone | **Pass.** |
| 6. Quality changes while recording | **Pass.** The recording kept going. Best (4K) dropped about 180 frames on this laptop; Balanced added none. |
| 16. Microphone off / on / off | **Pass.** |
| 17. Screen sharing, entire screen, phone sound (0.7) | **Pass.** |
| 4. Rotation | **Pass.** |
| 19. Unplug during live | **Pass.** Live ended on the phone and the **OBS recording kept going**, as designed (a lost cable never stops OBS recording). After the replug and a tap on record the picture came back. Wi-Fi was not paired yet, so no Wi-Fi fallback was possible (the app does not switch to Wi-Fi by itself during live). |
| 20. Hide and show | **Pass.** |
| 12/13. Leaving the app (notification, picture-in-picture, Stop) | **Pass.** |
| 21. Quit OBS while live | **Pass** (OBS closed, the phone said live ended). |
| S4. Nothing left running | **Pass** on the re-check. The first time an `adb.exe (32 bit)` stayed: the owner had started adb by hand from PowerShell before OBS, and the plugin reuses an adb server it did not start and never ends it. After `adb kill-server`, OBS live and quitting OBS left no adb running. |
| 8. Wi-Fi pairing by QR (laptop on the phone's hotspot) | **Pass.** |
| 10. Live over direct Wi-Fi | **Pass.** |
| 11. USB preferred when the cable is in | **Pass.** |

Afterwards the laptop was cleaned up: plugin uninstalled, `C:\platform-tools` deleted, firewall entry removed, the phone's USB debugging authorisations revoked and the laptop forgotten in Paired computers.

Not run: 9 (typed code, expiry), 15 (hotkeys), 18 (warnings), 22 (30-minute run), S2 (antivirus noted), S3 (4K timing), O2.

## Follow-ups found

- **README, Windows install:**
  - put platform-tools at `C:\platform-tools`; the plugin doesn't look in Downloads;
  - expect several "Allow" prompts (SmartScreen, admin, USB debugging, firewall), with a plain explanation of each and that PhoneCam collects no data;
  - add screenshots (owner).
- **Plugin:** also find adb in the usual unzip places, such as `Downloads\platform-tools*\platform-tools`, or point to the "adb path" setting in the message.
- **Recording file names:** OBS names recordings itself (Settings → Advanced → Recording → Filename Formatting). Give a README tip such as `PhoneCam %CCYY-%MM-%DD %hh-%mm-%ss`. The plugin should not change OBS's global setting.
- **Idea (owner):** when the cable is pulled during live and the computer is paired over Wi-Fi, continue over Wi-Fi by itself.
- **4K on Windows laptops:** drops at Best, as on the v0.4 laptop. Measure with S3 on the next Windows PC.
