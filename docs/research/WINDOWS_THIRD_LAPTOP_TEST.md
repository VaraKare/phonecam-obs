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

## Run 2 (retest with the fixes)

Pending.
