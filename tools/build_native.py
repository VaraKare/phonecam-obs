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
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "native/build"
PLUGIN_VERSION = "0.7.0-alpha.1"
OBS_ARCHIVES = {
    "33.0.0": "55c6767b209a00f1859b315bf977df907b19890d132723731e00fd589f6860c0",
    "33.0.1": "9174ee0e3677c92d0d060bdce4c5d44dfee9fd3afddfb6333f098fd029b8de2b",
}
BUNDLE_ID = "dev.phonecam.obs.usb"
# Official Intel OBS for macOS (SHA-256 as published on the GitHub release), for the --universal x86_64 slice.
INTEL_OBS_VERSION = "33.0.1"
INTEL_OBS = ("OBS-Studio-33.0.1-macOS-Intel.dmg",
             "https://github.com/obsproject/obs-studio/releases/download/33.0.1/OBS-Studio-33.0.1-macOS-Intel.dmg",
             "745257d65e06eb0f784af229659223a863ad50d036464bb2b52f8a2d9278c2b1")


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
                if not relative.as_posix().startswith(prefixes):  # forward slashes on Windows too
                    continue
                target = BUILD / directory / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(bundle.extractfile(member).read())
    sdk = BUILD / f"obs-sdk-{version}/libobs"
    (sdk / "obsconfig.h").write_text('#pragma once\n#define OBS_RELEASE_CANDIDATE 0\n#define OBS_BETA 0\n#define OBS_INSTALL_PREFIX ""\n#define OBS_DATA_PATH "data"\n')
    return sdk


