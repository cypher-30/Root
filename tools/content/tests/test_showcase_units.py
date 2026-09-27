import json
import re
import subprocess
import sys
import unittest
from pathlib import Path

from content_pipeline.gates import check_audio_assets_present_for_publication, check_publication_status_allowed

ROOT = Path(__file__).resolve().parents[3]
SHOWCASE_DIR = ROOT / "content" / "editorial" / "showcase"
SEED_CATALOG = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "root" / "app" / "data" / "SeedCatalog.kt"


class ShowcaseUnitTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.paths = sorted(SHOWCASE_DIR.glob("*.json"))
        cls.manifests = [json.loads(p.read_text(encoding="utf-8")) for p in cls.paths]

    def test_seven_units_cover_every_language_twice_with_the_pilot(self):
        self.assertEqual(len(self.manifests), 7)
        counts = {}
        for m in self.manifests:
            counts[m["language"]["code"]] = counts.get(m["language"]["code"], 0) + 1
        self.assertEqual(counts, {"sn": 1, "sw": 2, "am": 2, "luo": 2})

    def test_each_unit_has_conversation_pattern_and_story(self):
        for m in self.manifests:
            self.assertEqual([l["format"] for l in m["lessons"]], ["guided_conversation", "pattern_workshop", "listening"])
            for lesson in m["lessons"]:
                self.assertTrue(lesson["requiredActivityIds"], lesson["id"])
                self.assertGreaterEqual(len(lesson["activities"]), 3, lesson["id"])

    def test_no_development_wording_in_learner_text(self):
        for m in self.manifests:
            text = json.dumps([m["title"], m["objective"], m["lessons"]], ensure_ascii=False).lower()
            for banned in ("development", "prototype", "placeholder", "pilot"):
                self.assertNotIn(banned, text, m["id"])

    def test_listening_is_honestly_unavailable_and_never_required(self):
        for m in self.manifests:
            self.assertEqual(m["assets"], [])
            self.assertEqual(m["publication"], "development")
            self.assertFalse(check_publication_status_allowed(m["publication"]).allowed)
            self.assertTrue(check_audio_assets_present_for_publication(m).allowed)
            for lesson in m["lessons"]:
                for activity in lesson["activities"]:
                    if activity["kind"] == "listening":
                        self.assertIsNone(activity["audioAssetId"])
                        self.assertTrue(activity["unavailableReason"])
                        self.assertNotIn(activity["id"], lesson["requiredActivityIds"])
                    if activity["kind"] in ("reflection", "speaking_prompt"):
                        self.assertNotIn(activity["id"], lesson["requiredActivityIds"])

    def test_target_language_lines_come_from_the_checked_starter_phrases(self):
        answers = set(re.findall(r'p\("[^"]+", "(?:[^"\\]|\\.)*", "((?:[^"\\]|\\.)*)"\)', SEED_CATALOG.read_text(encoding="utf-8")))
        answers |= {"Amosi", "Idhi nade?", "Erokamano"}

        def normalize(s):
            return re.sub(r"\s*\(.*?\)", "", s).strip().rstrip(".")

        known = {normalize(a) for a in answers}
        # Slots filled with a name, place, or a listed noun are fine: "Jina langu ni …" + "Amina".
        slot_prefixes = {normalize(a).replace("…", "").strip() for a in answers if "…" in a}
        slot_fillers = {"Amina", "Tendai", "Otieno", "Dawit", "Hanna", "Nairobi", "Kisumu", "Harare",
                        "chai ya maziwa", "Oyawore"}
        for m in self.manifests:
            for lesson in m["lessons"]:
                for activity in lesson["activities"]:
                    if activity["kind"] != "dialogue_turn" or activity["speaker"] == "Story":
                        continue
                    for sentence in re.split(r"(?<=[.?!])\s+", normalize(activity["text"])):
                        sentence = sentence.rstrip(".")
                        if sentence in known:
                            continue
                        filled = any(sentence.startswith(p) and sentence[len(p):].strip() in slot_fillers
                                     for p in slot_prefixes if p)
                        filled = filled or any(sentence.replace(f, "…").replace("… …", "…") in {normalize(a) for a in answers}
                                               for f in slot_fillers)
                        self.assertTrue(filled, f"{lesson['id']}: '{sentence}' is not a checked starter phrase")

    def test_rebuild_is_deterministic(self):
        before = {p.name: p.read_bytes() for p in self.paths}
        script = ROOT / "tools" / "content" / "scripts" / "build_showcase_units.py"
        result = subprocess.run([sys.executable, str(script)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(before, {p.name: p.read_bytes() for p in sorted(SHOWCASE_DIR.glob("*.json"))})


if __name__ == "__main__":
    unittest.main()
