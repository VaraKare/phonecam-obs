import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import live
from phonecam import ResearchError


class LivePreparation(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.config = Path(self.temp.name) / "settings/live.json"
        self.device = ["adb", "-s", "private-device"]

    def prepare(self, forwards="", token="a" * 64):
        calls = []
        def run(command):
            calls.append(command)
            if command[-1] == "files/live-token": return token
            if command[-1] == "--list": return forwards
            return ""
        with patch.object(live, "CONFIG", self.config), patch.object(live, "select_device", return_value=self.device), patch.object(live, "run", side_effect=run), patch("builtins.print"):
            live.prepare()
        return calls

    def test_creates_private_settings_and_local_abstract_forward(self):
        calls = self.prepare()
        self.assertEqual(json.loads(self.config.read_text())["port"], 27183)
        self.assertEqual(self.config.stat().st_mode & 0o777, 0o600)
        self.assertEqual(calls[-1], self.device + ["forward", "--no-rebind", "tcp:27183", "localabstract:phonecam-live-v1"])
        self.assertFalse(any("am" in call or "input" in call for call in calls))

    def test_identical_forward_not_rebound(self):
        calls = self.prepare("private-device tcp:27183 localabstract:phonecam-live-v1")
        self.assertEqual(len(calls), 2)

    def test_other_device_forward_is_preserved(self):
        with self.assertRaises(ResearchError): self.prepare("other-device tcp:27183 localabstract:phonecam-live-v1")
        self.assertFalse(self.config.exists())

    def test_other_destination_is_preserved(self):
        with self.assertRaises(ResearchError): self.prepare("private-device tcp:27183 tcp:9000")

    def test_invalid_token_creates_no_settings(self):
        with self.assertRaises(ResearchError): self.prepare(token="unexpected token")
        self.assertFalse(self.config.exists())

    def test_replaces_existing_settings_privately(self):
        self.config.parent.mkdir(parents=True); self.config.write_text("old")
        self.config.chmod(0o644)
        self.prepare()
        self.assertEqual(self.config.stat().st_mode & 0o777, 0o600)
        self.assertEqual(json.loads(self.config.read_text())["token"], "a" * 64)
