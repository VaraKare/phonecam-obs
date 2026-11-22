#!/usr/bin/env python3
"""Package a built Mac plugin bundle: the zip (manual copy) and an unsigned .pkg installer, both verified.

The .pkg installs for the current user only, into ~/Library/Application Support/obs-studio/plugins/, so no
Terminal step and no administrator password. It is not signed with a Developer ID: macOS asks the user to allow it
(System Settings > Privacy & Security > Open Anyway) the first time.
  python3 tools/macos_package.py native/build/phonecam-usb.plugin OUT_DIR [--version V] [--arch arm64]
"""
from pathlib import Path
import argparse
import plistlib
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = "phonecam-usb.plugin"
BUNDLE_ID = "dev.phonecam.obs.usb"
PKG_ID = BUNDLE_ID + ".pkg"
COMPONENT = "phonecam-usb.pkg"  # the inner package; distribution.xml names it
# Relative to the install domain: with enable_currentUserHome the Installer puts it under the user's home.
INSTALL_LOCATION = "/Library/Application Support/obs-studio/plugins"
TEMPLATES = Path(__file__).resolve().parent / "macos_pkg"
INSTALL_TEXT = """PhoneCam OBS plugin {version} (macOS, Apple Silicon, OBS 33)

Easier: use PhoneCam-OBS-plugin-macos-arm64-{version}.pkg from the same release instead (no Terminal step).
By hand:
1. Quit OBS.
2. Copy phonecam-usb.plugin into:
     ~/Library/Application Support/obs-studio/plugins/
   (Finder: Go > Go to Folder..., paste the path; create the "plugins" folder if missing.)
3. This preview is not signed by Apple, so macOS blocks downloaded copies. In Terminal run:
     xattr -dr com.apple.quarantine ~/Library/Application\\ Support/obs-studio/plugins/phonecam-usb.plugin
4. Install Android platform-tools (adb), e.g. `brew install android-platform-tools`.
5. Open OBS, Sources > + > PhoneCam (phone camera).

Full guide, troubleshooting and source code: see the README in the GitHub repository.
License: GPL-2.0-or-later (COPYING-GPL-2.0.txt). Third-party notices: NOTICE.txt.
"""


def run(command, **kwargs):
    return subprocess.run([str(x) for x in command], check=True, **kwargs)


def plugin_version():
    match = re.search(r'PLUGIN_VERSION = "([^"]+)"', (ROOT / "tools/build_native.py").read_text())
    if not match: raise SystemExit("PLUGIN_VERSION not found in tools/build_native.py")
    return match.group(1)


def verify_signature(bundle):
    run(["codesign", "--verify", "--strict", "--verbose=2", bundle])


def shipped_files(bundle):
    """Every file in the bundle; refuses debug symbols (they name the builder's home folder, AUDIT-015)."""
    files = sorted(path for path in Path(bundle).rglob("*") if path.is_file())
    leaked = [str(path) for path in files if any(part.endswith(".dSYM") for part in path.parts)]
    if leaked: raise SystemExit(f"Debug symbols inside the plugin bundle (rebuild with tools/build_native.py): {leaked[:3]}")
    return files


def write_zip(bundle, archive, version):
    bundle = Path(bundle)
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as out:
        for path in shipped_files(bundle):
            info = zipfile.ZipInfo.from_file(path, f"{BUNDLE}/{path.relative_to(bundle).as_posix()}")
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, path.read_bytes())
        out.writestr("INSTALL.txt", INSTALL_TEXT.format(version=version))
    return archive


def verify_zip(archive):
    """Unzips like Finder (ditto) and checks the signature of exactly what users get."""
    with tempfile.TemporaryDirectory() as folder:
        run(["ditto", "-x", "-k", archive, folder])
        verify_signature(Path(folder) / BUNDLE)


def distribution(version, arch):
    text = (TEMPLATES / "distribution.xml").read_text()
    return text.replace("@VERSION@", version).replace("@ARCH@", arch).replace("@PKG_ID@", PKG_ID)


