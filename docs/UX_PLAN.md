# UI and UX plan (phone app and OBS source)

Notes from the 17 November 2026 Windows laptop session. Nothing here is built yet unless it says so. Order within each list is rough priority.

## Phone app

1. **Friendly connection errors.** The phone showed the raw Java text when the computer refused the connection ("failed to connect to /…:47183 … ECONNREFUSED (Connection refused)"). Map the common network errors to a sentence and an action. Example: "The computer refused the connection. In OBS, click Pair a phone over Wi-Fi again." Never show addresses or ports unless the user opens details.
2. **QR scanner times out.** Pairing codes expire after 3 minutes. A scanner left open (app in the background, then reopened 5 minutes later) should close itself and say "That code has expired; show a new one in OBS". The camera is already released while the app is in the background; only the stale screen is wrong.
3. **Switch camera or quality during live.** Owner request from 16 Nov. Today the controls are locked while live. Tapping them should stop and restart the session automatically, with a short "Switching…" state. OBS keeps the source; the picture returns in a second or two.
4. **One clear status line.** Show where the video goes ("Live to OBS on <computer> over USB / Wi-Fi") and the next step when waiting. Today this is split between the badge, the stats chip and messages.
5. **Heat chip.** Fixed in code (66e4976): it now follows Android's thermal status after Stop. The phone still runs the older build, so check it after the next install. Later: when the phone stays "Hot" for a minute, suggest a lower quality.
6. **Battery and charging hint** next to the heat chip during long sessions.
7. **Settings: one "Paired computers" row** (owner, 18 Nov). Today each pairing is listed one after the other in Settings. Instead, show one row with the count ("Paired computers · 2"). Tapping it opens a list; tapping a computer offers "Forget" (with confirmation) or Cancel. Also in Settings: the app version and a link to the help page.
8. **Less cluttered main screen** (owner, 18 Nov: "still feels clustered"). Group the controls (camera, quality, extras) and hide rarely used ones behind Settings. To design together with item 4.

## OBS source (Properties)

1. **Sections.** Order top to bottom:
   - **Status:** one line, plus phone name, resolution, fps, decoder and dropped frames.
   - **Connection:** USB and Wi-Fi, with Pair and Forget.
   - **Picture:** fit to canvas, keep live while hidden (66e4976).
   - **Advanced:** adb path and Wireless-debugging pairing.

   Direct Wi-Fi is the main wireless path, so the adb pairing moves under Advanced.
2. **Pairing card.** Show a countdown ("2:41 left") and a short "Paired with V2437" confirmation picture before the card disappears. The card already hides itself when the window expires (7871251).
3. **Forget one phone,** not only all of them.
4. **Copy diagnostics** button. It copies the recent `[phonecam]` log lines without addresses or user names, for bug reports.
5. **Clear wording for states that look like errors.** For example: "Live ended: the phone stopped sending" when the user tapped Stop should read "Stopped on the phone".
6. **4K warning.** When the computer falls behind at 4K, say so in the status line and suggest 1080p, until the GPU path (AUDIT-009 C) exists.

## Info (ⓘ) help, only where a setting needs it

The owner asked for it on 17 Nov: an ⓘ next to settings whose purpose isn't obvious, opening a short explanation of what it does and when to use it. Not on self-explanatory controls.

**OBS:** `obs_property_set_long_description` adds the help text. On the Mac (OBS 33.0.1) it appeared as text in the Properties, with no ⓘ icon (owner, 18 Nov). Check how it shows on Windows, and keep the texts short. Candidates:
- "Keep the phone live while this source is hidden": heat and battery cost, and when it helps (switching scenes often).
- "Allow paired phones over Wi-Fi": what listens on the network, and that turning it off closes the port.
- "Fit the picture to the canvas automatically".
- The adb path, and Wireless debugging versus direct Wi-Fi.

**Phone:** a small ⓘ that opens a bottom sheet. Candidates:
- quality presets (resolution, fps, bitrate, and which needs USB);
- "Mirror in OBS";
- the HQ / processing option;
- "Show preview" (heat and battery);
- the Wi-Fi versus USB choice;
- the heat chip (what Warm and Hot mean).

## Release notes material

User-visible changes since v0.3.0-alpha.5:
- keep live while hidden;
- faster hide;
- Wi-Fi pairing fixed on Windows;
- heat chip fix;
- first-start drop fix.
