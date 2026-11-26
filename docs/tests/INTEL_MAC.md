# Test checklist: Android phone and Intel Mac

Ticket: [#3](https://github.com/VaraKare/phonecam-obs/issues/3). Status in [COMPATIBILITY.md](../COMPATIBILITY.md): Needs a device (ticket).

Use this list to test the PhoneCam OBS plugin on a Mac with an Intel processor. Today's tested plugin runs on Apple Silicon Macs only. The universal plugin (one file for both kinds of Mac) is built, but it has never run on an Intel Mac.

Nothing here counts as tested until a real Intel Mac, a real phone and real OBS pass it.

## What you need

- A Mac with an **Intel** processor, macOS 13 or newer.
- The Intel build of [OBS Studio](https://obsproject.com) 33.
- An Android phone with Android 10 or newer, and a USB **data** cable.
- A Wi-Fi network that the phone and the Mac are both on (checks 8 to 11). The Internet is not needed.
- The PhoneCam APK and the **universal** Mac plugin zip that the owner names for this test.
- A text file or paper to write results in. About two hours, plus the 30-minute run.

## How to install

Follow the README's [Install section](../../README.md#install-one-time) for the phone and the Mac:
1. Install `adb` (step 1).
2. Turn on USB debugging on the phone (step 2).
3. Install the phone app (step 3). Use the APK the owner names for this test: the v0.7 release, or a test build.
4. Install the Mac plugin (step 4). Use the zip the owner names for this test.

If the release has an installer or its own install notes, use those instead of step 4 and write down which you used.

## Before you start

- Write down the Mac's model, year and macOS version (Apple menu → About This Mac).
- Plug the Mac into its charger.

## How to use this list

- Do the checks in order. They are numbered 1 to 22 and are the same in every file in this folder, so results can be compared.
- Each check has **Steps**, a **Pass when** line, and sometimes a **Write down** line.
- Write **Pass**, **Fail**, **Skipped** (and why) or **Not offered** (the phone does not have that feature) for each check.
- If a check fails, do not fix it and carry on silently. Write what you saw, then go on to the next check.
- Do check 22 (30 minutes) after the extra checks. Allow about two hours in all, plus the run.

## Checks

### 1. Install the app and open it

**Steps**
1. Install the PhoneCam app from the APK, as in the README's Install section (step 3). Write down the file name.
2. Open PhoneCam. Allow camera access when Android asks.
3. Wait for the first camera check to finish. Look at the camera and quality choices.
4. Open **Settings → What the buttons do** and read the list.

**Pass when:** PhoneCam shows a live picture from the camera on the phone's screen, offers at least one camera and one quality choice, and shows no error message.

**Write down:** the cameras listed, the quality choices, whether a 4K choice ("Best") and a 60 fps choice are offered, and whether the phone's own camera app offers more (for example 4K at 60 fps).

### 2. The plugin and the source in OBS

**Steps**
1. Install the OBS plugin (see "How to install" above). Quit OBS first.
2. Start OBS. In **Sources**, click **+** and choose **PhoneCam (phone camera)**, then **OK**.
3. Right-click the source → **Properties**. Read the **Status** line.
4. Open **Help → Log Files → View Current Log**. Find the lines that start with `[phonecam]`.

**Pass when:** the source can be added; the Status line says it is ready or says what it is waiting for; it does not say "adb not found" or that the plugin failed to load; and the log has `[phonecam]` lines.

### 3. Live over USB

**Steps**
1. Turn on USB debugging on the phone (README Install, step 2).
2. Plug the phone into the Mac with a USB **data** cable. When the phone asks **Allow USB debugging?**, tick **Always allow from this computer** and tap **Allow**.
3. In OBS, make sure the PhoneCam source is visible (eye icon on).
4. On the phone, choose **Rear** and **High**. Tap the round red **record button** and count the seconds until the picture shows in OBS.
5. Wait one minute. Look at the live badge on the phone: frames per second and drops.
6. In OBS, open **Help → Log Files → View Current Log** and find the `[phonecam]` lines about going live. They give the picture size and say "hardware" or "software" decoding.

**Pass when:** the picture shows in OBS within 10 seconds of the tap; the phone shows "● Live" and "USB"; the Status line says "Live over USB"; and the picture is smooth with few or no drops after one minute.

**Write down:** the seconds to the first picture, fps and drops after one minute, and whether the decoding is hardware or software.

### 4. Rotation and fit

**Steps** (live over USB or Wi-Fi)
1. Switch on auto-rotate on the phone (Android quick settings).
2. Hold the phone upright (portrait) and look at OBS.
3. Turn it to one landscape side, wait for the picture, then to the other landscape side.
4. Turn it back to portrait.
5. In OBS, drag the source so it no longer fits the canvas. Open its **Properties** and click **Fit to canvas now**.

**Pass when:** the picture is upright (not sideways, not upside down) in every position, it turns without OBS saying "Live ended", the whole picture fits the canvas, and **Fit to canvas now** works.

### 5. Switch camera, mirror and camera controls

**Steps** (live)
1. Tap the camera icon to switch from rear to front. Wait for the picture. Switch back. Do it three times in all.
2. On the front camera, tap the mirror icon on and off. Look at OBS. (Android always shows the front camera mirrored on the phone's own screen; the mirror icon changes only what OBS gets.)
3. Under the preview, try zoom (slider or pinch), tap on the preview to focus, brightness and, on the rear camera, the torch.
4. Start a recording in OBS first, then repeat step 1 once. Stop the recording and play it back.

**Pass when:** each switch pauses the picture for a few seconds at most (about 5) and it comes back by itself; OBS never says "Live ended"; the recording keeps going with no black frames; the mirror icon flips the picture in OBS; and zoom, focus and torch visibly change the picture in OBS.

**Write down:** any extra cameras the phone lists (for example "Rear 2"), and whether it has a torch.

### 6. Quality choices

**Steps**
1. Stop live if it is running. Choose **High**, tap record, and wait 30 seconds. Write down fps and drops.
2. Start a recording in OBS. While live, tap the quality icon and choose **Balanced**. Wait 30 seconds. Then choose **Best** (if offered), wait 30 seconds, and read the size in the Status line (for example 3840×2160). Then **Smooth 60 fps** if it is offered.
3. Stop the recording and play it back.

**Pass when:** every choice the phone offers goes live and is smooth; each change keeps OBS connected and the recording keeps going with no black frames (a pause of a few seconds is fine); and a choice the phone cannot do says so in plain words and does not quietly use other settings.

**Write down:** for each choice: picture size, fps, drops after 30 seconds and the temperature on the badge.

### 7. Stop

**Steps**
1. While live, tap the record button (now a red square) on the phone.
2. Start live again. In OBS, hide the source (eye icon off). On the phone, tap record: it says it is waiting for OBS. Tap the record button again to stop. Show the source and tap record.

**Pass when:** step 1 ends live within a few seconds: the phone shows its preview again, OBS's Status goes back to ready, and the notification is gone. In step 2, the start after "waiting" works and the picture appears.

### 8. Wi-Fi pairing by QR code

The phone and the Mac must be on the **same Wi-Fi network**. The Internet is not needed.

**Steps**
1. Unplug the cable.
2. In OBS, right-click the PhoneCam source → **Properties** → **Pair a phone over Wi-Fi**. The source shows a QR code and a 16-character code for 3 minutes. Hide the source from any stream or recording while it shows.
   If macOS asks whether OBS may find or connect to devices on your local network, allow it and write it down.
3. On the phone, open **Settings → Pair a computer** and point the camera at the QR code.
4. Wait for the vibration and chime, and the message "Paired with …".
5. Open **Settings → Paired computers**.

**Pass when:** the phone says it is paired with your computer, vibrates and chimes, and the computer is in the **Paired computers** list.

Do not post the QR code or the 16-character code anywhere.

### 9. Wi-Fi pairing by typed code, twice in a row, and expiry

**Steps**
1. On the phone, open **Settings → Paired computers** and **Forget** your computer.
2. In OBS, open **Pair a phone over Wi-Fi** again. On the phone, choose **Type a pairing code** and type the 16 characters.
3. Forget the computer again and pair once more, this time by QR code.
4. In OBS, open **Pair a phone over Wi-Fi** once more and do nothing. Wait 4 minutes.

**Pass when:** the typed code pairs; the second pairing works with no refusal or "can't reach" message; and the QR card disappears from the source by itself after 3 minutes.

### 10. Live over Wi-Fi

**Steps**
1. Keep the phone and the Mac on the same Wi-Fi. Unplug the cable. Make sure the PhoneCam source is visible.
2. Tap record and count the seconds until the picture shows.
3. Read the Status line in OBS and the status at the top of the phone's screen.
4. Run **High** for one minute. Write down fps and drops. While live, switch camera once and quality once.
5. Try **Best** (4K) if it is offered, for one minute. Look for "Link busy" on the badge.

**Pass when:** live starts within 15 seconds; the Status line says "Live over direct Wi-Fi"; the top of the phone's screen shows a Wi-Fi icon and your computer's name; High is smooth with few drops; and the camera and quality switches keep going. For 4K, only write down what you see.

**Write down:** the router or Wi-Fi band if you know it (2.4 or 5 GHz), fps and drops, and any "Link busy" numbers.

### 11. USB is preferred when the cable is in

**Steps**
1. Keep the Wi-Fi pairing from check 8. Plug the cable in. Unlock the phone.
2. Tap record. Read OBS's Status line and the top of the phone's screen.
3. Stop. Unplug the cable. Tap record again.

**Pass when:** with the cable in, live starts within 5 seconds and says USB (a cable icon on the phone, "Live over USB" in OBS); with the cable out, it says Wi-Fi.

### 12. Live keeps going when you leave the app

**Steps** (live)
1. Press Home. Look at OBS. Pull down the notifications on the phone.
2. Open another app for 30 seconds. Come back to PhoneCam.
3. Lock the phone's screen for 30 seconds. Unlock it.
4. Tap **Stop** in the notification.
5. Go live again. Open the recent apps list and swipe PhoneCam away.
6. Open **Settings → While live** and turn **Keep live when you leave the app** off. Go live, then press Home.
7. Turn the setting back on.

**Pass when:**
- step 1: OBS keeps getting the picture, and there is a notification "● PhoneCam is live to OBS" with a timer and **Stop**, and Android's green camera dot shows;
- step 2: the preview and timer are back and OBS stayed connected;
- step 3: live kept going;
- step 4: live ends and the notification is gone;
- step 5: live stops and the notification is gone;
- step 6: live stops when PhoneCam leaves the screen, with a message that says how to turn background live on.

**Write down:** whether Android asked to allow notifications the first time (Android 13 or newer), and whether the phone ever closed PhoneCam by itself while you were away. If it did, say what the app told you afterwards.

### 13. Picture-in-picture

**Steps** (live)
1. Press Home.
2. Tap the small floating window. Look at its buttons.
3. Tap the full-screen button to go back to PhoneCam. Press Home again.
4. Close the small window (drag it away or use its close button).
5. Open **Settings → While live** and turn **Picture-in-picture when you leave** off. Go live and press Home.
6. Turn the setting back on. Go live, press Home, and tap **Stop** in the small window.

**Pass when:**
- step 1: a small window shows the preview and a timer like "● 00:24", and OBS keeps going;
- step 2: it has **Stop** and a full-screen button;
- step 4: live keeps going in the background, with the notification;
- step 5: there is no small window, and live keeps going;
- step 6: **Stop** ends live.

### 14. The Phone section in OBS

**Steps** (live)
1. In OBS, right-click the PhoneCam source → **Properties**.
2. Find the **Phone** section. It shows a line like "Rear camera · USB · battery 80 %", a **Camera** list, a **Quality** list, **Torch** and **Mirror** tick boxes, and **Stop live on the phone**.
3. Change **Camera**. Change **Quality**. Tick **Torch** on and off (rear camera, if the phone has a torch). Tick **Mirror** on and off.
4. Look for anything in OBS that could **start** live. Before you tap record, look at the Properties again.
5. Click **Stop live on the phone**.

**Pass when:** each change reaches the phone and the picture within a few seconds; the line shows the right camera, connection and battery; **Stop live on the phone** ends live and OBS says the phone stopped sending; and OBS has no button that starts live.

### 15. OBS hotkeys

**Steps** (live)
1. In OBS, open **Settings → Hotkeys**. Find the three PhoneCam keys: switch phone camera, torch on/off, and stop live on the phone.
2. Give each one a key that nothing else uses. Click **OK**.
3. Press the camera key, then the torch key (rear camera, if the phone has a torch), then the stop key.

**Pass when:** the camera key switches the phone's camera; the torch key switches the torch; and the stop key ends live.

### 16. Microphone and audio tracks

Needs a quiet room. The microphone must stay **off** unless you turn it on.

**Steps**
1. Before going live, look at the microphone icon on the phone. It should be crossed out (off).
2. Go live with the microphone off. In OBS's **Audio Mixer**, find the PhoneCam source. Speak. The meter should not move. (If OBS says the source "is not assigned to any audio tracks", open **Edit → Advanced Audio Properties**, tick **Tracks 1–6** for the PhoneCam source and unmute it in the Audio Mixer. Write down that you had to.)
3. While live, tap the microphone icon. Android asks for permission: tap **Allow**. The icon turns blue.
4. Speak. The meter in OBS moves. Start a recording in OBS, clap once in front of the camera, say a few words, and stop after 10 seconds. Play the recording back.
5. Tap the microphone icon to turn it off while live. Speak. The meter stops moving and the picture goes on.
6. Stop live. Start live again. Look at the microphone icon before and after.
7. Stop live. In Android's settings for PhoneCam, set the Microphone permission to **Don't allow**. Go back to PhoneCam and tap the microphone icon.
8. Give the permission back.

**Pass when:**
- step 2: no sound in OBS while the microphone is off;
- step 4: you hear yourself in OBS's meter and in the recording, and the clap sounds together with the picture (within about a quarter of a second; if not, say whether the sound is early or late);
- step 5: sound stops, picture goes on;
- step 6: the microphone is off in a new session until you turn it on;
- step 7: PhoneCam refuses and says why. It never goes live with the microphone icon blue and no sound.

### 17. Screen sharing

**Steps**
1. On the phone, tap the screen icon (next to the camera icon). Tap the record button.
2. Android shows a screen-sharing prompt. Read it. On Android 14 or newer it may offer **A single app** or **Entire screen**. Write down what it offers. Choose **Entire screen** if it is offered. Allow.
3. Look at OBS. Turn the phone from portrait to landscape and back.
4. Press Home and open another app for 20 seconds. Look at OBS and at the notification.
5. Tap **Stop** in the notification.
6. Share the screen again. Look for the prompt.

**Pass when:** the prompt appears **every time** you start; the phone's screen shows in OBS; turning the phone resizes the picture without leaving OBS; sharing keeps going in the background with the notification and **Stop**; and **Stop** ends it. The phone's own sound is not sent (that comes later).

**Write down:** what the prompt offered (one app or entire screen) and the picture size.

### 18. Warnings, dimming and the alert when away

**Steps**
1. Open **Settings → While live**. Read the switches for the heat, battery and away alerts and for the dim screen.
2. Go live. Do not touch the phone for 40 seconds. Then touch the screen once.
3. Go live, then open another app on the phone and stay in it. On the Mac, quit OBS (or hide the source).
4. During the 30-minute run (check 22), watch for a heat notification ("Warm" or "Hot"). If one shows, tap **Lower quality**.
5. If you can, let the battery drop below 20 % while live and not charging. Look for a notification.

**Pass when:**
- step 1: each warning has its own switch;
- step 2: the screen dims after about 30 seconds and the first touch only wakes it;
- step 3: within a few seconds a notification says why live ended, with sound and vibration;
- step 4: if it showed, **Lower quality** moves down one step (Best → High → Balanced) and live keeps going;
- step 5: a notification shows at 20, 15, 10 and 5 %.

Steps 4 and 5 may not happen. Then write "Not seen" or "Skipped". Never write Pass for something you did not see.

### 19. Unplug and replug the cable

**Steps** (live over USB)
1. Pull the cable out. Wait 5 seconds.
2. Plug it back in. If the phone asks **Allow USB debugging?**, tap **Allow**.
3. Look at OBS's Status line. Tap record on the phone. Count the seconds until the picture is back.
4. Repeat from step 1 three times in all. Do not restart OBS between them.

**Pass when:** every time, the picture is back within about 15 seconds after you tap record, without restarting OBS or the app; and OBS never freezes.

### 20. Hide and show the source

**Steps**
1. While live, hide the PhoneCam source (eye icon off). Wait 5 seconds.
2. Show it again and tap record on the phone. Count the seconds until the picture is back.
3. Repeat 5 times in all.
4. In the source's **Properties**, tick **Keep the phone live while this source is hidden**. Go live, hide the source for 10 seconds, show it again.

**Pass when:** each hide ends live (the phone says so) and each show plus record brings the picture back within about 5 seconds; OBS never freezes. With the tick box on, the phone keeps live while the source is hidden, and the picture is back by itself when you show it.

### 21. Quit OBS while live

**Steps**
1. Go live. Open another app on the phone and leave PhoneCam in the background.
2. In OBS, quit the program normally. Count the seconds until its window is gone.
3. Look at the phone.
4. Start OBS again. Open the source's Properties.

**Pass when:** OBS closes within 10 seconds, with no "not responding" message; the phone shows a notification that live ended, with the reason; and the new OBS session says it is ready.

### 22. The 30-minute run

Do this one last. Use **High** with the microphone on. If you want a harder test, use **Best** and say so.

**Steps**
1. Write down the phone's battery level and the temperature on the live badge.
2. In OBS, open **View → Docks → Stats** and reset it if it has old numbers. Start a recording.
3. Go live over USB. In front of the camera, clap once and say the time.
4. Leave PhoneCam on screen for 5 minutes. Then press Home and leave the phone alone for 20 minutes. Do not touch OBS.
5. Open PhoneCam again for the last 5 minutes. Clap once more and say the time. Look at the badge.
6. Stop the recording. Read the **Stats** dock. Play the recording back, including the start and the end.
7. Write down the battery level and temperature again.

**Pass when:** live never ended by itself; the phone did not shut down or restart; the recording has no freeze longer than 3 seconds; drops on the badge are under 1 % of the frames (for 1080p at 30 fps that is about 540); the end clap is as close to its picture as the start clap (no drift); and OBS stayed responsive.

**Write down:** battery and temperature at the start and the end, drops on the badge, the **Stats** numbers (missed, skipped and dropped frames), and any warning you saw.

## Extra checks for this setup

These only matter on an Intel Mac. Do them after checks 1 to 21 (S1 first, if you can, because it explains a failure of check 2) and before check 22.

### S1. Check that this is an Intel Mac and the right plugin

**Steps**
1. Click the Apple menu → **About This Mac**. Write down the chip or processor line (it should say Intel), the Mac's model and year, and the macOS version (13 or newer).
2. Check you installed the Intel build of OBS 33 (OBS's download page has separate Intel and Apple Silicon downloads).
3. Look at the plugin zip's name. If it says it is for Apple Silicon only (`arm64`) and not universal, stop and tell the owner: it cannot run on this Mac.
4. Start OBS. Add the **PhoneCam (phone camera)** source (check 2).

**Pass when:** the Mac is Intel, OBS lists **PhoneCam (phone camera)** as a source, and OBS's log does not complain about the plugin's architecture. If the source is missing, paste the log lines about the plugin (any lines with `phonecam` or `plugin`), with names removed.

### S2. Hardware or software decoding

Older Intel Macs may not decode H.265 in hardware.

**Steps**
1. Go live with **High**, then with **Best** (if offered).
2. In the OBS log find the `[phonecam]` lines about going live. Write down whether each says "hardware" or "software" decoding.
3. Watch OBS's CPU use in **Activity Monitor** for one minute with each.

**Pass when:** **High** is smooth and its fps matches the phone's badge within 2 frames per second. For **Best**, only write down what you see: if it is choppy, say so and paste the log lines.

### S3. `adb` on an Intel Mac

**Steps**
1. In Terminal, type `which adb` and press Enter. Write down the answer.
2. If OBS says "adb not found", open the source's Properties and put the full path in the **adb path** field. Homebrew on an Intel Mac usually puts it at `/usr/local/bin/adb`.

**Pass when:** the plugin finds `adb` (with or without the path field). Write down which.

## Optional checks

Do these if you have time.

### O1. Pairing and live over the phone's hotspot

Skip this if the phone cannot make a hotspot.

**Steps**
1. Turn on the phone's hotspot. Join the Mac to it. (Turn mobile data off on the phone if you like.)
2. Pair by QR code as in check 8, then go live as in check 10.

**Pass when:** pairing works and live starts over direct Wi-Fi.

### O2. Wireless debugging (Android 11 or newer)

This is the older developer way to go without a cable. It is different from direct Wi-Fi.

**Steps**
1. Forget the computer in **Settings → Paired computers**, so the phone has to use Wireless debugging.
2. Follow the README section "Without a cable: Wireless debugging (developer option)".
3. Unplug the cable, tap record, and wait up to 10 seconds.

**Pass when:** the Status line says "Live over Wi-Fi" (not "direct Wi-Fi") and the picture shows.

## Report

Paste this into the GitHub issue for your setup (the link is in [COMPATIBILITY.md](../COMPATIBILITY.md)). Fill in every line. If you did not do a check, write **Skipped** and why. Never write **Pass** for something you did not do or see.

```text
Setup: Android + Intel Mac
Phone: brand and model, Android version
Computer: Mac model and year, Intel processor, macOS version
OBS version:
App file you installed (for example PhoneCam-0.7.0-alpha.1.apk):
Plugin file you installed:
Connections you used: USB / direct Wi-Fi / Wireless debugging

Checks (Pass / Fail / Skipped / Not offered), with a short note:
1. Install the app and open it: 
2. The plugin and the source in OBS: 
3. Live over USB: 
4. Rotation and fit: 
5. Switch camera, mirror and camera controls: 
6. Quality choices: 
7. Stop: 
8. Wi-Fi pairing by QR code: 
9. Wi-Fi pairing by typed code, twice in a row, and expiry: 
10. Live over Wi-Fi: 
11. USB is preferred when the cable is in: 
12. Live keeps going when you leave the app: 
13. Picture-in-picture: 
14. The Phone section in OBS: 
15. OBS hotkeys: 
16. Microphone and audio tracks: 
17. Screen sharing: 
18. Warnings, dimming and the alert when away: 
19. Unplug and replug the cable: 
20. Hide and show the source: 
21. Quit OBS while live: 
22. The 30-minute run: 
S1. Check that this is an Intel Mac and the right plugin: 
S2. Hardware or software decoding: 
S3. `adb` on an Intel Mac: 
O1. Pairing and live over the phone's hotspot: 
O2. Wireless debugging (Android 11 or newer): 

Things I noticed:
```

**The OBS log.** In OBS, open **Help → Log Files → View Current Log**. Copy only the lines that contain `[phonecam]`. Before you paste them, clean them:
- Replace every IP address (four numbers with dots, such as 192.168.1.20) with `x.x.x.x`.
- Delete your name, your user name and your computer's or phone's name if they appear.
- Never paste the pairing code, a picture of the QR code, or a link that starts with `phonecam://`.

If a check failed, also say what you saw and what you expected. A screenshot of the phone and of the source's Properties helps; check it for names and addresses first.
