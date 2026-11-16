#!/usr/bin/env python3
"""Portable Windows test kit: test a CI build of the Windows plugin on a borrowed PC without installing anything.

    python3 tools/windows_kit.py build --run RUN_ID [--wifi]     # or --artifact DIR (an unzipped CI artifact)
    python3 tools/windows_kit.py serve                            # prints the one line to type on the PC

The kit is official OBS 33.0.1 in portable mode, Google's platform-tools and the plugin, in one zip. On the PC
one PowerShell line downloads it from this computer over the local network, runs it from a Desktop folder,
sends the PhoneCam log lines back here and deletes everything when OBS closes (tools/windows_kit.ps1).
Downloads are pinned by SHA-256. Wi-Fi is off unless --wifi: answering Windows Firewall's prompt (even with
Cancel) leaves a firewall entry that only an administrator can remove. Steps: docs/WINDOWS_TEST.md.
"""
from pathlib import Path
import argparse
import http.server
import json
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_native import BUILD, ROOT  # noqa: E402
from build_windows import OBS_ZIP, fetch  # noqa: E402

PLATFORM_TOOLS = ("platform-tools_r37.0.1-win.zip",  # Google's SHA-1 e03e78b1d80b396f1c3358e31251cb31740e1110
                  "https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip",
                  "45f4d63113e895ebde0c90f194099a4676b6ac653bd28d54314a9e022bbc1a99")
KIT = BUILD / "windows-kit"
TEMPLATE = Path(__file__).resolve().parent / "windows_kit.ps1"
TOKEN_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"  # short and easy to type: no 0/o, 1/l/i


def scene(wifi):
    """One scene with the PhoneCam source, visible, Wi-Fi as chosen."""
    scene_id, source_id = str(uuid.uuid4()), str(uuid.uuid4())
    item = {"name": "PhoneCam", "source_uuid": source_id, "visible": True, "locked": False, "id": 1, "rot": 0.0,
            "pos": {"x": 0.0, "y": 0.0}, "scale": {"x": 1.0, "y": 1.0}, "align": 5, "bounds_type": 0, "bounds_align": 0,
            "bounds": {"x": 0.0, "y": 0.0}, "crop_left": 0, "crop_top": 0, "crop_right": 0, "crop_bottom": 0}
    return {
        "name": "PhoneCam test", "current_scene": "PhoneCam test", "current_program_scene": "PhoneCam test",
        "scene_order": [{"name": "PhoneCam test"}], "groups": [], "quick_transitions": [], "transitions": [],
        "saved_projectors": [], "current_transition": "Fade", "transition_duration": 300, "preview_locked": False,
        "scaling_enabled": False, "modules": {}, "version": 2,
        "sources": [
            {"id": "scene", "versioned_id": "scene", "name": "PhoneCam test", "uuid": scene_id, "enabled": True, "mixers": 0,
             "settings": {"id_counter": 1, "custom_size": False, "items": [item]}},
            {"id": "phonecam_usb_v1", "versioned_id": "phonecam_usb_v1", "name": "PhoneCam", "uuid": source_id, "enabled": True,
             "settings": {"wifi_direct": wifi}},
        ],
    }


def safe(name):
    return not name.endswith("/") and not name.startswith(("/", "\\")) and ".." not in Path(name.replace("\\", "/")).parts


def build_kit(artifact, obs_zip, tools_zip, out, wifi):
    """Writes the kit zip: OBS/ (portable, scene ready, plugin, C++ runtime) and platform-tools/."""
    plugin = next(artifact.rglob("phonecam-obs.dll"), None)
    if not plugin: raise SystemExit(f"No phonecam-obs.dll in {artifact}")
    files = {
        "OBS/portable_mode.txt": b"PhoneCam test kit: OBS keeps all its settings inside this folder.\r\n",
        "OBS/config/obs-studio/global.ini": b"[General]\r\nLastVersion=553648129\r\n",   # 33.0.1: no "what's new"
        "OBS/config/obs-studio/user.ini": b"[General]\r\nFirstRun=true\r\n\r\n[Basic]\r\nSceneCollection=PhoneCam test\r\nSceneCollectionFile=PhoneCam_test\r\n",
        "OBS/config/obs-studio/basic/scenes/PhoneCam_test.json": json.dumps(scene(wifi), indent=1).encode(),
        "OBS/plugins/phonecam-obs/phonecam-obs.dll": plugin.read_bytes(),
        "OBS/plugins/phonecam-obs/data/README.txt": b"PhoneCam OBS test build.\r\n",
    }
    notices = next(artifact.rglob("THIRD_PARTY_NOTICES.txt"), None)
    if notices: files["OBS/plugins/phonecam-obs/THIRD_PARTY_NOTICES.txt"] = notices.read_bytes()
    for dll in sorted(artifact.rglob("vc-runtime/*.dll")):
        files[f"OBS/bin/64bit/{dll.name}"] = dll.read_bytes()   # app-local C++ runtime for PCs without it
    out.parent.mkdir(parents=True, exist_ok=True)
    temporary = out.with_suffix(".partial")
    with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as kit:
        with zipfile.ZipFile(obs_zip) as obs:
            for item in obs.infolist():
                target = "OBS/" + item.filename
                if safe(item.filename) and target not in files: kit.writestr(target, obs.read(item))
        with zipfile.ZipFile(tools_zip) as tools:
            for item in tools.infolist():
                if safe(item.filename) and item.filename.startswith("platform-tools/"): kit.writestr(item.filename, tools.read(item))
        for name, data in files.items(): kit.writestr(name, data)
    temporary.replace(out)
    (out.parent / "kit.json").write_text(json.dumps({"wifi": wifi, "plugin": str(plugin), "built": time.strftime("%Y-%m-%d %H:%M")}))
    return out