def component_plist():
    # Not relocatable: otherwise the Installer "upgrades" any copy with this identifier it finds elsewhere on the
    # disk (a build folder, a download) instead of installing into the OBS plugins folder.
    return plistlib.dumps([{"RootRelativeBundlePath": BUNDLE, "BundleIsRelocatable": False, "BundleIsVersionChecked": False,
                            "BundleHasStrictIdentifier": True, "BundleOverwriteAction": "upgrade"}])


def build_pkg(bundle, output, version, arch="arm64"):
    shipped_files(bundle)
    verify_signature(bundle)
    with tempfile.TemporaryDirectory() as work:
        work = Path(work)
        root, scripts, resources = work / "root", work / "scripts", work / "resources"
        root.mkdir()
        # No extended attributes from the build Mac; a quarantine flag must never ship (an APFS clone keeps
        # attributes even with --noextattr, so the flag is also removed from the copy explicitly).
        run(["ditto", "--noextattr", "--noqtn", "--norsrc", "--noacl", bundle, root / BUNDLE])
        run(["xattr", "-dr", "com.apple.quarantine", root / BUNDLE])
        shutil.copytree(TEMPLATES / "scripts", scripts)
        for script in scripts.iterdir(): script.chmod(0o755)
        shutil.copytree(TEMPLATES / "resources", resources)
        (work / "component.plist").write_bytes(component_plist())
        (work / "distribution.xml").write_text(distribution(version, arch))
        component = work / "packages" / COMPONENT
        component.parent.mkdir()
        run(["pkgbuild", "--root", root, "--component-plist", work / "component.plist", "--scripts", scripts,
             "--identifier", PKG_ID, "--version", version, "--install-location", INSTALL_LOCATION, component])
        run(["productbuild", "--distribution", work / "distribution.xml", "--resources", resources,
             "--package-path", component.parent, output])
    verify_pkg(output, version)
    return output


def verify_pkg(package, version):
    """The installer as built: user-home only, the one bundle with a valid signature, no debug symbols."""
    with tempfile.TemporaryDirectory() as folder:
        expanded = Path(folder) / "expanded"
        run(["pkgutil", "--expand-full", package, expanded])
        text = (expanded / "Distribution").read_text()
        domains = re.search(r"<domains\b[^>]*>", text)
        if not domains or 'enable_currentUserHome="true"' not in domains.group(0) or 'enable_localSystem="false"' not in domains.group(0) \
                or 'enable_anywhere="false"' not in domains.group(0):
            raise SystemExit(f"Installer must install for the current user only: {domains and domains.group(0)}")
        component = expanded / COMPONENT
        info = (component / "PackageInfo").read_text()
        if f'install-location="{INSTALL_LOCATION}"' not in info or f'version="{version}"' not in info:
            raise SystemExit(f"Unexpected PackageInfo: {info[:500]}")
        payload = component / "Payload"
        if sorted(p.name for p in payload.iterdir()) != [BUNDLE]:
            raise SystemExit(f"Installer payload must be only {BUNDLE}: {sorted(p.name for p in payload.iterdir())}")
        shipped_files(payload / BUNDLE)
        verify_signature(payload / BUNDLE)
        # A quarantine flag carried in the payload would make OBS refuse to load the installed plugin. (macOS may
        # tag files with com.apple.provenance, which cannot be removed here and does not block loading.)
        attributes = run(["xattr", "-lr", payload], capture_output=True, text=True).stdout
        if "com.apple.quarantine" in attributes: raise SystemExit("Installer payload carries a quarantine flag")


def package(bundle, out, version, arch="arm64"):
    """Both Mac downloads for one bundle, in out/; returns their paths."""
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    verify_signature(bundle)
    archive = write_zip(bundle, out / f"PhoneCam-OBS-plugin-macos-{arch}-{version}.zip", version)
    verify_zip(archive)
    installer = build_pkg(bundle, out / f"PhoneCam-OBS-plugin-macos-{arch}-{version}.pkg", version, arch)
    return archive, installer


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("--version", default=plugin_version())
    parser.add_argument("--arch", default="arm64", choices=["arm64"],
                        help="Only arm64 for now: no Intel Mac has tested the universal build")
    args = parser.parse_args()
    for path in package(args.bundle, args.out, args.version, args.arch): print(path)


if __name__ == "__main__":
    main()
