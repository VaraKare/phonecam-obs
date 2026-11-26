"""Install packaging: the Mac bundle signature (no dSYM sealed in), zip and .pkg (tools/macos_package.py), and the
Windows installer files and Inno Setup script (tools/build_windows.py --package, native/windows/installer.iss).
Builds nothing real: tiny stand-in bundles and DLLs. The macOS signing and .pkg tests run only on macOS."""
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))
import build_native  # noqa: E402
import build_windows  # noqa: E402
import macos_package  # noqa: E402

MAC = sys.platform == "darwin" and all(shutil.which(tool) for tool in ("codesign", "pkgbuild", "productbuild", "pkgutil", "xcrun"))
INFO = {"CFBundleExecutable": "phonecam-usb", "CFBundleIdentifier": macos_package.BUNDLE_ID, "CFBundleName": "phonecam-usb",
        "CFBundlePackageType": "BNDL", "CFBundleShortVersionString": "9.9.9-test", "CFBundleVersion": "1"}


def fake_bundle(folder, identifier=macos_package.BUNDLE_ID):
    bundle = Path(folder) / macos_package.BUNDLE
    (bundle / "Contents/MacOS").mkdir(parents=True)
    (bundle / "Contents/Resources").mkdir()
    (bundle / "Contents/MacOS/phonecam-usb").write_bytes(b"\xcf\xfa\xed\xfe binary")
    (bundle / "Contents/Resources/NOTICE.txt").write_text("notice")
    (bundle / "Contents/Info.plist").write_bytes(plistlib.dumps(dict(INFO, CFBundleIdentifier=identifier)))
    return bundle


class MacPackageTextTest(unittest.TestCase):
    def test_distribution_installs_for_the_current_user_only(self):
        text = macos_package.distribution("1.2.3-alpha.4", "arm64")
        self.assertNotIn("@", text.replace("@ARCH", ""))  # every placeholder filled
        domains = re.search(r"<domains\b[^>]*>", text).group(0)
        self.assertIn('enable_currentUserHome="true"', domains)
        self.assertIn('enable_localSystem="false"', domains)
        self.assertIn('enable_anywhere="false"', domains)
        self.assertIn('hostArchitectures="arm64"', text)
        self.assertIn(f'version="1.2.3-alpha.4" onConclusion="none">{macos_package.COMPONENT}</pkg-ref>', text)
        self.assertEqual(macos_package.INSTALL_LOCATION, "/Library/Application Support/obs-studio/plugins")

    def test_bundle_is_not_relocatable(self):
        (entry,) = plistlib.loads(macos_package.component_plist())
        self.assertEqual(entry["RootRelativeBundlePath"], "phonecam-usb.plugin")
        self.assertIs(entry["BundleIsRelocatable"], False)

    def test_zip_has_the_bundle_and_install_notes(self):
        with tempfile.TemporaryDirectory() as folder:
            bundle = fake_bundle(folder)
            archive = macos_package.write_zip(bundle, Path(folder) / "out.zip", "1.2.3")
            with zipfile.ZipFile(archive) as opened:
                names = sorted(opened.namelist())
                notes = opened.read("INSTALL.txt").decode()
        self.assertEqual(names, ["INSTALL.txt", "phonecam-usb.plugin/Contents/Info.plist", "phonecam-usb.plugin/Contents/MacOS/phonecam-usb",
                                 "phonecam-usb.plugin/Contents/Resources/NOTICE.txt"])
        self.assertIn("PhoneCam-OBS-plugin-macos-arm64-1.2.3.pkg", notes)

    def test_debug_symbols_in_the_bundle_are_refused(self):
        # The v0.6 fault: a dSYM inside the signed bundle, then left out of the zip, broke the signature.
        with tempfile.TemporaryDirectory() as folder:
            bundle = fake_bundle(folder)
            dsym = bundle / "Contents/MacOS/phonecam-usb.dSYM/Contents/Resources/DWARF/phonecam-usb"
            dsym.parent.mkdir(parents=True)
            dsym.write_bytes(b"dwarf")
            with self.assertRaises(SystemExit):
                macos_package.write_zip(bundle, Path(folder) / "out.zip", "1.2.3")

    def test_preinstall_removes_only_phonecam(self):
        script = TOOLS / "macos_pkg/scripts/preinstall"
        with tempfile.TemporaryDirectory() as ours, tempfile.TemporaryDirectory() as other:
            fake_bundle(other, "org.example.other")
            subprocess.run(["sh", script, "pkg", other, "/"], check=True)
            self.assertTrue((Path(other) / macos_package.BUNDLE).is_dir())
            subprocess.run(["sh", script, "pkg", "", "/"], check=True)  # no destination: nothing happens
            if Path("/usr/libexec/PlistBuddy").exists():
                bundle = fake_bundle(ours)
                (bundle / "Contents/MacOS/stale.txt").write_text("old")
                subprocess.run(["sh", script, "pkg", ours, "/"], check=True)
                self.assertFalse(bundle.exists())


