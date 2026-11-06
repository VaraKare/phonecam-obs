#!/usr/bin/env python3
"""Synthetic transport/VideoToolbox integration; never establishes a physical-device gate."""
from pathlib import Path
import json
import re
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
RECEIVER = ROOT / "native/build/phonecam-receive"


def packet(kind, body, flags=0, sequence=0, pts=0):
    return struct.pack(">4sHHIIQQ", b"PCAM", 1, kind, len(body), flags, sequence, pts) + body


@unittest.skipUnless(RECEIVER.exists() and shutil.which("ffmpeg"), "Build the native receiver and install ffmpeg first")
class NativeIntegration(unittest.TestCase):
    def exchange(self, packets, expected, success=True, token="a" * 64):
        with tempfile.TemporaryDirectory() as directory, socket.socket() as server:
            server.bind(("127.0.0.1", 0)); server.listen(1); server.settimeout(10)
            config = Path(directory) / "live.json"
            config.write_text(json.dumps({"port": server.getsockname()[1], "token": token}))
            authorized = []

            def sender():
                try:
                    with server.accept()[0] as client:
                        auth = b""
                        while len(auth) < 65:
                            value = client.recv(65 - len(auth))
                            if not value: break
                            auth += value
                        authorized.append(auth == (token + "\n").encode())
                        for data in packets: client.sendall(data)
                except (BrokenPipeError, ConnectionResetError): pass

            worker = threading.Thread(target=sender, daemon=True); worker.start()
            result = subprocess.run([str(RECEIVER), str(config), str(expected)], capture_output=True, text=True, timeout=20)
            worker.join(10)
            self.assertFalse(worker.is_alive())
            self.assertEqual(authorized, [True])
            if success:
                self.assertEqual(result.returncode, 0, result.stderr)
                value = json.loads(result.stdout)
                self.assertEqual(value["decodedFrames"], expected)
                self.assertTrue(value["hardwareDecode"])
                self.assertEqual(value["opticalLatency"], "not_measured")
            else:
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Receive failed", result.stderr)
            return result

    def encoded_packets(self, hevc=False, skip=None, width=320, height=240):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / ("clip.hevc" if hevc else "clip.h264")
            command = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", f"testsrc2=size={width}x{height}:rate=30",
                       "-frames:v", "12", "-c:v", "libx265" if hevc else "libx264", "-preset", "ultrafast", "-bf", "0"]
            if hevc:
                command += ["-x265-params", "pools=1:keyint=5:min-keyint=5:scenecut=0:aud=1:repeat-headers=1:log-level=error"]
            else: command += ["-tune", "zerolatency", "-x264-params", "keyint=5:min-keyint=5:scenecut=0:aud=1:repeat-headers=1"]
            subprocess.run(command + [str(output)], check=True, capture_output=True, timeout=30)
            nals = [part.rstrip(b"\0") for part in re.split(b"\x00\x00(?:\x00)?\x01", output.read_bytes()) if part]
        def kind(n): return (n[0] >> 1) & 63 if hevc else n[0] & 31
        config_types = (32, 33, 34) if hevc else (7, 8)
        config = b"".join(b"\0\0\0\1" + next(n for n in nals if kind(n) == t) for t in config_types)
        packets = [packet(1, struct.pack(">IIII", 2 if hevc else 1, width, height, 0) + config)]
        groups = []
        for n in nals:
            if kind(n) == (35 if hevc else 9): groups.append([])
            if not groups: groups.append([])
            groups[-1].append(n)
        self.assertEqual(len(groups), 12)
        for seq, group in enumerate(groups):
            if seq == skip: continue
            key = any(16 <= kind(n) <= 21 if hevc else kind(n) == 5 for n in group)
            payload = b"".join(b"\0\0\0\1" + n for n in group)
            packets.append(packet(2, payload, int(key), seq, seq * 33333))
        return packets

    def test_avc_hardware_decode(self): self.exchange(self.encoded_packets(width=1920, height=1080), 12)
    def test_hevc_hardware_decode(self): self.exchange(self.encoded_packets(True, width=1920, height=1080), 12)
    def test_sequence_gap_waits_for_keyframe(self): self.exchange(self.encoded_packets(skip=2), 9)
    def test_malformed_header_rejected(self):
        self.exchange([struct.pack(">4sHHIIQQ", b"PCAM", 1, 2, 0xffffffff, 0, 0, 0)], 1, False)
    def test_keyflag_mismatch_rejected(self):
        packets = self.encoded_packets()
        # A dependent P-frame falsely claims to be a keyframe.
        bad = bytearray(packets[2]); bad[15] = 1
        self.exchange([packets[0], packets[1], bytes(bad)], 12, False)


if __name__ == "__main__": unittest.main()
