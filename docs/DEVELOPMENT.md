# Building from source

## Requirements

- JDK 17+, Android SDK platform 36 and build tools 35.0.0 (Android Studio optional). Gradle is downloaded by the checked-in wrapper and verified by SHA-256. See [Android setup](ANDROID_SETUP.md).
- For the OBS plugin: Apple Silicon Mac, Apple command-line tools, OBS 33.0.0 or 33.0.1 in `/Applications`. Pinned, checksummed OBS and SIMDe headers are fetched into `native/build/` (ignored by Git). No Xcode or CMake.
- Python 3.10+, `adb` and `ffmpeg` for the tools and tests.

## Build and test

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 -m unittest discover -s tools/tests
python3 tools/build_native.py --install   # builds, tests and installs the plugin for this user
python3 tools/test_native.py              # synthetic VideoToolbox, rotation and auto-connect tests
python3 tools/phonecam.py install         # installs the debug app on the connected phone
```

Debug and release apps are signed with different keys. Switching between them on a phone requires uninstalling first, which deletes the app's local data (including research clips; collect them first).

## Diagnostics without OBS

```sh
python3 tools/live.py receive --auto --frames 300
```

Then tap **Start live** on the phone. The receiver finds, pairs and forwards automatically like the OBS source, and prints aggregate decode metadata. It saves no media.

## Release

```sh
python3 tools/release.py --create-key   # once; back up ~/.phonecam-obs
python3 tools/release.py                # dist/<version>/: signed APK, plugin zip, SHA256SUMS.txt
```

The app `versionName` and `PLUGIN_VERSION` in `tools/build_native.py` must match. The script publishes nothing. Test the exact files on a real phone and OBS, then create the GitHub Release with the tag `v<version>` and attach the three files.

## Research tools

The phone app's **Settings → Research tools** screen records timed benchmark clips for the camera-quality comparison. `tools/phonecam.py collect` copies them to `benchmarks/raw/` (debug builds only, ignored by Git). See [benchmarks](BENCHMARKS.md) and the [plan](PLAN.md) for the quality and latency gates.
