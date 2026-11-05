import copy
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("phonecam", Path(__file__).resolve().parents[1] / "phonecam.py")
phonecam = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(phonecam)


def evidence():
    mapping, ratings = {}, []
    index = 0
    for source in phonecam.SOURCES:
        for scene in phonecam.SCENES:
            for repeat in ((1, 2, 3) if source == "probe" else (1,)):
                index += 1
                identity = f"clip-{index:03}"
                metadata = {"success": True, "source": "probe", "profileId": "a" * 64, "stopReason": "duration",
                            "processingMismatchFrames": 0, "processingUnconfirmedFrames": 0,
                            "requested": {"edgeMode": 1, "noiseMode": 1}, "observedCameraLast": {"edgeMode": 1, "noiseMode": 1},
                            "encoded": {"fps": 30, "durationUs": 14_966_667, "frames": 450, "maxGapUs": 33_334, "nonMonotonicPts": 0}}
                mapping[identity] = {"source": source, "scene": scene, "repeat": repeat, "metadata": metadata if source == "probe" else None}
                ratings.append({"clip_id": identity, "score": "4", "acceptable_focus": "yes", "acceptable_motion": "yes", "no_severe_artifacts": "yes"})
    return mapping, ratings


class GateTests(unittest.TestCase):
    def test_quality_pass_does_not_claim_latency_or_verified_device(self):
        result = phonecam.quality_decision(*evidence())
        self.assertEqual(result["qualityStatus"], "pass")
        self.assertEqual(result["overallFeasibility"], "inconclusive")
        self.assertEqual(result["opticalLatencyStatus"], "not_measured")
        self.assertFalse(result["verifiedDevice"])

    def test_missing_repeat_or_baseline_is_inconclusive(self):
        for removed in ("clip-001", "clip-020"):
            mapping, ratings = evidence()
            del mapping[removed]
            ratings = [r for r in ratings if r["clip_id"] != removed]
            self.assertEqual(phonecam.quality_decision(mapping, ratings)["qualityStatus"], "inconclusive")

    def test_missing_rating_cannot_pass(self):
        mapping, ratings = evidence()
        self.assertEqual(phonecam.quality_decision(mapping, ratings[:-1])["qualityStatus"], "inconclusive")

    def test_bad_quality_is_fail_even_with_missing_evidence(self):
        mapping, ratings = evidence()
        ratings[-1]["score"] = "3"
        self.assertEqual(phonecam.quality_decision(mapping, ratings[1:])["qualityStatus"], "fail")

    def test_slow_capture_and_long_gap_fail(self):
        for field, value in (("fps", 24), ("maxGapUs", 100_000), ("durationUs", 5_000_000), ("nonMonotonicPts", 1)):
            mapping, ratings = evidence()
            mapping["clip-020"]["metadata"]["encoded"][field] = value
            self.assertEqual(phonecam.quality_decision(mapping, ratings)["qualityStatus"], "fail")

    def test_nonfinite_numbers_do_not_pass(self):
        for value in (float("nan"), float("inf"), None, True):
            mapping, ratings = evidence()
            mapping["clip-020"]["metadata"]["encoded"]["fps"] = value
            self.assertNotEqual(phonecam.quality_decision(mapping, ratings)["qualityStatus"], "pass")

    def test_different_profiles_cannot_be_combined(self):
        mapping, ratings = evidence()
        mapping["clip-020"]["metadata"]["profileId"] = "b" * 64
        self.assertEqual(phonecam.quality_decision(mapping, ratings)["qualityStatus"], "fail")

    def test_interrupted_capture_does_not_pass(self):
        mapping, ratings = evidence()
        mapping["clip-020"]["metadata"]["stopReason"] = "app_backgrounded"
        self.assertEqual(phonecam.quality_decision(mapping, ratings)["qualityStatus"], "fail")

    def test_unknown_applied_processing_is_inconclusive(self):
        mapping, ratings = evidence()
        mapping["clip-020"]["metadata"]["observedCameraLast"] = {}
        self.assertEqual(phonecam.quality_decision(mapping, ratings)["qualityStatus"], "inconclusive")

    def test_changed_media_fails(self):
        self.assertEqual(phonecam.quality_decision(*evidence(), hash_check=lambda *_: False)["qualityStatus"], "fail")

    def test_duplicate_or_unknown_rating_ids_rejected(self):
        mapping, ratings = evidence()
        with self.assertRaises(phonecam.ResearchError):
            phonecam.quality_decision(mapping, ratings + [copy.copy(ratings[0])])
        ratings.append({"clip_id": "unknown"})
        with self.assertRaises(phonecam.ResearchError):
            phonecam.quality_decision(mapping, ratings)


def archive(name="files/benchmarks/clip.mp4", content=b"video", link=False):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w") as tar:
        info = tarfile.TarInfo(name)
        if link:
            info.type = tarfile.SYMTYPE
            info.linkname = "/tmp/outside"
            tar.addfile(info)
        else:
            info.size = len(content)
            tar.addfile(info, io.BytesIO(content))
    output.seek(0)
    return output


class CollectionTests(unittest.TestCase):
    def test_regular_files_stream_to_destination(self):
        with tempfile.TemporaryDirectory() as path:
            out = Path(path)
            self.assertEqual(phonecam.extract_benchmarks(archive(), out), 1)
            self.assertEqual((out / "clip.mp4").read_bytes(), b"video")

    def test_traversal_links_and_unexpected_extensions_rejected(self):
        for name, link in (("files/benchmarks/../../../outside.mp4", False), ("/files/benchmarks/a.mp4", False),
                           ("files/benchmarks/a.mp4", True), ("files/benchmarks/executable.sh", False)):
            with tempfile.TemporaryDirectory() as path, self.assertRaises(phonecam.ResearchError):
                phonecam.extract_benchmarks(archive(name, link=link), Path(path))

    def test_collection_budget_and_overwrite_are_rejected(self):
        with tempfile.TemporaryDirectory() as path:
            with self.assertRaises(phonecam.ResearchError):
                phonecam.extract_benchmarks(archive(), Path(path), max_bytes=2)
            phonecam.extract_benchmarks(archive(), Path(path))
            with self.assertRaises(phonecam.ResearchError):
                phonecam.extract_benchmarks(archive(), Path(path))
            self.assertEqual((Path(path) / "clip.mp4").read_bytes(), b"video")


if __name__ == "__main__":
    unittest.main()
