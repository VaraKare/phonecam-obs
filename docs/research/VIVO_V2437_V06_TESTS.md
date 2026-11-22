# v0.6 tests on the vivo V2437 and the Mac (21–22 November 2026)

App and plugin 0.6.0-alpha.1 from `dist/0.6.0-alpha.1/` (rebuilt after each fix below), vivo V2437 (Android 16), OBS 33.0.1 on an Apple M1 Mac. The owner ran each check and reported the result; times come from the OBS log, because the phone does not keep this app's own log lines.

| # | Check | Result |
|---|---|---|
| 1 | New app, older 0.4.1 plugin | **Fail, then pass.** The first build sent its start timings (a new message type) to the old plugin, which ended every session with "Invalid PCAM type". Fixed in 2dc1cf8: nothing new is sent unless the plugin said hello. Then video worked and turning the microphone on stopped live with "The PhoneCam plugin in OBS is older than this app…" |
| 2 | OBS Phone section and hotkeys | **Pass.** Switch camera, quality, torch, mirror and stop all reached the phone (OBS log: `Phone command "…"`). Owner: "controls worked better and I liked it". |
| 2b | Stop from OBS | **Fail, then pass.** OBS said "video stalled for 3 seconds" instead of "the phone stopped sending": the phone closed its USB socket while the command reader was blocked on it, so the end never reached OBS. Fixed in 20e5d63 (shut down before close). |
| 2c | Shorter Phone section | **Pass.** OBS lays out one control per row; the section became two lists, two tick boxes and Stop (owner asked for side by side, which OBS's Properties can't do). |
| 3 | Start timings over USB | Tap → OBS connected 656 ms, camera open 1222 ms, first frame 1697 ms. The camera opened 566 ms after OBS connected because the encoder was set up first; a47ce38 opens the camera first. New timings: see below. |
| 4 | Icons, top status, What the buttons do | **Pass** (owner); one row of icons after the owner's layout request. |
| 5 | Microphone with the 0.6 plugin | **First try:** sound reached OBS's mixer, but OBS warned the source "is not assigned to any audio tracks": the source dated from before 0.6, when it had no sound. The owner ticked tracks 1–6 in Advanced Audio Properties and unmuted the source: **pass**, the voice was heard in the recording. The automatic fix (2e2a0a6, tracks 1–6 on the first sound, once) was not installed during the test (OBS stayed open), so it is not in the release and waits for the next one. Clap sync was not measured. |
| 6 | Screen sharing | **Pass.** The phone's prompt offered sharing one app; entire-screen choice is for a later version. |
| 7 | Warnings, alert when away, USB preferred, dimming | **Pass** (owner: "all the things work"). |

Not run: the 0.6 plugin on Windows; an hour with the microphone; screen sharing of the entire screen.

## Follow-up, 22 November (v0.7 test files)

- **Automatic audio tracks: not working, left as a documented step.** The plugin received the phone's sound (OBS log: "Receiving the phone's sound (48000 Hz, 1 channel)"), but OBS still showed the source as unassigned, and the recording had no phone sound. The plugin saw tracks on the source, so it didn't change them; why OBS still called it unassigned wasn't found. The owner chose to document the manual step (tick tracks 1–6, unmute the source) and move on. The README troubleshooting says so.
- **Faster start (a47ce38):** first picture 0.81–1.45 s after the tap over USB (was 1.70 s).

