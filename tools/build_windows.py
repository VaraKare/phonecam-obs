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
OUT = BUILD / "windows"


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
    runtime = obs_runtime(env)
    obs_headers = ["/external:I", sdk, "/external:I", BUILD / "simde"]
    plugin = OUT / "phonecam-obs.dll"
    run(strict + ["/O2", "/LD", "/I", ROOT / "native"] + obs_headers + [ROOT / "native/windows/plugin_win.cpp", f"/Fe:{plugin}", f"/Fo:{OUT}\\",
         "/link", OUT / "obs.lib"], env)
    smoke = runtime / "phonecam-smoke.exe"  # next to obs.dll so its dependencies load
    run(strict + obs_headers + [ROOT / "native/windows/smoke_win.cpp", f"/Fe:{smoke}", f"/Fo:{OUT}\\", "/link", OUT / "obs.lib"], env)
    run([smoke, plugin], env, cwd=runtime)
    print(json.dumps({"plugin": str(plugin), "physicalGate": "not passed: test build only"}))


if __name__ == "__main__":
    main()
