# Android research setup

## Mac prerequisites

The probe targets Android 10/API 29 and later. Build with JDK 17+, Gradle wrapper, Android SDK platform 36 and build tools 35.0.0. AGP is pinned to 8.13.0, Kotlin to 2.3.0, and Gradle to 8.13; their compatibility was checked against the official release tables. The wrapper verifies the distribution SHA-256. No full Android Studio, Rust, or Xcode installation is needed for this milestone.

The initial development host now has command-line Android SDK tools at `$HOME/Library/Android/sdk/cmdline-tools/19.0`, platform 36 and build tools 35.0.0. ADB, scrcpy and FFmpeg were already installed separately. Other Macs can install SDK command-line tools through [Android's official downloads](https://developer.android.com/studio#command-line-tools-only), then:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
"$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" 'platforms;android-36' 'build-tools;35.0.0'
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Adjust the command-line-tools version directory for your installation. Review SDK licenses when prompted. Use a JDK supported by the installed command-line tools. The initial Mac builds with its existing JDK 19; CI uses JDK 17. SDK discovery uses ANDROID_HOME or an ignored local.properties containing `sdk.dir=/absolute/path/to/sdk`.

## Connect a phone

1. Use a data-capable USB cable, directly connected if possible. A charging-only cable cannot communicate.
2. In Android Settings, locate About phone → Software information/Build number and enable Developer options. Vendor menus vary; do not change unrelated options.
3. Enable USB debugging. Unlock the phone and accept this Mac's RSA authorization prompt. Enable debugging only for a trusted computer; disable/revoke it when no longer needed.
4. Run `python3 tools/phonecam.py doctor`. Unauthorized means the phone needs approval; offline may need reconnecting; no device usually means cable/USB connection/debugging is unavailable.
5. If several devices are connected, use `python3 tools/phonecam.py --serial SERIAL doctor`. The serial selects the connection but is not put into benchmark reports.
6. Run `python3 tools/phonecam.py install`. If Android blocks APK installation, approve this specific research APK using the device's documented installation prompt. Never disable general security protections as a blanket fix.
7. Open PhoneCam Probe and grant camera permission. No microphone permission is requested. Scan, choose a profile, turn on auto-rotate, and hold the device in landscape.

No phone was connected during software implementation. No camera-quality, thermal, or latency result is implied by the APK building successfully.

## Record and collect

The app has no live preview in this milestone. Frame the baseline in the stock app, fix the phone on a tripod, then switch apps without changing position. Keep PhoneCam Probe visible. A 15-second capture starts only after the Record tap and encoder startup; 30/60 seconds are also available. Stop, backgrounding, rotation/recreation, or locking ends the current capture. Interrupted clips cannot pass the timed quality gate. This is intentionally not screen-off/background streaming.

```sh
python3 tools/phonecam.py collect
```

Debug `run-as` copies the app's private files in a streaming tar operation; no whole recording is buffered in host RAM. There is an 8 GiB collection budget. Collection directories are uniquely named, existing files are not overwritten, and links/path traversal are rejected. Collect only while the app is idle. If a transfer fails, its collection manifest remains incomplete; reconnect and collect into a new directory.

Phone files remain until Delete benchmark files or uninstall. To uninstall just this app:

```sh
adb uninstall dev.phonecam.obs
```

Host copies and Android build tooling remain separate; remove the explicitly chosen benchmark folders when no longer needed.

Debug APKs built on different machines may use different signing keys. If installation reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, collect existing clips before choosing to uninstall the older build; uninstall deletes its private research evidence. The install tool never uninstalls automatically.

## Common capture failures

- **No declared profile:** camera/encoder combination may lack surface input, the tested sizes, or 30 fps. Capability report contains the detected limitations. Do not label this phone verified.
- **Camera busy/permission denied:** close the stock camera and other camera apps; grant Camera permission in Settings and scan again.
- **4K rejected:** advertised sizes do not guarantee the full session works. Try declared 1080p; 720p/variable-range profiles require limited-mode opt-in. A 1080p success does not qualify 4K.
- **HIGH_QUALITY unavailable or slow:** test FAST. High-quality processing can reduce capture rate. Actual settings appear in each sidecar; unsupported requests are not silently substituted.
- **No video before deadline/encoder error:** try the baseline H.264 profile and inspect the report. Retained partial clips cannot qualify.
- **Dark or clipped colors:** confirm normal SDR on both stock/probe baselines; HDR comparisons require a separate procedure.

Primary setup guidance: https://developer.android.com/tools/adb
