# Third Windows laptop test (17 November 2026)

The same borrowed laptop as on 15 and 16 November (Windows 11 Home 26200, Ryzen 5 5600H, Radeon RX 5500M and integrated Radeon, 7.4 GB RAM, Avast and Defender). Phone: vivo V2437 with the app built from 72f5c6d (Stage 1 fixes). Portable kit with Wi-Fi on, built from CI run 44176489928 (commit 78c6c40), served over home Wi-Fi. The checks follow [audit/WINDOWS_PC_CHECKS_TOMORROW.md](../../audit/WINDOWS_PC_CHECKS_TOMORROW.md).

The owner chose not to tick "Always allow from this computer", so every replug asked for USB debugging again. That is expected and adds a few seconds each time.

## Run 1 (21:12–22:00)

| Check | Result | Evidence |
|---|---|---|
| 1. Replug during live (16 Nov failure) | **Passed, 4 of 4.** Video came back each time without restarting OBS. adb never stopped answering, so the restart path was not needed | Live ended 21:17:14 → phone seen again 21:17:37 → live 21:17:48 (11 s after "Ready", including the 10 s wait). Same pattern at 21:20, 21:21, 21:22 |
| 2. Status after recovery | Not reached (adb never got stuck) | — |
| 3. Stop while waiting, then Start (AUDIT-001) | Done on the phone; exact message not recorded | — |
| 4. Hide/show 10 times | **Passed (7 cycles).** No freeze; every hide ended live and the next Start reconnected in 1–3 s | 21:25:00–21:26:08 |
| 5. Rendering lag | Not recorded | — |
| 6. 4K portrait, 2 minutes | **Falls behind.** 30 fps per second when frames arrive, but only 5,345 frames in 218 s (~24.5 fps reached OBS). Average 32.4 ms per frame against a 33 ms budget: copy from GPU 27.9 ms, rotate 2.5 ms, decode 0.6 ms, hand to OBS 1.5 ms. 28 % of frames over 33 ms, slowest 67 ms. The phone reported "Hot — consider stopping" | "PC frame timing" lines 21:27:09–21:30:46. 16 Nov baseline: ~31 ms, ~4 % late |
| 7. 4K on the integrated GPU | Skipped (would leave a Graphics setting on a borrowed PC; the GPU-copy redesign matters more) | — |
| 9. Wi-Fi pairing by QR | **Passed once**, then **failed**: after the phone forgot the pairing, two new pairing windows were refused (phone: `ECONNREFUSED` on port 47183). The expired QR card stayed on screen, and OBS took about 45 s to close | Paired 21:38:56; windows 21:42:17 and 21:42:38 with no pairing attempt logged and no "Pairing window closed" at expiry; "Shutting down" 21:59:12, report 21:59:58 |
| USB with an unauthorized phone | The plugin searched for Wireless debugging phones while the cable phone waited for Allow, and the watchdog's "Not responding…" status stayed afterwards | 21:53:27–21:59 |
| Cleanup | "Left behind: nothing"; no firewall entries. No Windows Firewall prompt appeared at all | cleanup report 22:00:44 |

## Causes and fixes (same night)

- **Wi-Fi refused after a pairing.** The server thread that accepts phones also re-announced the service with Windows DNS-SD after each pairing. While that call was slow, nothing was accepted: queued connections filled the backlog and Windows refused the rest, the expiry check never ran, and closing OBS waited for it. Windows CI's Kotlin interop test (pair, then connect again) failed the same way on three runs from 66e4976. Fixed in 01aa7db: DNS-SD runs on its own thread.
- **Expired card on screen.** The plugin now hides the card after 3 minutes from its own timer (7871251).
- **Diagnosis next time.** The server records its step; the plugin logs any step (or a DNS-SD call) stuck for 10 s, and logs when the listener opens, closes or fails to accept (7871251, 3659ceb).
- **Wireless-debugging search while a USB phone waits for Allow.** Skipped now (AUDIT-008, 7871251).
- **"Not responding…" left on screen.** The previous status returns when progress resumes (AUDIT-022a, 7871251).
- **4K.** Needs the picture to stay on the GPU into OBS (AUDIT-009 C); not attempted tonight.
- **"Hot" chip frozen after Stop** (phone): follows Android's thermal status now (66e4976).
- **Owner request:** "Keep the phone live while this source is hidden" option (66e4976), and hide waits at most 0.5 s (AUDIT-002, 293c81e).

All of these are unproven on the laptop until the retest below.

## Run 2 (22:18–22:24, kit from run 44187960039)

Check 9 again: the first QR pairing worked (22:20:33); the new watchdog then logged "Wi-Fi server: no progress for 10 s while saving the pairing", so the server thread was stuck inside the plugin's status callback right after "Paired with V2437". The second pairing window got no connection, and OBS showed "Not responding" on close until the owner chose "Close the program". Cleanup: "Left behind: nothing". Fixed in 17e7698: the Wi-Fi thread only stores and logs the status and queues an OBS UI task for the status line and the card. (Windows CI's intermittent interop failure turned out to be the test receiver's 120 s limit, fixed in a3bb3b7.)

## Run 3 (from 22:34, kit from run 44190555302)

