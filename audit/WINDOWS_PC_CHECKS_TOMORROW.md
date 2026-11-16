# Windows PC checks for tomorrow (17 November 2026)

These are the audit steps that need the borrowed Windows laptop (Windows 11, Ryzen 5 5600H, RX 5500M, Avast). The owner agreed to a Windows Firewall entry, so the Wi-Fi checks are in scope.

Nothing here can be marked passed from CI. Record results in a new `docs/research/WINDOWS_THIRD_LAPTOP_TEST.md` with times and log lines, and keep personal details (user name, addresses, serials) out of it.

## Before the laptop arrives (Mac)

1. Stage 1 fixes are in (see [REMEDIATION_LOG.md](REMEDIATION_LOG.md)):
   - AUDIT-001 (phone listener);
   - AUDIT-003 (names);
   - AUDIT-004 (catches);
   - AUDIT-006 (Wi-Fi switch and listening only when needed);
   - AUDIT-016 A (kit hash check, slimmer reports).

   Install the phone app built from the same commit, because check 3 needs the AUDIT-001 fix.
2. Push and wait for a green Windows CI run. Note its run id.
3. Build the kit with Wi-Fi on (owner agreed):
   ```bash
   python3 tools/windows_kit.py build --run RUN_ID --wifi
   ```
4. Install the matching phone app on the phone.
5. Start the server; it prints the one line to type on the PC:
   ```bash
   python3 tools/windows_kit.py serve
   ```
6. **Network:** use your home Wi-Fi only. The kit is served over plain HTTP (AUDIT-016).

## On the PC: order of checks

Each check lists what to do, what passes, and the related finding. Run them in this order; stop if something blocks.

### 1. Replug (the 16 Nov failure; needs the 9f0b0cb fix, already in CI)

1. Rear, 1080p, Start USB live; wait for video.
2. Unplug for 5 s, plug back in, wait 10 s, tap Start.

Pass:
- Video returns without restarting OBS.
- The log shows "adb stopped answering. Restarting it…" and then "Live over USB".
- Note how many seconds it took (reviewers estimate 40–50 s for a stuck adb; anything under 60 s is acceptable for now).
- Repeat 5 times.

Finding: replug fix; AUDIT-012 timing.

### 2. Status after recovery

After check 1, read the PhoneCam status line in the source Properties.

Pass: it does **not** still say "Not responding…" once video is back.

Finding: AUDIT-022a.

### 3. Stop while waiting, then Start (phone side)

1. Unplug the cable.
2. On the phone tap Start, wait 3 s, tap Stop, then tap Start again.
3. Write down the exact message.

Pass:
- With today's app, "Address already in use" confirms AUDIT-001.
- With the fixed app, it waits normally.

Finding: AUDIT-001.

### 4. Hiding the source and closing OBS never hang

1. While live, hide/show the source with the eye icon 10 times.
2. Then close OBS.

Pass:
- Each hide returns within a second or two.
- OBS closes within 10 s.
- The kit reports cleanup.

Finding: AUDIT-002.

### 5. Rendering lag from hiding

1. Start a recording in OBS (to the kit folder).
2. Switch scenes away from and back to PhoneCam 20 times.
3. Read View → Stats → "Frames missed due to rendering lag".

Pass: a small number (under ~20). Record the number either way.

Finding: AUDIT-002.

### 6. 4K timing

1. Rear 4K portrait for 2 minutes; copy the "PC frame timing" lines.
2. Compare them with the phone's drop count and bitrate badge.

Pass: record the numbers. Today's baseline is about 31 ms per frame, 4% late.

Finding: AUDIT-009.

### 7. 4K on the other GPU

Repeat check 6 with OBS set to the integrated GPU (Windows Settings → Display → Graphics → OBS → Power saving).

Pass: record the numbers.

Finding: AUDIT-009 B.

### 8. Two PhoneCam sources

1. Add a second PhoneCam source to the scene and tap Start.
2. Record both status lines for one minute.

Pass:
- Today: expect one source to loop "video stalled".
- After AUDIT-011 A: one clear "another source" message.

Finding: AUDIT-011.

### 9. Wi-Fi pairing (firewall prompt; owner agreed)

1. Unplug the phone.
2. In the source Properties click "Pair a phone over Wi-Fi".
3. Allow the firewall prompt on **private** networks.
4. Pair once by QR, then forget it and pair by typed code.

Pass:
- Both pairings work.
- Note the firewall entry's name for the cleanup report.

Finding: W4 on hardware.

### 10. Wi-Fi live

Start live over Wi-Fi at 1080p for 5 minutes.

Pass:
- Video with no freeze over 1 s.
- Drop counts recorded.

Finding: W4/W5.

### 11. Wi-Fi switch off

1. Untick "Allow paired phones over Wi-Fi" and click OK, without hiding the source.
2. On the Mac, from Terminal, try to connect to the PC on port 47183. Use the PC's address, which the kit report shows; don't write it in docs:
   ```bash
   nc -vz PC_ADDRESS 47183
   ```

Pass:
- Today: the port is still open (confirms AUDIT-006).
- After the fix: refused.

Finding: AUDIT-006.

### 12. LAN noise

While live over Wi-Fi, from the Mac run this for 30 s:
```bash
for i in $(seq 1 50); do nc -z PC_ADDRESS 47183; done
```

Pass:
- Today: the status line changes to "Wi-Fi live ended…" (confirms AUDIT-005), and live may stall.
- After the fix: no change.

Finding: AUDIT-005.

### 13. adb blocked by antivirus

1. Close OBS.
2. In the kit folder, rename `platform-tools\adb.exe` to `adb.off`.
3. Start OBS from the kit again.

Pass:
- Today: the status probably says "Ready… connect a USB cable" (confirms AUDIT-022b).
- Hiding the source returns quickly, and OBS closes cleanly.
- Rename it back afterwards.

Finding: AUDIT-022b, 010.

### 14. First-run pairing store

1. With OBS closed, delete `%APPDATA%\phonecam-obs` if the kit created it.
2. Open OBS.
3. Open and close the PhoneCam Properties 10 times quickly.

Pass: no crash.

Finding: AUDIT-004.

### 15. Front camera, portrait, Mirror

1. Turn Mirror on in the phone app.
2. Compare with the same setup on the Mac later.

Pass: same orientation on both.

Finding: rotation/mirror agreement.

### 16. Full-range colour

1. Point the camera at a black object and a white sheet.
2. Screenshot OBS on Windows, then on the Mac with the same scene.

Pass: similar black and white levels.

Finding: AUDIT-022c.

### 17. Long run

USB 1080p for 30–60 minutes.

Pass:
- No freeze; OBS memory in Task Manager steady.
- Phone temperature noted.

Finding: Long-duration plan.

### 18. Cleanup

Close OBS and read the kit's cleanup report.

Pass:
- "Left behind: nothing" apart from the agreed firewall entry, which is named.
- Afterwards, on the phone: Developer options → **Revoke USB debugging authorisations**, because the PC's own adb key stays trusted otherwise (AUDIT-023b).

Finding: AUDIT-016, 023b.

## What to bring back

The kit's reports from all runs, the numbers from checks 1, 5, 6, 7 and 17, and the exact messages from checks 2, 3, 8, 11, 12 and 13. These decide:
- whether the replug and Stop fixes are proven;
- whether GPU work (AUDIT-009 C) is needed for 4K;
- whether W5 can be marked passed. That also needs the release-file checks in TEST_AND_VALIDATION_PLAN §9.
