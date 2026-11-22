# v0.7 tests on the vivo V2437 and the Mac (22 November 2026)

App and plugin 0.7.0-alpha.1 from `dist/0.7.0-alpha.1/` (rebuilt after each fix), vivo V2437 (Android 16), OBS 33.0.1 on an Apple M1 Mac. The owner ran each check; numbers come from the OBS log (the plugin's once-a-minute summary), because the phone drops this app's own log lines.

| # | Check | Result |
|---|---|---|
| 1 | Mac installer (.pkg) | **Pass.** Installed the 0.7 plugin into OBS's plugins folder with no password and no Terminal step; signature valid (`codesign --verify --strict`); OBS recorded. Installing a newer .pkg over it also worked. |
| 2 | Faster start (camera opens while the encoder is set up) | **Pass.** First picture 0.65–1.45 s after the tap over USB (v0.6: 1.70 s). |
| 3 | Automatic audio tracks for a source with none | **Fail, left as a documented step.** The plugin received the phone's sound but OBS still showed the source as unassigned. The README says to tick tracks 1–6 and unmute the source. |
| 4 | Clap sync | **Not measured.** A finger snap was too small to see in the video; `tools/sync_check.py` is ready for a two-hand clap. |
| 5 | Screen sharing: Entire screen / One app choice | **Pass.** Both worked. |
| 6 | Screen sharing smoothness | **Fail, then pass.** The phone's 120 Hz screen made the encoder send ~53 fps; the queue dropped 262 frames in 32 s and delay built up. Capped at 30 fps (1872399): 0 dropped frames in the next minute. |
| 7 | Phone sound | **Fail, then pass.** Android had the audio permission as "Only this time", which ends when PhoneCam leaves the screen (screen sharing always does), so the app turned the sound off and sent none (recording at −91 dB). Fixed in 5427c2e: the app asks again at start and says to choose "While using the app"; phone sound keeps the microphone service type. Then: mic on and phone sound on, peak 53 % in the OBS log; owner heard it in the recording and liked the output. |
| 8 | Cleaner pop-ups (bottom sheets) | Owner saw them during the screen tests; no complaint. |

Not run yet: Windows PC, Android tablet, the 2-hour run, 20 replugs, camera-to-OBS delay (`tools/latency_clock.html`), Intel Mac (no device).
