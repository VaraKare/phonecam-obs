# Second real Windows test (16 November 2026)

The same borrowed laptop as the [first test](WINDOWS_FIRST_LAPTOP_TEST.md) (Windows 11 Home, Ryzen 5 5600H, Radeon RX 5500M, Avast and Defender), the vivo V2437 with PhoneCam 0.3.0-alpha.5, and the Windows test build from CI run 43864249267, which has the fixes from the first test. It ran twice from the reusable portable kit (`tools/windows_kit.py`). After each run the kit reported "Left behind: nothing" and no firewall entries. Wi-Fi was off in the kit, so direct Wi-Fi was not tested.

## Results

| Check | Result |
|---|---|
| adb server start under Avast | **Passed**: the kit's foreground-mode server started at once in both runs |
| Phone found over USB | **Passed**: Windows' own "ADB Interface" driver |
| Live over USB, 1080p (rear, 20 Mb/s H.265) | **Passed**: 128 seconds with no frame over 33 ms on the PC |
| Live over USB, 4K portrait (60 Mb/s H.265) | **Much faster, not quite smooth**: about 31 ms per frame (45–60 ms before). PC frame timing: decode 0.5 ms, copy from the graphics card about 24 ms, rotate about 5 ms, hand to OBS about 2 ms. About 4% of frames took 34–37 ms |
| Rotation | **Passed**: turning the phone between portrait and landscape during live turned the picture in OBS seamlessly |
| Front camera with Mirror | **Passed**: the phone app's "Mirror in OBS" switch mirrored the picture in OBS |
| Unplug and replug during live | **Failed in both runs**: see below |

This is still a partial W5 result: replugging failed, 4K is borderline, and direct Wi-Fi was not tested.

## Replug failure

After the cable was unplugged during live, the plugin logged "Live ended" and then "Ready", and never reported anything again. That included the time the phone was plugged back in, the source being hidden and shown, and adb being restarted by hand. Closing OBS hung, and its log stops at "Shutting down". What was found on the laptop:

- The phone was connected again ("ADB Interface", status OK).
- adb's server was running and listening, but no client got an answer (`adb devices` printed nothing). About 700 recently closed connections to it had built up within a few minutes.
- After that server was ended by hand, nothing started a new one. So the plugin's connection thread was stuck in a call with no time limit, and OBS waited for that thread when the source was hidden and when it closed.

**Changes (unproven on real hardware):**
- After a live session drops, the plugin removes the USB link and finds the phone again, as at start.
- Two adb calls in a row without an answer restart adb's server:
  - `adb kill-server`;
  - on Windows, also the plugin's own server, or a stuck `adb.exe` still holding the port.
- On Windows, every adb call has a time limit, including starting the adb process itself. The link to the phone also gets a 3-second connect limit.
- OBS waits at most a few seconds for the connection thread. A thread that doesn't stop is left behind instead of freezing OBS.
- A step that makes no progress for 15 seconds is logged by name ("No progress for 15 s while …"). If the exact stuck call is something else, the next test shows it.

A new CI test reproduces the failure with a fake adb that stops answering and ignores kill-server. The same scenario is tested on the Mac.

## Other findings

- **Camera "already in use".** After the failed replug, tapping Stop while the phone waited for the PC, and then starting again, gave "already in use". The phone app should release the camera in that case. Still to check.
- **Changing camera or quality during live.** These are locked during live by design; the user would like them to work, as a quick automatic restart.
- **Grain in the 1080p picture.** This came from the room lighting: the phone was sending 1080p at 20 Mb/s.
- **The kit's live phone log was always empty.** `logcat -t 80` takes the last 80 lines before filtering for PhoneCam. Fixed in the kit.
