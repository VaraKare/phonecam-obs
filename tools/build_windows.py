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
        env = dict(os.environ, FAKE_ADB_LOG=str(log), FAKE_ADB_TOKEN=token, FAKE_ADB_PORT=str(server.getsockname()[1]))
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
    print("PASS: Windows automatic adb pairing, forward, retry and receive", flush=True)


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
    ffmpeg_include, ffmpeg_libs = ffmpeg_sdk()
    runtime = obs_runtime(env)
    obs_headers = ["/external:I", sdk, "/external:I", BUILD / "simde"]
    shared = [ROOT / "native/windows/receiver_win.cpp", ROOT / "native/windows/adb_win.cpp", ROOT / "native/adb_session.cpp"]
    includes = ["/I", ROOT / "native", "/external:I", ffmpeg_include]
    system = ["ws2_32.lib"] + ffmpeg_libs
    plugin = OUT / "phonecam-obs.dll"
    run(strict + ["/O2", "/LD"] + includes + obs_headers + [ROOT / "native/windows/plugin_win.cpp"] + shared +
        [f"/Fe:{plugin}", f"/Fo:{OUT}\\", "/link", OUT / "obs.lib"] + system, env)
    receiver = runtime / "phonecam-receive.exe"  # next to OBS's FFmpeg DLLs
    run(strict + ["/O2"] + includes + [ROOT / "native/windows/receiver_cli_win.cpp"] + shared + [f"/Fe:{receiver}", f"/Fo:{OUT}\\", "/link"] + system, env)
    fake_adb = OUT / "fake-adb.exe"
    run(strict + [ROOT / "native/windows/fake_adb.cpp", f"/Fe:{fake_adb}", f"/Fo:{OUT}\\"], env)
    native_tests(runtime, receiver, fake_adb)
    smoke = runtime / "phonecam-smoke.exe"  # next to obs.dll so its dependencies load
    run(strict + obs_headers + [ROOT / "native/windows/smoke_win.cpp", f"/Fe:{smoke}", f"/Fo:{OUT}\\", "/link", OUT / "obs.lib"], env)
    run([smoke, plugin], env, cwd=runtime)
    print(json.dumps({"plugin": str(plugin), "physicalGate": "not passed: test build only"}))


if __name__ == "__main__":
    main()
