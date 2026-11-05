"""Actual local MP4 parsing and blind-review round trip; never device evidence."""
import argparse
import contextlib
import csv
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("phonecam", Path(__file__).resolve().parents[1] / "phonecam.py")
phonecam = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(phonecam)


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "FFmpeg and ffprobe needed for real-container integration")
class ReviewRoundTrip(unittest.TestCase):
    def test_real_container_blinding_and_changed_file_detection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            seed = root / "synthetic-test-only.mp4"
            subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=c=blue:s=64x64:r=30",
                            "-t", "10.1", "-c:v", "libx264", "-preset", "ultrafast", "-bf", "0", str(seed)], check=True)
            entries = []
            index = 0
            for source in phonecam.SOURCES:
                for scene in phonecam.SCENES:
                    for repeat in ((1, 2, 3) if source == "probe" else (1,)):
                        index += 1
                        clip = root / f"synthetic-{index}.mp4"
                        subprocess.run(["ffmpeg", "-v", "error", "-i", str(seed), "-c", "copy", "-metadata",
                                        f"comment=synthetic unit-test fixture {index}", str(clip)], check=True)
                        entry = {"source": source, "scene": scene, "repeat": repeat, "file": clip.name}
                        if source == "probe":
                            sidecar = root / f"synthetic-{index}.json"
                            metadata = {"schemaVersion": 1, "source": "probe", "success": True, "stopReason": "duration",
                                        "processingMismatchFrames": 0, "processingUnconfirmedFrames": 0,
                                        "profileId": "a" * 64, "scene": scene, "mediaFile": clip.name,
                                        "requested": {"width": 64, "height": 64, "mime": "video/avc", "edgeMode": 1, "noiseMode": 1},
                                        "observedCameraLast": {"edgeMode": 1, "noiseMode": 1},
                                        "encoded": {"frames": 303, "fps": 30, "durationUs": 302 * 1_000_000 / 30,
                                                    "maxGapUs": 33_334, "nonMonotonicPts": 0}}
                            sidecar.write_text(json.dumps(metadata))
                            entry["sidecar"] = sidecar.name
                        entries.append(entry)
            manifest = root / "session.json"
            manifest.write_text(json.dumps({"schemaVersion": 1, "matchedConditionsConfirmed": True, "clips": entries}))
            review = root / "review"
            with contextlib.redirect_stdout(io.StringIO()):
                phonecam.create_review(argparse.Namespace(manifest=manifest, out=review))
            mapping = json.loads((review / "PRIVATE_sources.json").read_text())["clips"]
            self.assertEqual(len(mapping), 20)
            self.assertEqual(len(list((review / "clips").glob("*.mp4"))), 20)
            with (review / "ratings.csv").open(newline="") as stream:
                reader = csv.DictReader(stream)
                fields, rows = reader.fieldnames, list(reader)
            self.assertNotIn("source", fields)
            for row in rows:
                row.update(score="4", acceptable_focus="yes", acceptable_motion="yes", no_severe_artifacts="yes")
            with (review / "ratings.csv").open("w", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=fields)
                writer.writeheader(); writer.writerows(rows)
            report = root / "result.json"
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(phonecam.evaluate(argparse.Namespace(review=review, out=report)), 0)
            decision = json.loads(report.read_text())
            self.assertEqual(decision["qualityStatus"], "pass")
            self.assertEqual(decision["overallFeasibility"], "inconclusive")
            next((review / "clips").glob("*.mp4")).write_bytes(b"changed")
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(phonecam.evaluate(argparse.Namespace(review=review, out=report)), 2)
            self.assertEqual(json.loads(report.read_text())["qualityStatus"], "fail")


class InvalidManifestTests(unittest.TestCase):
    def test_missing_condition_confirmation_and_duplicated_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "session.json"
            manifest.write_text('{"schemaVersion": 1, "clips": []}')
            args = argparse.Namespace(manifest=manifest, out=root / "review")
            with self.assertRaises(phonecam.ResearchError):
                phonecam.create_review(args)
            a, b = root / "a.mp4", root / "b.mp4"
            a.write_bytes(b"same bytes"); b.write_bytes(b"same bytes")
            manifest.write_text(json.dumps({"schemaVersion": 1, "matchedConditionsConfirmed": True, "clips": [
                {"source": "stock", "scene": "well_lit_detail", "repeat": 1, "file": "a.mp4"},
                {"source": "stock", "scene": "well_lit_detail", "repeat": 2, "file": "b.mp4"}]}))
            with patch.object(phonecam, "inspect_media", return_value={"frames": 303, "fps": 30}), self.assertRaisesRegex(phonecam.ResearchError, "Duplicate media"):
                phonecam.create_review(args)

    def test_non_object_and_nonfinite_json_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "input.json"
            for text in ("[]", '{"fps": NaN}', '{"fps": Infinity}'):
                file.write_text(text)
                with self.assertRaises(phonecam.ResearchError):
                    phonecam.load_json(file)