def fetch(name, url, expected):
    """A pinned download in native/build, checked by SHA-256 (streamed: the OBS disk images are ~200 MB)."""
    archive = BUILD / name
    if not archive.exists():
        BUILD.mkdir(parents=True, exist_ok=True)
        temporary = archive.with_suffix(".download")
        urllib.request.urlretrieve(url, temporary)
        temporary.replace(archive)
    digest = hashlib.sha256()
    with open(archive, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    if digest.hexdigest() != expected:
        archive.unlink()
        raise RuntimeError(f"Dependency checksum mismatch: {name}")
    return archive


def intel_frameworks():
    """libobs and its frameworks from the official Intel OBS 33.0.1 for macOS, for the x86_64 slice."""
    target = BUILD / f"obs-intel-{INTEL_OBS_VERSION}/Frameworks"
    if not (target / "libobs.framework").is_dir():
        dmg = fetch(*INTEL_OBS)
        partial = target.with_name("Frameworks.partial")
        shutil.rmtree(partial, ignore_errors=True)
        with tempfile.TemporaryDirectory() as mount:
            run(["hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount, dmg])
            try:
                run(["ditto", Path(mount) / "OBS.app/Contents/Frameworks", partial])
            finally:
                run(["hdiutil", "detach", mount])
        partial.replace(target)
    archs = subprocess.run(["lipo", "-archs", str(target / "libobs.framework/libobs")], capture_output=True, text=True, check=True).stdout.split()
    if "x86_64" not in archs:
        raise RuntimeError(f"Intel OBS libobs has no x86_64 slice: {archs}")
    return target


def rosetta():
    return subprocess.run(["arch", "-x86_64", "/usr/bin/true"], capture_output=True).returncode == 0


def verify_signature(bundle):
    """The signature seals every file in the bundle; a missing or extra file fails here, not on someone's Mac."""
    run(["codesign", "--verify", "--strict", "--verbose=2", bundle])


def make_bundle(bundle, binary, info):
    """The plugin bundle from a linked binary. Built from scratch each time so nothing stale (an old dSYM from
    earlier builds put it in Contents/MacOS) is sealed into the signature and then left out of the zip."""
    shutil.rmtree(bundle, ignore_errors=True)
    executable = bundle / "Contents/MacOS/phonecam-usb"
    executable.parent.mkdir(parents=True)
    (bundle / "Contents/Resources").mkdir()
    shutil.copyfile(ROOT / "LICENSE", bundle / "Contents/Resources/COPYING-GPL-2.0.txt")
    shutil.copyfile(ROOT / "NOTICE", bundle / "Contents/Resources/NOTICE.txt")
    shutil.copyfile(BUILD / "simde/COPYING", bundle / "Contents/Resources/COPYING-SIMDe.txt")
    shutil.copyfile(binary, executable)
    executable.chmod(0o755)
    (bundle / "Contents/Info.plist").write_bytes(plistlib.dumps(info))
    # Debug symbols name the build folder (the builder's home path) and temporary object files: never ship them
    # (audit/FINDINGS.md AUDIT-015). The dSYM stays next to the linked binary in native/build, outside the bundle;
    # the copy is stripped before signing, so the signature covers the final binary and every file shipped.
    run(["xcrun", "strip", "-S", executable])
    if b"/Users/" in executable.read_bytes(): raise RuntimeError("Build paths left in the plugin binary")
    if any(path.suffix == ".dSYM" for path in bundle.rglob("*")): raise RuntimeError("Debug symbols inside the plugin bundle")
    run(["codesign", "--force", "--sign", "-", bundle])
    verify_signature(bundle)
    return executable


def universal(compiler, sources, apple, info, frameworks):
    """Apple Silicon + Intel plugin: an x86_64 slice against the Intel OBS libobs, joined with lipo. Each slice is
    loaded by OBS's own libobs of that architecture (Intel under Rosetta when this Mac has it)."""
    intel = intel_frameworks()
    sdk = dependencies(INTEL_OBS_VERSION)
    headers = ["-I", sdk, "-I", BUILD / "simde"]
    folder = BUILD / "universal"
    folder.mkdir(exist_ok=True)
    # The x86_64 slice links against the downloaded Intel libobs but, like the arm64 one, looks for it in
    # /Applications/OBS.app at run time: the build folder must not end up in the shipped binary.
    x86 = folder / "phonecam-usb-x86_64"
    run(compiler("x86_64") + headers + ["-bundle"] + sources + ["-o", x86] + apple +
        ["-F", intel, "-framework", "libobs", "-Wl,-rpath," + str(frameworks)])
    joined = folder / "phonecam-usb"
    run(["lipo", "-create", BUILD / "phonecam-usb", x86, "-output", joined])
    executable = make_bundle(folder / "phonecam-usb.plugin", joined, info)
    archs = subprocess.run(["lipo", "-archs", str(executable)], capture_output=True, text=True, check=True).stdout.split()
    if sorted(archs) != ["arm64", "x86_64"]: raise RuntimeError(f"Universal plugin architectures: {archs}")
    run([BUILD / "obs-smoke", executable])  # the arm64 slice, in the Apple Silicon OBS's libobs
    if rosetta():
        smoke = folder / "obs-smoke-x86_64"
        run(compiler("x86_64") + headers + [ROOT / "native/smoke.mm", "-o", smoke, "-F", intel, "-framework", "libobs", "-Wl,-rpath," + str(intel)])
        run(["arch", "-x86_64", smoke, executable])  # the x86_64 slice, in the Intel OBS's libobs under Rosetta
    else:
        print("SKIP: x86_64 slice load test (Rosetta is not installed); only a link check covers it")
    print(f"Universal test build: {folder / 'phonecam-usb.plugin'} ({' + '.join(archs)}). Not tested on an Intel Mac.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install", action="store_true", help="Install only this plugin in the current user's OBS plugins folder")
    parser.add_argument("--universal", action="store_true",
                        help=f"Also build native/build/universal/phonecam-usb.plugin (Apple Silicon + Intel, x86_64 slice against the "
                             f"Intel OBS {INTEL_OBS_VERSION} libobs). Test build: not part of a release until tested on an Intel Mac")
    args = parser.parse_args()
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        parser.error("This research build currently supports Apple Silicon macOS only")
    obs = Path("/Applications/OBS.app/Contents")
    version = plistlib.loads((obs / "Info.plist").read_bytes())["CFBundleShortVersionString"]
    if version not in OBS_ARCHIVES:
        parser.error(f"Pinned OBS headers support {', '.join(OBS_ARCHIVES)}; installed: {version}")
    sdk = dependencies(version)
    frameworks = obs / "Frameworks"

    def compiler(arch):
        return ["xcrun", "clang++", "-std=c++17", "-O2", "-g", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
                "-mmacosx-version-min=13.0", "-arch", arch, "-fobjc-arc", "-I", ROOT / "native"]
    common = compiler("arm64")
    headers = ["-I", sdk, "-I", BUILD / "simde"]
    apple = ["-framework", "Foundation", "-framework", "VideoToolbox", "-framework", "CoreMedia", "-framework", "CoreVideo",
             "-framework", "Security", "-framework", "CoreImage", "-framework", "CoreText", "-framework", "CoreGraphics"]
    # The plugin finds libobs where OBS installs it on every Mac; the build folder never goes into the binary.
    libobs = ["-F", frameworks, "-framework", "libobs", "-Wl,-rpath," + str(frameworks)]
    sources = [ROOT / "native/plugin.mm", ROOT / "native/receiver.mm", ROOT / "native/adb.mm", ROOT / "native/adb_session.cpp",
               ROOT / "native/wifi.mm", ROOT / "native/wifi_server.cpp", ROOT / "native/pairing_card.mm"]
    info = {"CFBundleExecutable": "phonecam-usb", "CFBundleIdentifier": BUNDLE_ID,
            "CFBundleName": "phonecam-usb", "CFBundlePackageType": "BNDL", "CFBundleShortVersionString": PLUGIN_VERSION,
            "CFBundleVersion": "3", "LSMinimumSystemVersion": "13.0"}
    # Linked outside the bundle: clang writes native/build/phonecam-usb.dSYM next to it, never into the bundle.
    linked = BUILD / "phonecam-usb"
    run(common + headers + ["-bundle"] + sources + ["-o", linked] + apple + libobs)
    bundle = BUILD / "phonecam-usb.plugin"
    executable = make_bundle(bundle, linked, info)
    run(common + [ROOT / "native/receiver_cli.mm", ROOT / "native/receiver.mm", ROOT / "native/adb.mm", ROOT / "native/adb_session.cpp", ROOT / "native/wifi.mm", ROOT / "native/wifi_server.cpp",
                  ROOT / "native/pairing_card.mm", "-o", BUILD / "phonecam-receive"] + apple)
    run(common + headers + [ROOT / "native/smoke.mm", "-o", BUILD / "obs-smoke"] + libobs)
    run(["xcrun", "clang++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-fsanitize=address,undefined",
         ROOT / "native/protocol_test.cpp", "-o", BUILD / "protocol-test"])
    run([BUILD / "protocol-test"])
    run(["xcrun", "clang++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-fsanitize=address,undefined",
         "-I", ROOT / "native", ROOT / "native/adb_test.cpp", "-o", BUILD / "adb-test"])
    run([BUILD / "adb-test"])
    run(["xcrun", "clang++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-fsanitize=address,undefined",
         "-I", ROOT / "native", ROOT / "native/rotate_test.cpp", "-o", BUILD / "rotate-test"])
    run([BUILD / "rotate-test"])
    run(["xcrun", "clang++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-fsanitize=address,undefined",
         "-I", ROOT / "native", ROOT / "native/wifi_test.cpp", "-o", BUILD / "wifi-test", "-framework", "Security", "-framework", "CoreFoundation"])
    run([BUILD / "wifi-test"])
    run([BUILD / "obs-smoke", executable])
    if args.universal:
        universal(compiler, sources, apple, info, frameworks)
    if args.install:
        target = Path.home() / "Library/Application Support/obs-studio/plugins/phonecam-usb.plugin"
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            info_file = target / "Contents/Info.plist"
            if not info_file.is_file() or plistlib.loads(info_file.read_bytes()).get("CFBundleIdentifier") != BUNDLE_ID:
                raise RuntimeError("Refusing to overwrite an unrelated existing plugin")
            shutil.rmtree(target)
        shutil.copytree(bundle, target)
        print(f"Installed: {target}. Open/restart OBS to discover the source.")
    print("Native build + protocol + OBS module-load checks passed. Physical quality/latency remain unverified.")


if __name__ == "__main__":
    main()
