# Background live on the vivo V2437 (21 November 2026)

App 0.5.0-alpha.1 (`dist/0.5.0-alpha.1/`, built from bdd205b), Mac OBS 33.0.1 with the 0.4.1-alpha.1 plugin. The owner ran each check on the phone and reported the result. The phone did not keep the app's own log lines, so there are no timings.

| Check | Result |
|---|---|
| 1. Live, then Home screen | **Pass.** OBS kept the picture. The notification showed "● PhoneCam is live to OBS · Wi-Fi" with Stop, and Android's camera dot showed. Wi-Fi won the race because the phone is also paired. |
| 2. Wi-Fi off, USB only, then Stop in the notification | **Pass.** The notification said "· USB". Stop ended live in OBS and cleared the notification and the dot. |
| 3. Screen locked about 30 s, then unlocked | **Pass.** The picture kept moving in OBS. The phone preview came back on unlock and the live timer kept counting. |
| 4. PhoneCam swiped away from recent apps | **Pass.** Live stopped in OBS; the notification and the dot went away. |
| 5. Settings → Keep live when you leave the app turned off, then Home | **Pass.** Live stopped, no notification, and the screen explained how to turn it back on. |

Not checked: the "phone closed PhoneCam in the background" message (vivo did not close it during these runs), Android 10/11 back-key handling, a long background session, battery and heat over time, and phones other than the vivo V2437.