def kit_script(url, wifi):
    script = TEMPLATE.read_text(encoding="utf-8").replace("__URL__", url).replace("__WIFI__", "$true" if wifi else "$false")
    assert "__URL__" not in script and "__WIFI__" not in script
    return script


def lan_address():
    """This computer's address on the local network (no packet is sent)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.connect(("10.255.255.255", 1))
        return probe.getsockname()[0]


def make_handler(token, script, kit_zip, reports):
    class Handler(http.server.BaseHTTPRequestHandler):
        def send(self, data, kind):
            self.send_response(200)
            self.send_header("Content-Type", kind); self.send_header("Content-Length", str(len(data)))
            self.end_headers(); self.wfile.write(data)

        def do_GET(self):
            if self.path == f"/{token}": self.send(script.encode(), "text/plain; charset=utf-8")
            elif self.path == f"/{token}/kit.zip": self.send(kit_zip.read_bytes(), "application/zip")
            else: self.send_error(404); return
            print(time.strftime("%H:%M:%S"), "sent", self.path.replace(token, "…"), flush=True)

        def do_POST(self):
            match = re.fullmatch(rf"/{token}/report/([a-z0-9-]{{1,40}})", self.path)
            length = int(self.headers.get("Content-Length", "0") or 0)
            if not match or length > 4 * 1024 * 1024: self.send_error(404); return
            body = self.rfile.read(length)
            (reports / f"{match.group(1)}.txt").write_bytes(body)
            with open(reports / "_events.log", "a") as log: log.write(f"{time.strftime('%H:%M:%S')} {match.group(1)} {len(body)} bytes\n")
            print(time.strftime("%H:%M:%S"), "report", match.group(1), flush=True)
            self.send_response(204); self.end_headers()

        def log_message(self, *args): pass
    return Handler


def serve(port, address=None):
    kit_zip = KIT / "kit.zip"
    if not kit_zip.exists(): raise SystemExit("Build the kit first: python3 tools/windows_kit.py build --run RUN_ID")
    wifi = json.loads((KIT / "kit.json").read_text())["wifi"]
    token = "".join(secrets.choice(TOKEN_ALPHABET) for _ in range(6))
    host = address or lan_address()
    reports = KIT / "reports" / time.strftime("%Y-%m-%d-%H%M")
    reports.mkdir(parents=True, exist_ok=True)
    server = http.server.ThreadingHTTPServer(("0.0.0.0", port), make_handler(token, kit_script(f"http://{host}:{port}/{token}", wifi), kit_zip, reports))
    print(f"Kit ({'with' if wifi else 'without'} Wi-Fi) ready. On the Windows PC, open Windows PowerShell and type:\n")
    print(f"    iex (iwr -UseBasicParsing http://{host}:{port}/{token}).Content\n")
    print(f"Reports: {reports}\nStop with Ctrl+C after the PC reports its cleanup.", flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build")
    source = build.add_mutually_exclusive_group(required=True)
    source.add_argument("--run", help="GitHub Actions run id with the phonecam-obs-windows-test artifact")
    source.add_argument("--artifact", type=Path, help="unzipped CI artifact folder")
    build.add_argument("--wifi", action="store_true", help="turn direct Wi-Fi on (Windows Firewall will ask once)")
    run = commands.add_parser("serve")
    run.add_argument("--port", type=int, default=8765)
    run.add_argument("--address", help="this computer's LAN address, if the automatic one is wrong")
    args = parser.parse_args()
    if args.command == "serve": return serve(args.port, args.address)
    artifact = args.artifact
    if args.run:
        artifact = KIT / "artifact"
        shutil.rmtree(artifact, ignore_errors=True)
        subprocess.run(["gh", "run", "download", args.run, "-n", "phonecam-obs-windows-test", "-D", str(artifact)], check=True, cwd=ROOT)
    out = build_kit(artifact, fetch(*OBS_ZIP), fetch(*PLATFORM_TOOLS), KIT / "kit.zip", args.wifi)
    print(f"{out} ({out.stat().st_size // 1_000_000} MB, Wi-Fi {'on' if args.wifi else 'off'}). Next: python3 tools/windows_kit.py serve")


if __name__ == "__main__":
    main()
