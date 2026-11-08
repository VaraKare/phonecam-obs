#!/usr/bin/env python3
"""Command-line diagnostics for the USB link. The OBS source connects automatically; this is for testing without OBS. Never starts the camera or records media."""
from pathlib import Path
import argparse
import json
import os
import re
import subprocess
import tempfile
from phonecam import select_device, run, ResearchError, ROOT

CONFIG = Path.home() / "Library/Application Support/phonecam-obs/live.json"
PORT = 27183
PAIRING_URI = "content://dev.phonecam.obs.pairing/token"


def prepare(serial=None):
    device = select_device(serial)
    # The app answers this query only for the ADB shell user, in debug and release builds.
    answer = run(device + ["shell", "content", "query", "--uri", PAIRING_URI])
    match = re.fullmatch(r"Row: 0 token=([0-9a-f]{64})", answer.strip())
    if not match:
        raise ResearchError("Install the current PhoneCam app on the phone, then run prepare again")
    token = match.group(1)
    forwards = run([device[0], "forward", "--list"]).splitlines()
    for line in forwards:
        parts = line.split()
        if len(parts) == 3 and parts[1] == f"tcp:{PORT}" and (parts[0] != device[2] or parts[2] != "localabstract:phonecam-live-v1"):
            raise ResearchError("Port 27183 already belongs to a different ADB forward; remove that forward explicitly")
    # --no-rebind protects other forwards; leave an identical existing forward alone.
    if not any(line.split() == [device[2], f"tcp:{PORT}", "localabstract:phonecam-live-v1"] for line in forwards):
        run(device + ["forward", "--no-rebind", f"tcp:{PORT}", "localabstract:phonecam-live-v1"])
    CONFIG.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=".live-", dir=CONFIG.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump({"schemaVersion": 1, "port": PORT, "token": token}, stream)
            stream.write("\n")
        temporary.replace(CONFIG)
    finally:
        temporary.unlink(missing_ok=True)
    print("USB link prepared for the command-line receiver. No camera started. (The OBS source pairs and connects by itself; it does not need this.)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("prepare")
    receive = commands.add_parser("receive", help="Test hardware decode without OBS; tap Start live yourself")
    receive.add_argument("--auto", action="store_true", help="Find, pair and forward automatically, like the OBS source (no prepare needed)")
    receive.add_argument("--frames", type=int, default=300)
    args = parser.parse_args()
    try:
        if args.command == "prepare": prepare(args.serial)
        else:
            if not 1 <= args.frames <= 216000: parser.error("frames must be 1..216000")
            binary = str(ROOT / "native/build/phonecam-receive")
            command = [binary, "--auto", str(args.frames)] if args.auto else [binary, str(CONFIG), str(args.frames)]
            raise SystemExit(subprocess.call(command))
    except (ResearchError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, f"USB setup failed: {error}\n")


if __name__ == "__main__":
    main()
