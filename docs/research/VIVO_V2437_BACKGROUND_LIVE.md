# Background live on the vivo V2437 (21 November 2026)

App 0.5.0-alpha.1 (`dist/0.5.0-alpha.1/`, built from bdd205b), Mac OBS 33.0.1 with the 0.4.1-alpha.1 plugin. The owner ran each check on the phone and reported the result. The phone did not keep the app's own log lines, so there are no timings.

| Check | Result |
|---|---|
| 1. Live, then Home screen | **Pass.** OBS kept the picture. The notification showed "● PhoneCam is live to OBS · Wi-Fi" with Stop, and Android's camera dot showed. Wi-Fi won the race because the phone is also paired. |
| 2. Wi-Fi off, USB only, then Stop in the notification | **Pass.** The notification said "· USB". Stop ended live in OBS and cleared the notification and the dot. |
| 3. Screen locked about 30 s, then unlocked | **Pass.** The picture kept moving in OBS. The phone preview came back on unlock and the live timer kept counting. |
| 4. PhoneCam swiped away from recent apps | **Pass.** Live stopped in OBS; the notification and the dot went away. |
| 5. Settings → Keep live when you leave the app turned off, then Home | **Pass.** Live stopped, no notification, and the screen explained how to turn it back on. |

## Picture-in-picture and screen changes (same day, app from 179be6b)

| Check | Result |
|---|---|
| 6. Live, then Home | **Fail, then pass.** First build: no window, because the app told Android "not live" (it updated the window settings before recording that live had started). Fixed in f464a04; then the small window showed the preview and OBS kept going. |
| 7. Small window badge | First build: the full "● Live 00:24 · Wi-Fi" badge covered much of the window. Now "● 00:24" in small text (6422212). Owner: good. |
| 8. Stop in the small window | **Pass.** Live ended. |
| 9. Closing the small window | **Pass.** Live kept going in the background with the notification. |
| 10. Record button (no text) and Settings during live | **Pass.** Owner: all good. |

Not checked: the "phone closed PhoneCam in the background" message (vivo did not close it during these runs), Android 10/11 back-key handling, a long background session, battery and heat over time, and phones other than the vivo V2437.
