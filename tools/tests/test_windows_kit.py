"""Portable Windows test kit (tools/windows_kit.py): kit contents, the served script and the report server."""
from pathlib import Path
import http.client
import http.server
import json
import sys
import tempfile
import threading
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import windows_kit  # noqa: E402


def make_zip(path, entries):
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in entries.items(): archive.writestr(name, data)
    return path


class KitTest(unittest.TestCase):
    def setUp(self):
        self.folder = Path(tempfile.mkdtemp())
        artifact = self.folder / "artifact/native/build/windows"
        (artifact / "vc-runtime").mkdir(parents=True)
        (artifact / "phonecam-obs.dll").write_bytes(b"plugin")
        (artifact / "vc-runtime/vcruntime140.dll").write_bytes(b"runtime")
        (artifact / "THIRD_PARTY_NOTICES.txt").write_text("QR Code generator library (MIT)")
        self.artifact = self.folder / "artifact"
        self.obs = make_zip(self.folder / "obs.zip", {"bin/64bit/obs64.exe": "obs", "../escape.txt": "x", "data/obs-studio/x.txt": "y"})
        self.tools = make_zip(self.folder / "tools.zip", {"platform-tools/adb.exe": "adb", "other/readme.txt": "z"})

    def build(self, wifi):
        out = windows_kit.build_kit(self.artifact, self.obs, self.tools, self.folder / "kit/kit.zip", wifi)
        with zipfile.ZipFile(out) as kit:
            return {name: kit.read(name) for name in kit.namelist()}

    def test_kit_contents(self):
        files = self.build(False)
        self.assertEqual(files["OBS/plugins/phonecam-obs/phonecam-obs.dll"], b"plugin")
        self.assertEqual(files["OBS/bin/64bit/vcruntime140.dll"], b"runtime")
        self.assertIn("MIT", files["OBS/plugins/phonecam-obs/THIRD_PARTY_NOTICES.txt"].decode())
        self.assertIn("OBS/portable_mode.txt", files)
        self.assertIn(b"FirstRun=true", files["OBS/config/obs-studio/user.ini"])
        self.assertEqual(files["OBS/bin/64bit/obs64.exe"], b"obs")
        self.assertEqual(files["platform-tools/adb.exe"], b"adb")
        self.assertFalse(any(".." in name or name.startswith("other/") for name in files), list(files))
        scene = json.loads(files["OBS/config/obs-studio/basic/scenes/PhoneCam_test.json"])
        source = next(s for s in scene["sources"] if s["id"] == "phonecam_usb_v1")
        self.assertIs(source["settings"]["wifi_direct"], False)   # no listener, so no firewall prompt
        item = scene["sources"][0]["settings"]["items"][0]
        self.assertEqual((item["source_uuid"], item["visible"]), (source["uuid"], True))
        self.assertFalse(json.loads((self.folder / "kit/kit.json").read_text())["wifi"])

    def test_wifi_kit(self):
        scene = json.loads(self.build(True)["OBS/config/obs-studio/basic/scenes/PhoneCam_test.json"])
        self.assertIs(next(s for s in scene["sources"] if s["id"] == "phonecam_usb_v1")["settings"]["wifi_direct"], True)

    def test_script_is_filled_and_plain_ascii(self):
        script = windows_kit.kit_script("http://192.168.1.5:8765/abc234", False, "ab" * 32)
        self.assertIn("$Url = 'http://192.168.1.5:8765/abc234'", script)
        self.assertIn("$Wifi = $false", script)
        self.assertIn("$ZipSha = '" + "ab" * 32 + "'", script)
        self.assertIn("Get-FileHash -Algorithm SHA256 $Zip", script)   # a changed download is refused (AUDIT-016)
        self.assertNotIn("devices -l", script)                          # no phone serial numbers in reports
        self.assertNotIn("-Raw", script)                                # never the whole OBS log
        self.assertIn("Replace($env:USERPROFILE, '~')", script)
        with self.assertRaises(ValueError): windows_kit.kit_script("http://x", False, "not-a-hash")
        script.encode("ascii")   # Windows PowerShell 5 reads scripts in the system code page
        for setting in ("ADB_TRACE", "ANDROID_ADB_SERVER_PORT", "PHONECAM_WIFI_STORE", "server', 'nodaemon"):
            self.assertIn(setting, script)

    def test_sha256_file(self):
        data = self.folder / "data.bin"; data.write_bytes(b"abc")
        self.assertEqual(windows_kit.sha256_file(data), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    def test_server_needs_the_token(self):
        kit_zip = make_zip(self.folder / "kit.zip", {"a": "b"})
        reports = self.folder / "reports"; reports.mkdir()
        handler = windows_kit.make_handler("abc234", "script text", kit_zip, reports)
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        port = server.server_address[1]
        def request(method, path, body=None):
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
            connection.request(method, path, body=body)
            response = connection.getresponse()
            return response.status, response.getheader("Content-Type"), response.read()
        try:
            status, kind, data = request("GET", "/abc234")
            self.assertEqual((status, data), (200, b"script text"))
            self.assertTrue(kind.startswith("text/plain"))   # PowerShell 5 gives bytes, not text, otherwise
            self.assertEqual(request("GET", "/abc234/kit.zip")[2], kit_zip.read_bytes())
            self.assertEqual(request("GET", "/wrong")[0], 404)
            self.assertEqual(request("GET", "/abc234/../kit.zip")[0], 404)
            self.assertEqual(request("POST", "/abc234/report/live", b"[phonecam] Ready")[0], 204)
            self.assertEqual((reports / "live.txt").read_bytes(), b"[phonecam] Ready")
            self.assertEqual(request("POST", "/wrong/report/live", b"x")[0], 404)
            self.assertEqual(request("POST", "/abc234/report/..%2Fx", b"x")[0], 404)
            self.assertEqual(sorted(p.name for p in reports.iterdir()), ["_events.log", "live.txt"])
        finally:
            server.shutdown(); server.server_close()


if __name__ == "__main__":
    unittest.main()
