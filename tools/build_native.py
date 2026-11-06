#!/usr/bin/env python3
"""Build against pinned installed OBS 33.0.x with Apple command-line tools; no full Xcode/CMake."""
from pathlib import Path
import argparse
import hashlib
import platform
import plistlib
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "native/build"
OBS_ARCHIVES = {
    "33.0.0": "55c6767b209a00f1859b315bf977df907b19890d132723731e00fd589f6860c0",
    "33.0.1": "9174ee0e3677c92d0d060bdce4c5d44dfee9fd3afddfb6333f098fd029b8de2b",
}


def run(command):
    subprocess.run([str(x) for x in command], check=True, cwd=ROOT)


def dependencies(version):
    archives = (
        (f"obs-{version}.tar.gz", f"https://codeload.github.com/obsproject/obs-studio/tar.gz/refs/tags/{version}",
         OBS_ARCHIVES[version], f"obs-sdk-{version}", ("libobs/",)),
        ("simde-0.8.2.tar.gz", "https://codeload.github.com/simd-everywhere/simde/tar.gz/refs/tags/v0.8.2",
         "ed2a3268658f2f2a9b5367628a85ccd4cf9516460ed8604eed369653d49b25fb", "simde", ("simde/", "COPYING")),
    )
    BUILD.mkdir(parents=True, exist_ok=True)
    for name, url, expected, directory, prefixes in archives:
        archive = BUILD / name
        if not archive.exists():
            temporary = archive.with_suffix(".download")
            urllib.request.urlretrieve(url, temporary)
            temporary.replace(archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
            raise RuntimeError(f"Dependency checksum mismatch: {name}")
        with tarfile.open(archive) as bundle:
            for member in bundle:
                parts = Path(member.name).parts
                if len(parts) < 2 or ".." in parts or Path(member.name).is_absolute() or not member.isfile():
                    continue
                relative = Path(*parts[1:])
                if not str(relative).startswith(prefixes):
                    continue
                target = BUILD / directory / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(bundle.extractfile(member).read())
    sdk = BUILD / f"obs-sdk-{version}/libobs"
    (sdk / "obsconfig.h").write_text('#pragma once\n#define OBS_RELEASE_CANDIDATE 0\n#define OBS_BETA 0\n#define OBS_INSTALL_PREFIX ""\n#define OBS_DATA_PATH "data"\n')
    return sdk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install", action="store_true", help="Install only this plugin in the current user's OBS plugins folder")
    args = parser.parse_args()
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        parser.error("This research build currently supports Apple Silicon macOS only")
    obs = Path("/Applications/OBS.app/Contents")
    version = plistlib.loads((obs / "Info.plist").read_bytes())["CFBundleShortVersionString"]
    if version not in OBS_ARCHIVES:
        parser.error(f"Pinned OBS headers support {', '.join(OBS_ARCHIVES)}; installed: {version}")
    sdk = dependencies(version)
    frameworks = obs / "Frameworks"
    common = ["xcrun", "clang++", "-std=c++17", "-O2", "-g", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
              "-mmacosx-version-min=13.0", "-arch", "arm64", "-fobjc-arc", "-I", ROOT / "native"]
    headers = ["-I", sdk, "-I", BUILD / "simde"]
    apple = ["-framework", "Foundation", "-framework", "VideoToolbox", "-framework", "CoreMedia", "-framework", "CoreVideo"]
    libobs = ["-F", frameworks, "-framework", "libobs", "-Wl,-rpath," + str(frameworks)]
    bundle = BUILD / "phonecam-usb.plugin"
    executable = bundle / "Contents/MacOS/phonecam-usb"
    executable.parent.mkdir(parents=True, exist_ok=True)
    (bundle / "Contents/Resources").mkdir(exist_ok=True)
    shutil.copyfile(ROOT / "LICENSE", bundle / "Contents/Resources/COPYING-GPL-2.0.txt")
    shutil.copyfile(ROOT / "NOTICE", bundle / "Contents/Resources/NOTICE.txt")
    shutil.copyfile(BUILD / "simde/COPYING", bundle / "Contents/Resources/COPYING-SIMDe.txt")
    run(common + headers + ["-bundle", ROOT / "native/plugin.mm", ROOT / "native/receiver.mm", "-o", executable] + apple + libobs)
    info = {"CFBundleExecutable": "phonecam-usb", "CFBundleIdentifier": "dev.phonecam.obs.usb",
            "CFBundleName": "phonecam-usb", "CFBundlePackageType": "BNDL", "CFBundleShortVersionString": "0.2.0",
            "CFBundleVersion": "2", "LSMinimumSystemVersion": "13.0"}
    (bundle / "Contents/Info.plist").write_bytes(plistlib.dumps(info))
    run(["codesign", "--force", "--sign", "-", bundle])
    run(common + [ROOT / "native/receiver_cli.mm", ROOT / "native/receiver.mm", "-o", BUILD / "phonecam-receive"] + apple)
    run(common + headers + [ROOT / "native/smoke.mm", "-o", BUILD / "obs-smoke"] + libobs)
    run(["xcrun", "clang++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-fsanitize=address,undefined",
         ROOT / "native/protocol_test.cpp", "-o", BUILD / "protocol-test"])
    run([BUILD / "protocol-test"])
    run([BUILD / "obs-smoke", executable])
    if args.install:
        target = Path.home() / "Library/Application Support/obs-studio/plugins/phonecam-usb.plugin"
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            info_file = target / "Contents/Info.plist"
            if not info_file.is_file() or plistlib.loads(info_file.read_bytes()).get("CFBundleIdentifier") != info["CFBundleIdentifier"]:
                raise RuntimeError("Refusing to overwrite an unrelated existing plugin")
            shutil.rmtree(target)
        shutil.copytree(bundle, target)
        print(f"Installed: {target}. Open/restart OBS to discover the source.")
    print("Native build + protocol + OBS module-load checks passed. Physical quality/latency remain unverified.")


if __name__ == "__main__":
    main()
