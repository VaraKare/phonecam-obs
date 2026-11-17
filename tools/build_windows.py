#!/usr/bin/env python3
"""Build and test the Windows (x64) OBS plugin with MSVC. Test build only (docs/WINDOWS.md).

Downloads are pinned by SHA-256: OBS 33.0.1 source headers and SIMDe (shared with build_native.py) and the
official OBS 33.0.1 Windows x64 zip, whose obs.dll provides the import library and the libobs used by the
load test. Finds Visual Studio's x64 tools with vswhere when not run from a Developer Command Prompt.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_native import BUILD, ROOT, dependencies  # noqa: E402

OBS_VERSION = "33.0.1"
OBS_ZIP = ("OBS-Studio-33.0.1-Windows-x64.zip",
           "https://github.com/obsproject/obs-studio/releases/download/33.0.1/OBS-Studio-33.0.1-Windows-x64.zip",
           "0d9436451c2d772b33428e3fdae9776f071895c335de17283176306fb72651d3")
OBS_DEPS = ("windows-deps-2026-10-23-x64.zip",  # pinned by OBS 33.0.1 (CMakePresets.json)
            "https://github.com/obsproject/obs-deps/releases/download/2026-10-23/windows-deps-2026-10-23-x64.zip",
            "7508972a867a0ef68c26cd8bdba61fd999edd0a92fa9eb9585774de9bf51bfbe")
# Project Nayuki's QR Code generator (MIT) for the Wi-Fi pairing card; the Mac uses Core Image instead.
QRCODEGEN = [("qrcodegen.hpp", "https://raw.githubusercontent.com/nayuki/QR-Code-generator/v1.8.0/cpp/qrcodegen.hpp",
              "b779c3b156cf7a57ce789d6fee4fc991ccc2913774d26c909d22bb8f26b2a793"),
             ("qrcodegen.cpp", "https://raw.githubusercontent.com/nayuki/QR-Code-generator/v1.8.0/cpp/qrcodegen.cpp",
              "1f3b3fcdac6954c32cf583ccd02ec9b5901f756a38c461acedc70be4a77d3757")]
OUT = BUILD / "windows"
FIXTURES = ROOT / "native/tests/fixtures"


def fetch(name, url, expected):
    archive = BUILD / name
    if not archive.exists():
        BUILD.mkdir(parents=True, exist_ok=True)
        temporary = archive.with_suffix(".download")
        urllib.request.urlretrieve(url, temporary)
        temporary.replace(archive)
    digest = hashlib.sha256()
    with open(archive, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""): digest.update(block)
    if digest.hexdigest() != expected:
        archive.unlink()
        raise RuntimeError(f"Dependency checksum mismatch: {name}")
    return archive


def qrcodegen():
    """Pinned QR library sources in native/build/qrcodegen (not vendored in Git)."""
    folder = BUILD / "qrcodegen"
    folder.mkdir(parents=True, exist_ok=True)
    for name, url, expected in QRCODEGEN:
        archive = fetch(f"qrcodegen-v1.8.0-{name}", url, expected)
        shutil.copyfile(archive, folder / name)
    return folder


def wifi_checks(runtime, receiver):
    """The pairing card draws, and the phone's Kotlin client pairs with and streams to the Windows server."""
    import struct
    card = OUT / "pairing-card.bmp"
    run([receiver, "--card", card], dict(os.environ), cwd=runtime)
    data = card.read_bytes()
    assert data[:2] == b"BM" and len(data) == 54 + 1280 * 720 * 4, len(data)
    def pixel(x, y):
        offset = 54 + (y * 1280 + x) * 4
        return tuple(data[offset:offset + 3])  # B, G, R
    assert pixel(5, 5) == (41, 23, 15), pixel(5, 5)                  # card background
    assert pixel(100 + 10, 100 + 10) == (255, 255, 255), pixel(110, 110)  # QR quiet zone (margin 100 px at 720p)
    dark = sum(1 for y in range(140, 560, 4) for x in range(140, 560, 4) if pixel(x, y) == (0, 0, 0))
    assert dark > 1000, dark                                          # QR modules drawn
    text = sum(1 for y in range(110, 160) for x in range(720, 1100) if pixel(x, y)[0] > 200)
    assert text > 500, text                                           # title text drawn
    print("PASS: Windows Wi-Fi pairing card (QR code and text)", flush=True)

    if os.environ.get("PHONECAM_WINDOWS_INTEROP") != "1":
        print("SKIP: Kotlin phone client against the Windows Wi-Fi server (set PHONECAM_WINDOWS_INTEROP=1; CI does)", flush=True)
        return
    import tempfile
    with tempfile.TemporaryDirectory() as directory:
        env = dict(os.environ, PHONECAM_WIFI_STORE=str(Path(directory) / "wifi.json"))
        server = subprocess.Popen([str(receiver), "--wifi", "12"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env, cwd=runtime)
        # Drain stderr while Gradle runs: a full pipe would block the server's log writes, and with them the
        # server thread, which then refuses or ignores the phone (a flaky failure, not the code under test).
        import threading
        errors = []
        drain = threading.Thread(target=lambda: errors.extend(server.stderr), daemon=True)
        drain.start()
        def failure_details():
            results = ROOT / "app/build/test-results/testDebugUnitTest/TEST-dev.phonecam.obs.WifiInteropTest.xml"
            junit = results.read_text()[-4000:] if results.exists() else "(no JUnit report)"
            return f"\n--- JUnit ---\n{junit}\n--- Windows server ---\n{''.join(errors)[-4000:]}"
        try:
            line = server.stdout.readline().split()
            assert line and line[0] == "PAIR", line
            env["PHONECAM_WIFI_PEER"] = f"127.0.0.1 {line[1]} {line[2]} {line[3]}"
            env["PHONECAM_WIFI_PACKETS"] = str(FIXTURES / "avc-640x360.pcam")
            gradle = subprocess.run([str(ROOT / "gradlew.bat"), "--no-daemon", "-q", "testDebugUnitTest", "--tests", "*WifiInteropTest*", "--rerun"],
                                    cwd=ROOT, env=env, capture_output=True, text=True, timeout=1200)
            assert gradle.returncode == 0, gradle.stdout[-3000:] + gradle.stderr[-3000:] + failure_details()
            report = (ROOT / "app/build/test-results/testDebugUnitTest/TEST-dev.phonecam.obs.WifiInteropTest.xml").read_text()
            assert 'skipped="0"' in report, report[:2000]
            server.wait(timeout=60)  # stdout carries only the PAIR line and the final JSON, so it cannot fill
            out = server.stdout.read()
            drain.join(timeout=10)
            err = "".join(errors)
        finally:
            if server.poll() is None: server.kill()
        print(err, flush=True)
        assert server.returncode == 0, err
        value = json.loads(out.strip().splitlines()[-1])
        assert value["decodedFrames"] == 12 and (value["lastWidth"], value["lastHeight"]) == (640, 360), value
        assert "wrong code" in err and "Paired with Test phone" in err and "Live over direct Wi-Fi" in err, err
        assert "Listening before pairing: 0" in err, err  # nothing paired yet: no open port (AUDIT-006)
        assert json.loads((Path(directory) / "wifi.json").read_text())["phones"][0]["name"] == "Test phone"
    print("PASS: Windows direct Wi-Fi with the phone's Kotlin client: wrong code refused, pairing, encrypted video", flush=True)
    damaged_store_check(receiver, runtime)


def damaged_store_check(receiver, runtime):
    """A pairings file that cannot be read is reported and left as it is, never replaced (pairings kept) and never
    a crash (AUDIT-004, AUDIT-021e)."""
    import tempfile
    with tempfile.TemporaryDirectory() as directory:
        store = Path(directory) / "wifi.json"
        store.write_bytes(b"{not json")
        env = dict(os.environ, PHONECAM_WIFI_STORE=str(store))
        result = subprocess.run([str(receiver), "--wifi", "1"], capture_output=True, text=True, encoding="utf-8", errors="replace",
                                env=env, cwd=runtime, timeout=60)
        assert result.returncode == 1, (result.returncode, result.stderr)  # an orderly failure, not an abort
        assert "could not be read" in result.stderr, result.stderr
        assert store.read_bytes() == b"{not json"
    print("PASS: Windows Wi-Fi keeps a damaged pairings file and reports it", flush=True)


def msvc_environment():
    if os.environ.get("VCINSTALLDIR") and shutil.which("cl"):
        return dict(os.environ)
    vswhere = Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Microsoft Visual Studio/Installer/vswhere.exe"
    if not vswhere.exists():
        raise SystemExit("Visual Studio 2022 with C++ tools is required (vswhere.exe not found)")
    path = subprocess.run([str(vswhere), "-latest", "-products", "*", "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                           "-property", "installationPath"], capture_output=True, text=True, check=True).stdout.strip()
    vcvars = Path(path) / "VC/Auxiliary/Build/vcvars64.bat"
    dump = subprocess.run(f'"{vcvars}" >nul && set', shell=True, capture_output=True, text=True, check=True).stdout
    return dict(line.split("=", 1) for line in dump.splitlines() if "=" in line)


def tool(name, env):
    """Windows looks up programs in the parent's PATH, not the one passed in env, so resolve it here."""
    path = next((value for key, value in env.items() if key.upper() == "PATH"), None)
    found = shutil.which(name, path=path)
    if not found: raise SystemExit(f"{name} not found; install Visual Studio 2022 with C++ tools")
    return found


def run(command, env, cwd=ROOT):
    command = [str(c) for c in command]
    if command[0] in ("cl", "lib", "dumpbin"): command[0] = tool(command[0], env)
    print("+", " ".join(command), flush=True)
    subprocess.run(command, check=True, env=env, cwd=cwd)


def obs_runtime(env):
    """Extracts OBS's 64-bit binaries and makes obs.lib from obs.dll's exports."""
    runtime = OUT / "obs-bin"
    if not (runtime / "obs.dll").exists():
        with zipfile.ZipFile(fetch(*OBS_ZIP)) as bundle:
            for member in bundle.infolist():
                parts = Path(member.filename).parts
                if member.is_dir() or ".." in parts or "64bit" not in parts: continue
                relative = Path(*parts[parts.index("64bit") + 1:])
                target = runtime / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(bundle.read(member))
    exports = subprocess.run([tool("dumpbin", env), "/nologo", "/exports", str(runtime / "obs.dll")], capture_output=True, text=True, check=True, env=env).stdout
    names = re.findall(r"^\s+\d+\s+[0-9A-F]+\s+[0-9A-F]{8}\s+(\S+)", exports, re.M)
    if len(names) < 500: raise RuntimeError(f"Unexpected obs.dll exports ({len(names)})")
    (OUT / "obs.def").write_text("LIBRARY obs\nEXPORTS\n" + "".join(f"    {n}\n" for n in names))
    run(["lib", "/nologo", "/machine:x64", f"/def:{OUT / 'obs.def'}", f"/out:{OUT / 'obs.lib'}"], env)
    return runtime


def ffmpeg_sdk():
    """FFmpeg headers and import libraries from OBS's own pinned dependencies (its DLLs ship with OBS)."""
    deps = OUT / "deps"
    if not (deps / "include/libavcodec/avcodec.h").exists():
        with zipfile.ZipFile(fetch(*OBS_DEPS)) as bundle:
            for member in bundle.infolist():
                parts = Path(member.filename).parts
                if member.is_dir() or ".." in parts: continue
                name = Path(member.filename).as_posix()
                if not (name.startswith(("include/libavcodec/", "include/libavutil/")) or Path(name).name.lower() in ("avcodec.lib", "avutil.lib")):
                    continue
                target = deps / Path(name)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(bundle.read(member))
    libs = {p.name.lower(): p for p in deps.rglob("*.lib")}
    if "avcodec.lib" not in libs or "avutil.lib" not in libs:
        raise RuntimeError(f"FFmpeg import libraries missing from obs-deps: {sorted(libs)}")
    return deps / "include", [libs["avcodec.lib"], libs["avutil.lib"]]


def native_tests(runtime, receiver, fake_adb):
    """Same checks as the macOS tests, on the recorded fixture streams and a fake adb."""
    import socket
    import threading

    def decode(name, frames=12):
        result = subprocess.run([str(receiver), "--file", str(FIXTURES / name), str(frames)], capture_output=True, text=True, cwd=runtime, timeout=60)
        if result.returncode: raise RuntimeError(f"{name}: {result.stderr}{result.stdout}")
        value = json.loads(result.stdout.strip().splitlines()[-1])
        print(name, value, flush=True)
        return value
    avc = decode("avc-640x360.pcam")
    assert (avc["lastWidth"], avc["lastHeight"]) == (640, 360), avc
    mirrored = decode("avc-640x360-mirror.pcam")
    assert abs(avc["leftLuma"] - avc["rightLuma"]) > 2, avc
    assert abs(mirrored["leftLuma"] - avc["rightLuma"]) <= 2 and abs(mirrored["rightLuma"] - avc["leftLuma"]) <= 2, (avc, mirrored)
    hevc = decode("hevc-320x240.pcam")
    assert (hevc["lastWidth"], hevc["lastHeight"]) == (320, 240), hevc
    portrait = decode("hevc-640x360-portrait.pcam")
    assert (portrait["lastWidth"], portrait["lastHeight"]) == (360, 640), portrait
    print("PASS: Windows decodes H.264 and H.265, rotates and mirrors like the Mac", flush=True)

    token = "b" * 64
    packets = (FIXTURES / "hevc-320x240.pcam").read_bytes()
    with socket.socket() as server:
        server.bind(("127.0.0.1", 0)); server.listen(2); server.settimeout(20)
        log = OUT / "fake-adb.log"
        log.unlink(missing_ok=True)
        received = []

        def phone():
            with server.accept()[0] as first: first.recv(65)  # not started yet: close at once
            with server.accept()[0] as client:
                auth = b""
                while len(auth) < 65:
                    value = client.recv(65 - len(auth))
                    if not value: break
                    auth += value
                received.append(auth)
                client.sendall(packets)
                try: client.recv(1)
                except OSError: pass
        worker = threading.Thread(target=phone, daemon=True); worker.start()
        with socket.socket() as probe:  # a free port for the adb server, away from any real one on 5037
            probe.bind(("127.0.0.1", 0)); adb_port = probe.getsockname()[1]
        env = dict(os.environ, FAKE_ADB_LOG=str(log), FAKE_ADB_TOKEN=token, FAKE_ADB_PORT=str(server.getsockname()[1]),
                   ANDROID_ADB_SERVER_PORT=str(adb_port))
        result = subprocess.run([str(receiver), "--auto", "12", str(fake_adb)], capture_output=True, text=True, cwd=runtime, env=env, timeout=60)
        worker.join(10)
        if result.returncode: raise RuntimeError(f"automatic connection: {result.stderr}{result.stdout}")
        assert received == [(token + "\n").encode()], received
        assert json.loads(result.stdout.strip().splitlines()[-1])["decodedFrames"] == 12
        calls = log.read_text()
        assert "-s FAKE1 shell content query --uri content://dev.phonecam.obs.pairing/token" in calls, calls
        assert "-s FAKE1 forward tcp:0 localabstract:phonecam-live-v1" in calls, calls
        assert f"-s FAKE1 forward --remove tcp:{server.getsockname()[1]}" in calls, calls
        assert "Ready. Tap Start live on the phone." in result.stderr, result.stderr
        # No adb server was running, so the receiver started one in foreground mode, once, and it ended
        # with the receiver (job object).
        assert calls.count(f"-L tcp:{adb_port} server nodaemon") == 1, calls
        with socket.socket() as probe:
            probe.settimeout(5)
            assert probe.connect_ex(("127.0.0.1", adb_port)) != 0, "adb server outlived the receiver"
    print("PASS: Windows automatic adb server start, pairing, forward, retry and receive", flush=True)
    replug_checks(runtime, receiver, fake_adb)


def messages(data):
    """Splits a recorded stream into its protocol messages (32-byte header, length at offset 8)."""
    out, position = [], 0
    while position < len(data):
        length = int.from_bytes(data[position + 8:position + 12], "big")
        out.append(data[position:position + 32 + length]); position += 32 + length
    return out


def replug_checks(runtime, receiver, fake_adb):
    """The 16 Nov 2026 laptop failure: unplugging during live dropped the link and left adb's server
    accepting connections but answering nothing, and the plugin never recovered. Here another program's
    adb.exe server (like the test kit's) stops answering "devices" and ignores kill-server: the receiver
    must end that server, start its own, find the phone again with a new link and receive the next session."""
    import socket
    import threading
    import time

    token = "d" * 64
    parts = messages((FIXTURES / "hevc-320x240.pcam").read_bytes())
    folder = OUT / "replug"
    shutil.rmtree(folder, ignore_errors=True); folder.mkdir()
    adb = folder / "adb.exe"  # the stuck server is ended only if it is an adb.exe
    shutil.copy(fake_adb, adb)
    log, hang = folder / "adb.log", folder / "hang"
    with socket.socket() as server:
        server.bind(("127.0.0.1", 0)); server.listen(4); server.settimeout(60)
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0)); adb_port = probe.getsockname()[1]
        env = dict(os.environ, FAKE_ADB_LOG=str(log), FAKE_ADB_TOKEN=token, FAKE_ADB_PORT=str(server.getsockname()[1]),
                   FAKE_ADB_HANG=str(hang), ANDROID_ADB_SERVER_PORT=str(adb_port))
        other = subprocess.Popen([str(adb), "-L", f"tcp:{adb_port}", "server", "nodaemon"], env=env)
        sessions = []

        def phone():
            for number in range(2):
                with server.accept()[0] as client:
                    auth = b""
                    while len(auth) < 65:
                        value = client.recv(65 - len(auth))
                        if not value: break
                        auth += value
                    sessions.append(auth)
                    if number == 0:
                        client.sendall(b"".join(parts[:6]))  # configuration and 5 frames, then the cable goes
                        hang.write_text("")
                    else:
                        client.sendall(b"".join(parts))
                        try: client.recv(1)
                        except OSError: pass
        try:
            for _ in range(50):
                with socket.socket() as probe:
                    if probe.connect_ex(("127.0.0.1", adb_port)) == 0: break
                time.sleep(0.1)
            worker = threading.Thread(target=phone, daemon=True); worker.start()
            result = subprocess.run([str(receiver), "--auto", "14", str(adb)], capture_output=True, text=True, encoding="utf-8", errors="replace",
                                    cwd=runtime, env=env, timeout=120)
            worker.join(10)
            if result.returncode: raise RuntimeError(f"replug: {result.stderr}{result.stdout}")
            assert sessions == [(token + "\n").encode()] * 2, sessions
            assert "Live ended" in result.stderr, result.stderr
            assert "adb stopped answering. Restarting it…" in result.stderr, result.stderr
            calls = log.read_text().splitlines()
            assert "kill-server" in calls, calls
            assert other.poll() is not None, "the stuck adb server was not ended"
            assert calls.count(f"-L tcp:{adb_port} server nodaemon") == 2, calls  # the other one, then ours
            assert calls.count("-s FAKE1 forward tcp:0 localabstract:phonecam-live-v1") == 2, calls
        finally:
            if other.poll() is None: other.kill()
    print("PASS: Windows replug: stuck adb server ended and replaced, phone found again, next session received", flush=True)


def main():
    if sys.platform != "win32":
        raise SystemExit("Run on Windows x64 (CI: windows-latest)")
    OUT.mkdir(parents=True, exist_ok=True)
    env = msvc_environment()
    sdk = dependencies(OBS_VERSION)
    strict = ["cl", "/nologo", "/std:c++17", "/EHsc", "/W4", "/WX", "/permissive-", "/utf-8", "/external:W0"]
    # Shared portable parsers, same tests as macOS.
    run(strict + ["/I", ROOT / "native", ROOT / "native/protocol_test.cpp", f"/Fe:{OUT / 'protocol-test.exe'}", f"/Fo:{OUT}\\"], env)
    run([OUT / "protocol-test.exe"], env)
    run(strict + ["/I", ROOT / "native", ROOT / "native/adb_test.cpp", f"/Fe:{OUT / 'adb-test.exe'}", f"/Fo:{OUT}\\"], env)
    run([OUT / "adb-test.exe"], env)
    run(strict + ["/I", ROOT / "native", ROOT / "native/rotate_test.cpp", f"/Fe:{OUT / 'rotate-test.exe'}", f"/Fo:{OUT}\\"], env)
    run([OUT / "rotate-test.exe"], env)
    # Wi-Fi crypto with Windows CNG against the vectors the Mac and the phone use.
    run(strict + ["/I", ROOT / "native", ROOT / "native/wifi_test.cpp", f"/Fe:{OUT / 'wifi-test.exe'}", f"/Fo:{OUT}\\", "/link", "bcrypt.lib"], env)
    run([OUT / "wifi-test.exe"], env)
    qr = qrcodegen()
    qr_object = OUT / "qrcodegen.obj"  # third-party code: built without the project's warnings-as-errors
    run(["cl", "/nologo", "/std:c++17", "/EHsc", "/O2", "/W1", "/c", qr / "qrcodegen.cpp", f"/Fo:{qr_object}"], env)
    ffmpeg_include, ffmpeg_libs = ffmpeg_sdk()
    runtime = obs_runtime(env)
    obs_headers = ["/external:I", sdk, "/external:I", BUILD / "simde"]
    shared = [ROOT / "native/windows/receiver_win.cpp", ROOT / "native/windows/adb_win.cpp", ROOT / "native/adb_session.cpp",
              ROOT / "native/wifi_server.cpp", ROOT / "native/windows/wifi_win.cpp", ROOT / "native/windows/pairing_card_win.cpp", qr_object]
    includes = ["/I", ROOT / "native", "/external:I", ffmpeg_include, "/external:I", qr]
    system = ["ws2_32.lib", "iphlpapi.lib", "bcrypt.lib", "dnsapi.lib", "gdi32.lib", "user32.lib"] + ffmpeg_libs
    plugin = OUT / "phonecam-obs.dll"
    run(strict + ["/O2", "/LD"] + includes + obs_headers + [ROOT / "native/windows/plugin_win.cpp"] + shared +
        [f"/Fe:{plugin}", f"/Fo:{OUT}\\", "/link", OUT / "obs.lib"] + system, env)
    receiver = runtime / "phonecam-receive.exe"  # next to OBS's FFmpeg DLLs
    run(strict + ["/O2"] + includes + [ROOT / "native/windows/receiver_cli_win.cpp"] + shared + [f"/Fe:{receiver}", f"/Fo:{OUT}\\", "/link"] + system, env)
    fake_adb = OUT / "fake-adb.exe"
    run(strict + [ROOT / "native/windows/fake_adb.cpp", f"/Fe:{fake_adb}", f"/Fo:{OUT}\\", "/link", "ws2_32.lib"], env)
    native_tests(runtime, receiver, fake_adb)
    wifi_checks(runtime, receiver)
    smoke = runtime / "phonecam-smoke.exe"  # next to obs.dll so its dependencies load
    run(strict + obs_headers + [ROOT / "native/windows/smoke_win.cpp", f"/Fe:{smoke}", f"/Fo:{OUT}\\", "/link", OUT / "obs.lib"], env)
    run([smoke, plugin], env, cwd=runtime)
    # Microsoft's C++ runtime, for app-local use in the portable test kit (redistributable per the
    # Visual Studio license), so a PC without the runtime installed can still run portable OBS.
    redist = Path(env.get("VCToolsRedistDir", ""))
    runtime_out = OUT / "vc-runtime"
    runtime_out.mkdir(exist_ok=True)
    found = sorted((redist / "x64").glob("Microsoft.VC*.CRT/*.dll")) if redist.name else []
    for dll in found: shutil.copy2(dll, runtime_out / dll.name)
    print("VC runtime for the kit:", [d.name for d in found], flush=True)
    # The plugin includes the QR library (MIT): ship its notice with the test build.
    header = (qr / "qrcodegen.hpp").read_text(encoding="utf-8").split("*/", 1)[0]
    notice = "\n".join(line.lstrip("/* ").rstrip() for line in header.splitlines())
    (OUT / "THIRD_PARTY_NOTICES.txt").write_text("PhoneCam OBS Windows test build includes:\n\n" + notice.strip() + "\n", encoding="utf-8")
    print(json.dumps({"plugin": str(plugin), "physicalGate": "not passed: test build only"}))


if __name__ == "__main__":
    main()