@unittest.skipUnless(MAC, "macOS signing and installer tools")
class MacSigningTest(unittest.TestCase):
    """make_bundle and package() on a stand-in plugin (a copy of /usr/bin/true, re-signed ad hoc)."""

    def setUp(self):
        self.folder = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.folder)
        build = self.folder / "build"
        (build / "simde").mkdir(parents=True)
        (build / "simde/COPYING").write_text("simde licence")
        self.linked = build / "phonecam-usb"
        shutil.copyfile("/usr/bin/true", self.linked)
        dsym = build / "phonecam-usb.dSYM/Contents/Resources/DWARF/phonecam-usb"  # where clang leaves it
        dsym.parent.mkdir(parents=True)
        dsym.write_bytes(b"dwarf")
        self.original_build = build_native.BUILD
        build_native.BUILD = build
        self.addCleanup(setattr, build_native, "BUILD", self.original_build)
        self.bundle = build / "phonecam-usb.plugin"

    def make(self):
        return build_native.make_bundle(self.bundle, self.linked, INFO)

    def test_bundle_is_signed_without_debug_symbols_and_rebuilt_clean(self):
        stale = self.bundle / "Contents/MacOS/phonecam-usb.dSYM/Contents/Info.plist"  # an earlier build's layout
        stale.parent.mkdir(parents=True)
        stale.write_text("old")
        self.make()
        self.assertFalse((self.bundle / "Contents/MacOS/phonecam-usb.dSYM").exists())
        self.assertTrue((self.bundle.parent / "phonecam-usb.dSYM").is_dir())
        build_native.verify_signature(self.bundle)
        (self.bundle / "Contents/Resources/NOTICE.txt").unlink()  # a sealed file missing, as the v0.6 zip had
        with self.assertRaises(subprocess.CalledProcessError):
            build_native.verify_signature(self.bundle)

    def test_zip_and_pkg_are_verified(self):
        self.make()
        out = self.folder / "dist"
        archive, installer = macos_package.package(self.bundle, out, "9.9.9-test")
        self.assertEqual(archive.name, "PhoneCam-OBS-plugin-macos-arm64-9.9.9-test.zip")
        self.assertEqual(installer.name, "PhoneCam-OBS-plugin-macos-arm64-9.9.9-test.pkg")
        domains = subprocess.run(["installer", "-dominfo", "-pkg", str(installer)], capture_output=True, text=True, check=True).stdout
        self.assertEqual(domains.split(), ["CurrentUserHomeDirectory"])

    def test_unsealed_file_is_refused_before_packaging(self):
        self.make()
        (self.bundle / "Contents/MacOS/extra.txt").write_text("added after signing")
        with self.assertRaises(subprocess.CalledProcessError):
            macos_package.package(self.bundle, self.folder / "dist", "9.9.9-test")