| Check | Result | Evidence |
|---|---|---|
| 9. Wi-Fi pairing, forget on the phone, pair again | **Passed.** Both pairings worked and no watchdog warning appeared | Paired 22:40:37 and 22:41:02 |
| 10. Wi-Fi live, rear 1080p | **Passed.** 3.5 minutes at 1080×1920 with hardware decoding, no "fell behind" lines; the owner saw no freeze and no drops on the phone badge (phone "Warm"). Stopped by the owner | Live 22:41:36 → Stop 22:45:05 |
| First Start after OBS opens | Ended after 2 s (also on USB in run 1): Windows sets up the D3D11 decoder on the first picture (1.4 s) and reads nothing meanwhile; the phone gives up (750 ms USB, 3 s Wi-Fi). The second Start works | 22:41:12 first frame "decode 1430.7 ms", ended 22:41:14. Phone fix in 80513ef (4 s writes in a session's first 5 s); not yet installed on the phone |
| 11. Wi-Fi switch off | **Passed (AUDIT-006 fixed on hardware).** After unticking, the server stopped and a connection from the Mac to port 47183 was refused | 22:50:18 "Wi-Fi: stopped listening"; `nc` refused |
| 12. LAN noise during Wi-Fi live | **No freeze.** 50 empty connections in 33 s: 4 queued, 46 refused (backlog full); live continued and the owner saw no freeze, also after Stop and Start. When live ended, each queued empty connection set the status to "Wi-Fi live ended: connection closed": **AUDIT-005 confirmed** (status overwritten, and each queued connection holds the server up to 5 s) | 22:52:47–22:53:20; three status lines at 22:53:47 |
| 17 (first try). Long run over Wi-Fi | Stopped after 4.5 min: the USB thread hung at 22:59 after "Unlock the phone…" with the Properties window open, and later Wi-Fi sessions stalled after a few seconds. OBS memory stayed between about 60 and 140 MB | Same cause as the Wi-Fi hang: refreshing the properties from a worker thread. Fixed for every status update in 0b53c03 |

## Run 4 (from 23:18, kit from run 44196193195, commit 0b53c03; phone app from e761846/80513ef installed 23:20)

| Check | Result | Evidence |
|---|---|---|
| First Start after OBS opens | **Passed (fix 80513ef works).** The first picture took 659 ms to decode and the phone kept sending | 23:23:39 |
| 17. Long run, USB rear 1080p | **13 minutes, no late frames on the PC.** Phone badge: 30 fps, 37 drops, timer about 13 min. Stopped by the owner. Heat chip showed Hot during the run, Warm a few seconds after Stop, then disappeared (66e4976 verified). The 30-minute target was not reached | 23:23:39 → 23:36:52 |
| Phone live timer (e761846) | **Works.** The LIVE badge counted up to about 13 minutes | Owner |

Phone UI: after Stop the badge said "Wi-Fi ready · <Mac>" although the laptop was used last. It showed the oldest pairing. It now shows the most recently used computer plus the number of others (" +1").
| 18. Cleanup | **Passed.** OBS closed in about 3 s (23:45:57 → 23:46:00; it took 45–50 s or hung before the fixes). "Left behind: nothing", no firewall entries, in all four runs. The owner then revoked USB debugging authorisations on the phone and forgot the laptop's Wi-Fi pairing | cleanup 23:46:24 |

## Not done on this laptop

- **13** (adb blocked by antivirus) and **14** (first-run pairing store). Both need files changed before OBS starts, and the kit deletes its folder when OBS closes. CI covers the code paths (damaged store, missing adb). Left for user reports after release.
- **15–16** (mirror and colour against the Mac): mirror passed on 16 Nov; colour comparison later on the Mac.
- **7** (integrated GPU): skipped so that no Graphics setting is left on a borrowed PC.
- **4K**: falls behind on this laptop (about 24 of 30 fps reach OBS; the copy from the GPU takes about 28 ms). Needs AUDIT-009 C.
- **30–60 minute run**: 13 minutes reached; a longer run can be done on the Mac.

## Release status

The Windows plugin built from 0b53c03 (CI run 44196193195) is the file proven here for:
- USB live;
- replug;
- hide/show;
- Wi-Fi pairing twice;
- Wi-Fi live;
- the Wi-Fi switch;
- LAN noise;
- a 13-minute run;
- a clean exit.

The Mac plugin and the phone app changed tonight as well (hide hand-off, keep live while hidden, ⓘ help, card timer, DNS-SD thread, AUDIT-005, live timer, badge), so they need a Mac and phone check before any v0.4 release.

## Mac check of the same night's changes (18 November 2026, 00:02–00:40)

M1 Mac, OBS 33.0.1, the phone on the app from dd80efd (release-signed, installed 00:02).

| Check | Result | Evidence |
|---|---|---|
| USB live, first Start | **Passed.** Picture good, no drops; the live timer counted | Live 00:10:34, landscape and portrait |
| Hide and show (keep live off) | **Passed.** Wi-Fi stopped 0.2 s after hide and listened again 0.03 s after show | 00:15:05–00:15:11 |
| Keep live while hidden | **Passed.** With the box ticked, hiding and showing left the phone live | 00:16:49–00:16:54 |
| Help text (long descriptions) | Shown in Properties as text; no ⓘ icon on the Mac | Owner |
| Wi-Fi pairing | **Failed twice, then passed.** Clicking "Pair a phone over Wi-Fi" hung OBS (beachball). `sample` showed a self-deadlock: the button held wifiLock, setting the status refreshed the Properties synchronously, and properties() waited for wifiLock. Queuing a UI task was not enough, because OBS runs UI tasks at once on the UI thread (second sample). Fixed in 07b6c52: the status is set after the lock is released, and properties() only try-locks. Then the pairing at 00:34:08 worked, OBS stayed responsive, and live ran with keep-live hide/show | Samples kept outside Git; log 00:34:04–00:35:24 |
| Live after pairing | Went over adb Wireless debugging ("Live over Wi-Fi"), because the phone still had Wireless debugging on. Direct Wi-Fi on the Mac passed on 17 Nov | 00:34:37 |
| Quit OBS | **Passed.** Closed within a second | Owner |

The Windows plugin also changed in 07b6c52 (same lock rule). That build has passed CI only; the laptop proved 0b53c03.
