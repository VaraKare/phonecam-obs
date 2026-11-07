# PhoneCam OBS

Research toward using an Android phone as a native camera source in OBS on a Mac, over USB. **The research build includes a capability scanner, recording probe, and experimental native OBS USB source.** No device is verified yet.

The first question is whether an ordinary Android app can approach the stock camera's normal video quality. On 6 November 2026 the user elected to begin USB-to-OBS research before completing the controlled quality comparison. This changes the research sequence, not the acceptance criteria: a public product still requires both targets at the same settings.

## Build

Requires JDK 17+, Android SDK platform 36 and build tools 35.0.0. Android Studio is optional. Gradle is downloaded by the checked-in wrapper and verified by SHA-256.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 -m unittest discover -s tools/tests
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. It is a debug-signed research build. CI builds it; public signing and installers are later milestones.

## Use the probe

1. Follow [Android setup](docs/ANDROID_SETUP.md). USB debugging is needed only for installing and collecting research files; recording itself runs locally on the phone.
2. Run `python3 tools/phonecam.py doctor`, then `python3 tools/phonecam.py install`.
3. Open **PhoneCam Probe**, grant camera access, and scan capabilities.
4. Frame the stock-camera baseline on a tripod. The probe uses encoder-only capture without a preview to keep the research stream combination simple; keep the phone in position.
5. Select a declared profile and processing mode. Press **Record benchmark** to make an explicit, timed, video-only recording. Stop/background/lock the app to stop capture.
6. Run `python3 tools/phonecam.py collect` to copy reports and clips to `benchmarks/raw/` on this Mac.
7. Follow [the benchmark procedure](docs/BENCHMARKS.md) for stock/scrcpy comparisons, blind scoring, and the quality gate.

Recordings live in the app's private `files/benchmarks` directory until **Delete benchmark files** or uninstall. Collection copies files; it does not delete them. The app displays paths and deletion results. There is no background recording, microphone capture, or network permission.

## Experimental USB live mode

Follow [USB live setup](docs/USB_LIVE.md). Native code currently targets Apple Silicon and OBS 33.0.0 or 33.0.1, using Apple command-line tools. Build/install the plugin with `python3 tools/build_native.py --install`; prepare forwarding with `python3 tools/live.py prepare`. Add **PhoneCam USB (Research)** to an OBS scene, then tap **Start USB live** in the foreground phone app. Live mode saves no media and uses no Internet permission.

## Status and support

Android 10+ is the API minimum, not a universal compatibility guarantee. Profiles are **compatible, unverified** until physically tested, **limited** only with explicit opt-in, or **unsupported** with a reason. A verified label requires the complete quality, optical-latency, and stability evidence; this probe does not assign it.

Reference Mac during development: Apple M1, macOS 26.6.2, OBS 33.0.1 (initially 33.0.0). The reference Vivo identifies itself as V2437, Android 16. Short rear 1080p30 AVC/HEVC recordings succeeded, including applied HIGH_QUALITY processing; controlled quality, live optical latency, and sustained reliability remain unverified.

See [roadmap](docs/ROADMAP.md), [plan](docs/PLAN.md), [phase tracker](docs/PHASES.md), [branch workflow](CONTRIBUTING.md), and [data handling](docs/DATA.md).

Original source: GPL-2.0-or-later; see LICENSE and NOTICE for third-party tooling notices.
