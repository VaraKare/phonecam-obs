#!/usr/bin/env python3
"""Prepare an ADB-only connection. Never starts the camera or records media."""
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


def prepare(serial=None):
    device = select_device(serial)
    # The debug probe creates this private token when its foreground activity opens.
    token = run(device + ["shell", "run-as", "dev.phonecam.obs", "cat", "files/live-token"])
    if not re.fullmatch("[0-9a-f]{64}", token):
        raise ResearchError("Open the updated PhoneCam Probe once, then run prepare again")
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
    print("USB forwarding prepared. No camera started. Add PhoneCam USB (Research) in OBS, then tap Start USB live on the phone.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("prepare")
    receive = commands.add_parser("receive", help="Test hardware decode without OBS; tap Start USB live yourself")
    receive.add_argument("--frames", type=int, default=300)
    args = parser.parse_args()
    try:
        if args.command == "prepare": prepare(args.serial)
        else:
            if not 1 <= args.frames <= 216000: parser.error("frames must be 1..216000")
            raise SystemExit(subprocess.call([str(ROOT / "native/build/phonecam-receive"), str(CONFIG), str(args.frames)]))
    except (ResearchError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, f"USB setup failed: {error}\n")


if __name__ == "__main__":
    main()
