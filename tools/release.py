#!/usr/bin/env python3
"""Build release files into dist/<version>/: signed Android app, macOS OBS plugin zip and .pkg, checksums.

Publishing is a separate, manual step after the build has been tested on a real phone and OBS.
  python3 tools/release.py --create-key   # once; back up ~/.phonecam-obs afterwards
  python3 tools/release.py                # build dist/<version>/
"""
from pathlib import Path
import argparse
import hashlib
import os
import re
import secrets
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import macos_package  # noqa: E402

KEY_DIR = Path.home() / ".phonecam-obs"
PROPERTIES = Path(os.environ.get("PHONECAM_SIGNING", KEY_DIR / "keystore.properties"))


def run(command, **kwargs):
    subprocess.run([str(x) for x in command], check=True, cwd=ROOT, **kwargs)


def version():
    text = (ROOT / "app/build.gradle.kts").read_text()
    match = re.search(r'versionName = "([^"]+)"', text)
    if not match:
        raise SystemExit("versionName not found in app/build.gradle.kts")
    plugin = re.search(r'PLUGIN_VERSION = "([^"]+)"', (ROOT / "tools/build_native.py").read_text())
    if not plugin or plugin.group(1) != match.group(1):
        raise SystemExit("App versionName and PLUGIN_VERSION in tools/build_native.py must match")
    return match.group(1)


def create_key():
    store = KEY_DIR / "release.jks"
    if PROPERTIES.exists() or store.exists():
        raise SystemExit(f"A signing key already exists in {KEY_DIR}. Refusing to replace it: apps signed with a new key cannot update existing installs.")
    KEY_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    password = secrets.token_urlsafe(24)
    run(["keytool", "-genkeypair", "-keystore", store, "-storetype", "PKCS12", "-alias", "phonecam",
         "-keyalg", "RSA", "-keysize", "4096", "-validity", "10000", "-storepass", password, "-keypass", password,
         "-dname", "CN=PhoneCam OBS"])
    store.chmod(0o600)
    descriptor = os.open(PROPERTIES, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        stream.write(f"storeFile={store}\nstorePassword={password}\nkeyAlias=phonecam\nkeyPassword={password}\n")
    print(f"Created {store} and {PROPERTIES} (private, mode 600).")
    print("BACK UP this folder somewhere safe (e.g. an encrypted backup). Without it you cannot publish updates.")


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build():
    if not PROPERTIES.is_file():
        raise SystemExit("No release signing key. Run: python3 tools/release.py --create-key")
    name = version()
    status = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    if status:
        print("Warning: uncommitted changes are included in this build.")
    out = ROOT / "dist" / name
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    env = dict(os.environ, PHONECAM_SIGNING=str(PROPERTIES))
    run(["./gradlew", "--no-daemon", "testReleaseUnitTest", "lintRelease", "assembleRelease"], env=env)
    apk = ROOT / "app/build/outputs/apk/release/app-release.apk"
    if not apk.is_file():
        raise SystemExit("Release APK missing; check the signing properties")
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    apksigner = sdk / "build-tools/35.0.0/apksigner"
    certificate = subprocess.run([str(apksigner), "verify", "--print-certs", str(apk)], capture_output=True, text=True, check=True).stdout
    if "Android Debug" in certificate:
        raise SystemExit("Release APK is debug-signed; refusing to package it")
    shutil.copyfile(apk, out / f"PhoneCam-{name}.apk")
    run(["python3", "tools/build_native.py"])
    plugin = ROOT / "native/build/phonecam-usb.plugin"
    # The zip (manual copy) and the .pkg installer (user's home, no Terminal step), each checked with
    # codesign --verify --strict on exactly what users unpack or install. The universal (Intel) build is a CI
    # test artifact only until an Intel Mac has tested it.
    macos_package.package(plugin, out, name, "arm64")
    sums = "".join(f"{sha256(path)}  {path.name}\n" for path in sorted(out.iterdir()))
    (out / "SHA256SUMS.txt").write_text(sums)
    print(f"Release files in {out}:\n{sums}Nothing was published. Test these exact files on a real phone and OBS first.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--create-key", action="store_true", help="Create the private release signing key (once)")
    args = parser.parse_args()
    create_key() if args.create_key else build()


if __name__ == "__main__":
    main()
