#!/usr/bin/env python3
"""Local-only research workflow. Python 3.10+, standard library only."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import secrets
import shlex
import shutil
import subprocess
import sys
import tarfile
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.phonecam.obs"
SCENES = ("well_lit_detail", "indoor_face", "low_light_motion", "backlit_subject")
SOURCES = ("stock", "scrcpy", "probe")
MAX_COLLECTION_BYTES = 8 * 1024**3


class ResearchError(Exception):
    pass


def load_json(path: Path):
    if path.stat().st_size > 4 * 1024**2:
        raise ResearchError(f"JSON report too large: {path}")
    value = json.loads(path.read_text(), parse_constant=lambda value: (_ for _ in ()).throw(ResearchError(f"Non-finite JSON value: {value}")))
    if not isinstance(value, dict):
        raise ResearchError(f"Expected JSON object: {path}")
    return value


def write_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")
    temp.replace(path)


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def run(command: list[str], timeout=30) -> str:
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=False)
    if result.returncode:
        raise ResearchError(f"{command[0]} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout.strip()


def adb_executable() -> str:
    executable = os.environ.get("ADB") or shutil.which("adb")
    if not executable:
        raise ResearchError("ADB not found. Install Android platform-tools and add adb to PATH; see docs/ANDROID_SETUP.md.")
    return executable


def connected_devices(adb: str) -> list[tuple[str, str]]:
    output = run([adb, "devices"])
    return [(parts[0], parts[1]) for line in output.splitlines()[1:] if len(parts := line.split()) >= 2]


def select_device(serial: str | None) -> list[str]:
    adb = adb_executable()
    devices = connected_devices(adb)
    selected = serial or os.environ.get("ANDROID_SERIAL")
    if selected:
        status = dict(devices).get(selected)
        if status != "device":
            raise ResearchError(f"Selected device is {status or 'not connected'}. Unlock it and authorize USB debugging.")
    else:
        ready = [identity for identity, state in devices if state == "device"]
        if len(ready) != 1:
            raise ResearchError("Connect exactly one authorized phone, or pass --serial SERIAL before the command. Check the phone's USB debugging prompt.")
        selected = ready[0]
    return [adb, "-s", selected]


def doctor(args):
    adb = adb_executable()
    devices = connected_devices(adb)
    summary = {"schemaVersion": 1, "authorizedDevices": sum(state == "device" for _, state in devices),
               "unauthorizedDevices": sum(state == "unauthorized" for _, state in devices),
               "offlineDevices": sum(state == "offline" for _, state in devices),
               "adbVersion": run([adb, "version"]).splitlines()[0],
               "ffprobeAvailable": shutil.which("ffprobe") is not None,
               "status": "needs_device", "qualityStatus": "not_measured", "opticalLatencyStatus": "not_measured"}
    try:
        command = select_device(args.serial)
        properties = {key: run(command + ["shell", "getprop", prop]) for key, prop in (
            ("manufacturer", "ro.product.manufacturer"), ("model", "ro.product.model"),
            ("android", "ro.build.version.release"), ("api", "ro.build.version.sdk"))}
        summary["device"] = properties
        summary["status"] = "ready_for_probe" if int(properties["api"]) >= 29 else "unsupported_android_version"
    except ResearchError as error:
        summary["guidance"] = str(error)
    print(json.dumps(summary, indent=2))
    return 0 if summary["status"] == "ready_for_probe" else 2


def install(args):
    apk = args.apk.resolve()
    if not apk.is_file():
        raise ResearchError(f"APK missing: {apk}. Build with ./gradlew assembleDebug first.")
    print(run(select_device(args.serial) + ["install", "-r", str(apk)], timeout=120))
    print("Open PhoneCam on the phone and allow camera access. Live capture starts only when you tap Start live.")
    return 0


def extract_benchmarks(stream, destination: Path, max_bytes=MAX_COLLECTION_BYTES) -> int:
    """Stream ADB tar without whole-clip buffering or tar path/link extraction."""
    total = 0
    count = 0
    with tarfile.open(fileobj=stream, mode="r|") as archive:
        for member in archive:
            parts = PurePosixPath(member.name).parts
            if member.name.startswith("/") or ".." in parts or parts[:2] != ("files", "benchmarks"):
                raise ResearchError("Unexpected path in phone archive")
            if member.isdir():
                continue
            if not member.isfile() or len(parts) != 3 or member.size < 0:
                raise ResearchError("Phone archive must contain flat regular benchmark files only")
            if count >= 10_000:
                raise ResearchError("Phone archive contains too many files")
            total += member.size
            if total > max_bytes:
                raise ResearchError("Collection exceeds byte budget; collect fewer recordings")
            target = destination / parts[-1]
            if target.suffix not in (".json", ".mp4"):
                raise ResearchError("Unexpected benchmark file type")
            source = archive.extractfile(member)
            if source is None:
                raise ResearchError("Missing archive member data")
            temporary = target.with_name(target.name + ".collecting")
            try:
                with temporary.open("xb") as output:
                    shutil.copyfileobj(source, output, length=1024 * 1024)
                if temporary.stat().st_size != member.size or target.exists():
                    raise ResearchError("Truncated or duplicate collection entry")
                temporary.rename(target)
            except Exception:
                temporary.unlink(missing_ok=True)
                raise
            count += 1
    return count


def collect(args):
    command = select_device(args.serial)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(3)
    destination = args.out.resolve() / stamp
    destination.mkdir(parents=True, exist_ok=False)
    write_json(destination / "collection.json", {"complete": False})
    process = subprocess.Popen(command + ["exec-out", "run-as", PACKAGE, "tar", "-cf", "-", "files/benchmarks"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        count = extract_benchmarks(process.stdout, destination)
        _, error = process.communicate(timeout=30)
        if process.returncode:
            raise ResearchError(error.decode(errors="replace") or "ADB collection failed. Is the debug app installed and has Scan run?")
    except Exception:
        process.kill(); process.communicate()
        raise
    write_json(destination / "collection.json", {"complete": True, "files": count, "createdAtUtc": stamp})
    print(f"Copied {count} files to {destination}. Phone files are unchanged. Do not publish raw recordings without reviewing them.")
    return 0


def baseline(args):
    output = args.out.resolve()
    codec = "h264" if args.codec == "h264" else "h265"
    command = ["scrcpy"]
    if args.serial:
        command += ["--serial", args.serial]
    command += ["--video-source=camera", f"--camera-id={args.camera}", f"--camera-size={args.size}",
                "--camera-fps=30", f"--video-codec={codec}", f"--video-bit-rate={args.bitrate}M",
                "--video-codec-options=max-bframes=0,i-frame-interval=1", "--no-audio", "--no-playback",
                "--time-limit=15", f"--record={output}"]
    print("Printed only; no camera or recording has been started. Direct camera capture requires Android 12+. Match the probe's actual lens/size/bitrate; stock app mirroring is a separate baseline.")
    print(shlex.join(command))
    return 0


def resolve_entry_path(manifest: Path, value) -> Path:
    if not isinstance(value, str) or not value:
        raise ResearchError("Each entry needs a file path")
    path = Path(value).expanduser()
    return (manifest.parent / path).resolve() if not path.is_absolute() else path.resolve()


def inspect_media(path: Path) -> dict:
    executable = shutil.which("ffprobe")
    if not executable:
        raise ResearchError("ffprobe is required to validate review clips. Install FFmpeg; see docs/BENCHMARKS.md.")
    output = run([executable, "-v", "error", "-select_streams", "v:0", "-count_frames",
                  "-show_entries", "stream=codec_name,width,height,avg_frame_rate,nb_read_frames,duration,color_space,color_transfer,color_range",
                  "-of", "json", str(path)], timeout=180)
    streams = json.loads(output).get("streams", [])
    if len(streams) != 1:
        raise ResearchError(f"No readable video stream: {path}")
    stream = streams[0]
    try:
        frames = int(stream["nb_read_frames"])
        duration = float(stream["duration"])
        numerator, denominator = stream["avg_frame_rate"].split("/")
        fps = int(numerator) / int(denominator)
        if frames < 1 or not number(duration) or duration <= 0 or not number(fps):
            raise ValueError
    except (ValueError, KeyError, ZeroDivisionError):
        raise ResearchError(f"Incomplete video timing in {path}") from None
    return {**stream, "frames": frames, "durationSeconds": duration, "fps": fps}


def create_review(args):
    manifest = args.manifest.resolve()
    data = load_json(manifest)
    entries = data.get("clips", [])
    if data.get("schemaVersion") != 1 or data.get("matchedConditionsConfirmed") is not True:
        raise ResearchError("Confirm matched lens/framing/resolution/frame rate/lighting in the version-1 manifest first")
    if not isinstance(entries, list) or not entries or len(entries) > 100:
        raise ResearchError("Manifest needs 1–100 clip entries")
    seen_hashes = set()
    seen_slots = set()
    validated = []
    for entry in entries:
        if not isinstance(entry, dict):
            raise ResearchError("Every clip entry must be an object")
        source, scene, repeat = entry.get("source"), entry.get("scene"), entry.get("repeat")
        if source not in SOURCES or scene not in SCENES or type(repeat) is not int or repeat not in (1, 2, 3):
            raise ResearchError("Invalid source, scene or repeat in manifest")
        slot = (source, scene, repeat)
        if slot in seen_slots:
            raise ResearchError("Duplicate source/scene/repeat slot")
        seen_slots.add(slot)
        media = resolve_entry_path(manifest, entry.get("file"))
        if not media.is_file() or media.suffix.lower() != ".mp4" or media.stat().st_size == 0:
            raise ResearchError(f"Missing/non-MP4/empty clip: {media}")
        sha = digest(media)
        if sha in seen_hashes:
            raise ResearchError("Duplicate media bytes: repetitions must be independently recorded")
        seen_hashes.add(sha)
        actual_media = inspect_media(media)
        metadata = None
        if source == "probe":
            sidecar = resolve_entry_path(manifest, entry.get("sidecar"))
            metadata = load_json(sidecar)
            if metadata.get("scene") != scene or metadata.get("mediaFile") != media.name:
                raise ResearchError("Probe clip does not match its scene/sidecar filename")
            requested, encoded = metadata.get("requested", {}), metadata.get("encoded", {})
            expected_codec = {"video/avc": "h264", "video/hevc": "hevc"}.get(requested.get("mime"))
            if (metadata.get("success") is not True or expected_codec is None or
                    actual_media.get("codec_name") != expected_codec or
                    actual_media.get("width") != requested.get("width") or
                    actual_media.get("height") != requested.get("height") or
                    actual_media["frames"] != encoded.get("frames")):
                raise ResearchError("Actual MP4 does not match the successful probe sidecar")
        validated.append({"source": source, "scene": scene, "repeat": repeat, "original": str(media),
                          "sha256": sha, "metadata": metadata, "actualMedia": actual_media})
    # Baselines are required for each scene; probe winner needs three independent repeats.
    required = {(source, scene, repeat) for source in SOURCES for scene in SCENES
                for repeat in ((1, 2, 3) if source == "probe" else (1,))}
    if not required.issubset(seen_slots):
        raise ResearchError("Need stock and scrcpy baseline for every scene plus three probe repeats per scene")
    profiles = {v["metadata"].get("profileId") for v in validated if v["source"] == "probe"}
    if len(profiles) != 1 or not next(iter(profiles)):
        raise ResearchError("Review one winning probe profile at a time")
    probe_media = next(v["actualMedia"] for v in validated if v["source"] == "probe")
    if any(v["actualMedia"].get("width") != probe_media["width"] or
           v["actualMedia"].get("height") != probe_media["height"] or
           not 29.7 <= v["actualMedia"]["fps"] <= 30.3 for v in validated):
        raise ResearchError("Baseline and candidate videos must match resolution and actual 30 fps")
    destination = args.out.resolve()
    destination.mkdir(parents=True, exist_ok=False)
    (destination / "clips").mkdir()
    secrets.SystemRandom().shuffle(validated)
    mapping = {}
    try:
        with (destination / "ratings.csv").open("w", newline="") as stream:
            writer = csv.writer(stream)
            writer.writerow(["clip_id", "score", "acceptable_focus", "acceptable_motion", "no_severe_artifacts", "notes"])
            for index, entry in enumerate(validated, 1):
                identity = f"clip-{index:03}"
                target = destination / "clips" / f"{identity}.mp4"
                shutil.copyfile(entry["original"], target)
                mapping[identity] = entry
                writer.writerow([identity, "", "", "", "", ""])
        write_json(destination / "PRIVATE_sources.json", {"schemaVersion": 1, "matchedConditionsConfirmed": True, "clips": mapping})
    except Exception:
        (destination / "INCOMPLETE").write_text("Review creation failed; do not evaluate.\n")
        raise
    print(f"Created blind review at {destination}. Deliberate media copies were made. Score clips before opening PRIVATE_sources.json. Container metadata may still identify encoders.")
    return 0


def number(value) -> bool:
    return type(value) in (int, float) and math.isfinite(value)


def quality_decision(mapping: dict, ratings: list[dict], hash_check=None) -> dict:
    failures, missing = [], []
    rows = {}
    for row in ratings:
        identity = row.get("clip_id")
        if identity in rows or identity not in mapping:
            raise ResearchError("Duplicate or unknown clip ID in ratings")
        rows[identity] = row
    profiles, slots = set(), set()
    for identity, entry in mapping.items():
        if entry.get("source") not in SOURCES or entry.get("scene") not in SCENES or entry.get("repeat") not in (1, 2, 3):
            raise ResearchError("Invalid private mapping entry")
        slot = (entry["source"], entry["scene"], entry["repeat"])
        if slot in slots:
            raise ResearchError("Duplicate recording slot")
        slots.add(slot)
        if hash_check is not None and not hash_check(identity, entry):
            failures.append(f"{identity}: review media changed or missing")
        if entry["source"] != "probe":
            continue
        metadata = entry.get("metadata") or {}
        if not isinstance(metadata, dict):
            raise ResearchError("Probe metadata must be an object")
        profile = metadata.get("profileId")
        if not isinstance(profile, str) or len(profile) != 64:
            missing.append(f"{identity}: missing stable profile identity")
        profiles.add(profile)
        if metadata.get("success") is not True or metadata.get("source") != "probe" or metadata.get("stopReason") != "duration":
            failures.append(f"{identity}: incomplete/interrupted probe recording")
        encoded = metadata.get("encoded", {})
        if not isinstance(encoded, dict):
            raise ResearchError("Encoded statistics must be an object")
        for field in ("fps", "durationUs", "frames", "maxGapUs", "nonMonotonicPts"):
            if not number(encoded.get(field)):
                missing.append(f"{identity}: missing/non-finite encoded {field}")
        if number(encoded.get("fps")) and not 29.7 <= encoded["fps"] <= 30.3:
            failures.append(f"{identity}: actual frame rate outside 1% of 30 fps")
        if number(encoded.get("durationUs")) and encoded["durationUs"] < 10_000_000:
            failures.append(f"{identity}: less than 10 seconds of encoded video")
        if number(encoded.get("frames")) and encoded["frames"] < 298:
            failures.append(f"{identity}: insufficient encoded frames")
        if all(number(encoded.get(k)) for k in ("frames", "durationUs", "fps")) and encoded["durationUs"] > 0:
            calculated = (encoded["frames"] - 1) * 1_000_000 / encoded["durationUs"]
            if abs(calculated - encoded["fps"]) > 0.01:
                failures.append(f"{identity}: inconsistent frame timing counters")
        if number(encoded.get("maxGapUs")) and encoded["maxGapUs"] > 66_667:
            failures.append(f"{identity}: frame gap exceeds two target frame intervals")
        if number(encoded.get("nonMonotonicPts")) and encoded["nonMonotonicPts"] != 0:
            failures.append(f"{identity}: non-monotonic timestamps")
        requested, observed = metadata.get("requested", {}), metadata.get("observedCameraLast", {})
        if not isinstance(requested, dict) or not isinstance(observed, dict):
            raise ResearchError("Requested/observed camera settings must be objects")
        for key in ("edgeMode", "noiseMode"):
            if requested.get(key) is not None and observed.get(key) != requested[key]:
                missing.append(f"{identity}: applied {key} not confirmed")
        for key in ("processingMismatchFrames", "processingUnconfirmedFrames"):
            count = metadata.get(key)
            if not number(count) or count < 0:
                missing.append(f"{identity}: missing processing-observation counters")
            elif count > 0:
                missing.append(f"{identity}: processing was changed or unconfirmed during capture")
        row = rows.get(identity, {})
        try:
            score = float(row["score"])
            if not math.isfinite(score) or not 1 <= score <= 5:
                raise ValueError
            if score < 4:
                failures.append(f"{identity}: blind quality score below 4/5")
        except (ValueError, TypeError, KeyError):
            missing.append(f"{identity}: missing/invalid blind score")
        for key in ("acceptable_focus", "acceptable_motion", "no_severe_artifacts"):
            value = str(row.get(key, "")).strip().lower()
            if value == "no":
                failures.append(f"{identity}: {key} failed")
            elif value != "yes":
                missing.append(f"{identity}: {key} needs yes/no")
    required = {(source, scene, repeat) for source in SOURCES for scene in SCENES
                for repeat in ((1, 2, 3) if source == "probe" else (1,))}
    if not required.issubset(slots):
        missing.append("Need four scenes, three probe repeats, and matched stock/scrcpy baselines")
    if len(profiles) != 1:
        failures.append("Probe samples use different profiles")
    status = "fail" if failures else "inconclusive" if missing else "pass"
    return {"schemaVersion": 1, "qualityStatus": status, "failures": failures, "missingEvidence": missing,
            "profileId": next(iter(profiles)) if len(profiles) == 1 else None,
            "opticalLatencyStatus": "not_measured", "overallFeasibility": "inconclusive",
            "nextStep": "USB-to-OBS latency research may begin" if status == "pass" else "Do not advance; resolve evidence or use the one bounded tuning cycle",
            "verifiedDevice": False, "notes": "A human-scored quality gate is not optical latency or full-product qualification."}


def evaluate(args):
    review = args.review.resolve()
    if (review / "INCOMPLETE").exists():
        raise ResearchError("Review initialization is incomplete")
    key = load_json(review / "PRIVATE_sources.json")
    if key.get("schemaVersion") != 1 or key.get("matchedConditionsConfirmed") is not True:
        raise ResearchError("Missing matched-condition confirmation")
    mapping = key.get("clips", {})
    if not isinstance(mapping, dict) or not mapping or len(mapping) > 100:
        raise ResearchError("Invalid private mapping")
    if any(not identity.startswith("clip-") or not identity[5:].isdigit() for identity in mapping):
        raise ResearchError("Invalid blind clip ID")
    with (review / "ratings.csv").open(newline="") as stream:
        rows = list(csv.DictReader(stream))
    def check(identity, entry):
        path = review / "clips" / f"{identity}.mp4"
        return path.is_file() and digest(path) == entry.get("sha256")
    result = quality_decision(mapping, rows, check)
    write_json(args.out, result)
    print(json.dumps(result, indent=2))
    return 0 if result["qualityStatus"] == "pass" else 2


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", help="ADB device serial when multiple phones are connected; never included in reports")
    commands = p.add_subparsers(dest="command", required=True)
    commands.add_parser("doctor", help="Read-only tooling, USB authorization and Android version checks").set_defaults(func=doctor)
    install_p = commands.add_parser("install", help="Explicitly install/replace the research APK")
    install_p.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    install_p.set_defaults(func=install)
    collect_p = commands.add_parser("collect", help="Copy private app benchmark files through debug run-as")
    collect_p.add_argument("--out", type=Path, default=ROOT / "benchmarks/raw")
    collect_p.set_defaults(func=collect)
    baseline_p = commands.add_parser("baseline-command", help="Print, never execute, a matched scrcpy recording command")
    baseline_p.add_argument("--camera", default="0")
    baseline_p.add_argument("--size", choices=("3840x2160", "1920x1080", "1280x720"), default="1920x1080")
    baseline_p.add_argument("--codec", choices=("h264", "h265"), default="h264")
    baseline_p.add_argument("--bitrate", type=int, choices=(3, 5, 10, 20, 40, 60), default=10)
    baseline_p.add_argument("--out", type=Path, default=ROOT / "benchmarks/raw/scrcpy-baseline.mp4")
    baseline_p.set_defaults(func=baseline)
    review_p = commands.add_parser("review-init", help="Make deliberate blind media copies and an empty scoring sheet")
    review_p.add_argument("manifest", type=Path)
    review_p.add_argument("--out", required=True, type=Path)
    review_p.set_defaults(func=create_review)
    evaluate_p = commands.add_parser("evaluate", help="Evaluate completed quality evidence; never assert optical latency")
    evaluate_p.add_argument("review", type=Path)
    evaluate_p.add_argument("--out", type=Path, required=True)
    evaluate_p.set_defaults(func=evaluate)
    return p


def main():
    try:
        args = parser().parse_args()
        return args.func(args)
    except (ResearchError, OSError, ValueError, KeyError, TypeError, tarfile.TarError, subprocess.TimeoutExpired) as error:
        print(f"phonecam: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
