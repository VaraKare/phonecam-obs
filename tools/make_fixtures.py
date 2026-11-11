#!/usr/bin/env python3
"""Writes the synthetic PCAM v3 streams in native/tests/fixtures (ffmpeg test pattern, no camera footage).

Each file is the exact message sequence a phone sends: configuration, then 12 frames. The Windows CI
decodes them, because the Windows test machine has no ffmpeg; the macOS tests generate the same
streams on the fly (tools/test_native.py).
"""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_native import NativeIntegration  # noqa: E402

OUT = Path(__file__).resolve().parents[1] / "native/tests/fixtures"
CASES = {
    "avc-320x240.pcam": dict(hevc=False),
    "hevc-320x240.pcam": dict(hevc=True),
    "hevc-portrait-mirror.pcam": dict(hevc=True, turns=lambda seq: 1, mirror=True),
}

if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    maker = NativeIntegration("test_avc_hardware_decode")
    for name, options in CASES.items():
        data = b"".join(maker.encoded_packets(**options))
        (OUT / name).write_bytes(data)
        print(f"{name}: {len(data)} bytes")