class WindowsInstallerTest(unittest.TestCase):
    def setUp(self):
        self.folder = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.folder)
        build = self.folder / "build"
        (build / "simde").mkdir(parents=True)
        (build / "simde/COPYING").write_text("simde licence")
        self.out = build / "windows"
        self.out.mkdir()
        self.original_build = build_windows.BUILD
        build_windows.BUILD = build
        self.addCleanup(setattr, build_windows, "BUILD", self.original_build)

    def test_stage_has_obs_plugin_layout_and_no_adb(self):
        (self.out / "phonecam-obs.dll").write_bytes(b"MZ plugin")
        (self.out / "THIRD_PARTY_NOTICES.txt").write_text("qr")
        (self.out / "vc-runtime").mkdir()
        (self.out / "vc-runtime/vcruntime140.dll").write_bytes(b"MZ runtime")
        stage = build_windows.stage_installer(self.out)
        files = sorted(p.relative_to(stage).as_posix() for p in stage.rglob("*") if p.is_file())
        self.assertEqual(files, ["bin/64bit/phonecam-obs.dll", "data/COPYING-GPL-2.0.txt", "data/COPYING-SIMDe.txt",
                                 "data/NOTICE.txt", "data/THIRD_PARTY_NOTICES.txt"])
        self.assertEqual((stage / "bin/64bit/phonecam-obs.dll").read_bytes(), b"MZ plugin")

    def test_stage_needs_a_build(self):
        with self.assertRaises(SystemExit):
            build_windows.stage_installer(self.out)

    def test_versions_and_compiler_command(self):
        self.assertEqual(build_windows.numeric_version("0.7.0-alpha.1"), "0.7.0")
        command = build_windows.iscc_command("ISCC.exe", Path("stage"), Path("out"), "0.7.0-alpha.1")
        self.assertIn("/DAppVersion=0.7.0-alpha.1", command)
        self.assertIn("/DNumericVersion=0.7.0", command)
        self.assertTrue(command[-1].endswith("installer.iss"))


class InnoScriptTest(unittest.TestCase):
    text = (ROOT / "native/windows/installer.iss").read_text()

    def setting(self, name):
        return re.search(rf"^{name}=(.*)$", self.text, re.M).group(1)

    def test_installs_into_obs_plugin_folder_named_after_the_dll(self):
        self.assertEqual(self.setting("DefaultDirName"), r"{commonappdata}\obs-studio\plugins" + "\\" + build_windows.PLUGIN)
        self.assertIn(rf'Source: "{{#StageDir}}\bin\64bit\{build_windows.PLUGIN}.dll"; DestDir: "{{app}}\bin\64bit"', self.text)
        self.assertEqual(self.setting("PrivilegesRequired"), "admin")
        self.assertEqual(self.setting("OutputBaseFilename"), "PhoneCam-OBS-plugin-windows-x64-{#AppVersion}-setup")

    def test_has_uninstaller_and_no_bundled_adb(self):
        self.assertNotRegex(self.text, r"(?i)Uninstallable=no")
        self.assertNotRegex(self.text, r"(?im)^Source:.*adb")
        self.assertIn("developer.android.com/tools/releases/platform-tools", self.setting("FinishedLabel"))


class UniversalPinTest(unittest.TestCase):
    def test_intel_obs_is_pinned(self):
        name, url, digest = build_native.INTEL_OBS
        self.assertEqual(name, "OBS-Studio-33.0.1-macOS-Intel.dmg")
        self.assertTrue(url.startswith("https://github.com/obsproject/obs-studio/releases/download/33.0.1/"))
        self.assertRegex(digest, r"^[0-9a-f]{64}$")


class ObsUpdateTest(unittest.TestCase):
    """OBS updates must not change what the plugin is built against (owner, 23 Nov 2026)."""
    def test_one_pinned_sdk_baseline_for_every_build(self):
        self.assertEqual(list(build_native.OBS_ARCHIVES), [build_native.SDK_VERSION])
        self.assertRegex(build_native.OBS_ARCHIVES[build_native.SDK_VERSION], r"^[0-9a-f]{64}$")
        windows = (ROOT / "tools/build_windows.py").read_text()
        self.assertIn("dependencies(SDK_VERSION)", windows)
        self.assertIn("dependencies(SDK_VERSION)", (ROOT / "tools/build_native.py").read_text())

    def test_versions_compare_by_number(self):
        v = build_native.obs_version
        self.assertEqual(v("33.0.2"), (33, 0, 2))
        self.assertEqual(v("34.0"), (34, 0, 0))
        self.assertEqual(v("33.1.0-beta1"), (33, 1, 0))
        self.assertGreater(v("33.10.0"), v("33.9.0"))  # not text order

    def test_weekly_newest_obs_check_exists(self):
        workflow = (ROOT / ".github/workflows/newest-obs.yml").read_text()
        self.assertIn("schedule:", workflow)
        self.assertIn("2MMRE5MTB8", workflow)  # OBS's own Apple signature
        self.assertIn("--also-load-in", workflow)


if __name__ == "__main__":
    unittest.main()
