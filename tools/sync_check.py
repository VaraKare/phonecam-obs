#!/usr/bin/env python3
"""Clap test for sound-and-picture sync in an OBS recording (PhoneCam microphone, v0.6+).

Record a few seconds in OBS with the phone camera on your hands and the phone microphone on, clapping 3-5 times
about a second apart. Then:

  python3 tools/sync_check.py RECORDING.mkv [--out DIR]

For each clap the script finds the sound's peak and exports the video frames from 0.3 s before to 0.3 s after it,
named by their time relative to the sound (for example `clap1_-033ms.jpg`). It also prints how much each frame
changed from the one before: the hands move fast until they meet and then stop, so the frame where the change
drops is about when they touched. Offset = that frame's time minus the sound's time (positive: the picture is late).

Needs ffmpeg and ffprobe. Standard library only. Nothing is uploaded; the frames stay in DIR (default: next to the
recording), and recordings and frames stay out of Git.
"""
from pathlib import Path
import argparse
import array
import shutil
import subprocess
import sys

RATE = 8000  # enough to find a clap, small to scan


def audio_peaks(recording: Path, count: int = 8, gap_s: float = 0.4):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(recording), "-vn", "-ac", "1", "-ar", str(RATE),
                          "-f", "s16le", "-"], capture_output=True, check=True).stdout
    samples = array.array("h"); samples.frombytes(raw[: len(raw) // 2 * 2])
    if sys.byteorder != "little": samples.byteswap()
    if not samples: raise SystemExit("The recording has no sound track. Check the PhoneCam source's audio tracks in OBS.")
    window = RATE // 200  # 5 ms energy windows
    energy = [max(abs(v) for v in samples[i:i + window]) for i in range(0, len(samples) - window, window)]
    loudest = max(energy) or 1
    peaks = []
    for i, e in sorted(enumerate(energy), key=lambda item: -item[1]):
        if e < loudest * 0.35 or len(peaks) >= count: break
        t = i * window / RATE
        if all(abs(t - p) > gap_s for p in peaks): peaks.append(t)
    return sorted(peaks)


def video_fps(recording: Path) -> float:
    rate = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=avg_frame_rate",
                           "-of", "default=nw=1:nk=1", str(recording)], capture_output=True, text=True, check=True).stdout.strip()
    num, _, den = rate.partition("/")
    return float(num) / float(den or 1)


def frames_around(recording: Path, t: float, fps: float, out: Path, label: str):
    start = max(0.0, t - 0.3)
    pattern = out / f"{label}_%03d.jpg"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{start:.3f}", "-i", str(recording), "-t", "0.6",
                    "-vf", "scale=480:-2", "-q:v", "4", str(pattern)], check=True)
    gray = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{start:.3f}", "-i", str(recording), "-t", "0.6",
                           "-vf", "scale=96:-2,format=gray", "-f", "rawvideo", "-"], capture_output=True, check=True).stdout
    files = sorted(out.glob(f"{label}_*.jpg"))
    size = len(gray) // max(1, len(files))
    rows, previous = [], None
    for n, file in enumerate(files):
        offset_ms = round((start + n / fps - t) * 1000)
        frame = gray[n * size:(n + 1) * size]
        change = (sum(abs(a - b) for a, b in zip(frame, previous)) / max(1, len(frame))) if previous else 0.0
        previous = frame
        named = out / f"{label}_{offset_ms:+04d}ms.jpg"
        file.rename(named)
        rows.append((offset_ms, change, named.name))
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("recording", type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    for tool in ("ffmpeg", "ffprobe"):
        if not shutil.which(tool): raise SystemExit(f"{tool} not found. Install it, for example: brew install ffmpeg")
    out = args.out or args.recording.with_name(args.recording.stem + "-sync")
    out.mkdir(parents=True, exist_ok=True)
    fps = video_fps(args.recording)
    peaks = audio_peaks(args.recording)
    if not peaks: raise SystemExit("No clap found in the sound.")
    print(f"{len(peaks)} claps found; video {fps:.2f} fps. Frames in {out}")
    for k, t in enumerate(peaks, 1):
        print(f"\nClap {k}: sound at {t:.3f} s. Frame change (hands moving → stop at contact):")
        for offset_ms, change, name in frames_around(args.recording, t, fps, out, f"clap{k}"):
            print(f"  {offset_ms:+5d} ms  change {change:5.1f}  {'#' * int(change / 2)}  {name}")
    print("\nLook at the frames where the change drops after the big movement: that frame's time is the picture's clap."
          "\nOffset = that time (positive: picture late). Under 33 ms is within one frame.")


if __name__ == "__main__":
    main()
