import json
import subprocess
import sys
import unittest
from pathlib import Path

from content_pipeline.registry import ComponentRecord
from content_pipeline.gates import check_component_publishable, check_source_checked_only, check_publication_status_allowed

ROOT = Path(__file__).resolve().parents[3]
PILOT_DIR = ROOT / "content" / "editorial" / "shona_pilot"
MANIFEST_PATH = ROOT / "content" / "editorial" / "shona-pilot.json"


class ShonaPilotTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        cls.phrases = json.loads((PILOT_DIR / "phrases.json").read_text(encoding="utf-8"))
        cls.lessons = json.loads((PILOT_DIR / "lessons.json").read_text(encoding="utf-8"))

    def test_manifest_is_development_status_never_published(self):
        self.assertEqual(self.manifest["publication"], "development")
        decision = check_publication_status_allowed(self.manifest["publication"])
        self.assertFalse(decision.allowed)

    def test_manifest_has_no_assets_no_fake_audio(self):
        self.assertEqual(self.manifest["assets"], [])
        for phrase in self.phrases:
            self.assertIsNone(phrase["audioAssetId"])

    def test_wire_phrase_prompt_is_target_language_meaning_is_known_language(self):
        # Field-direction convention (matches parent's PhraseEntity(answer=wire.prompt,
        # prompt=wire.meaning) projection): 'prompt' must be the Shona target-language
        # text; 'meaning' must be the English gloss. Never the other way around.
        by_id = {p["id"]: p for p in self.manifest["phrases"]}
        hello_sg = by_id["shona-pilot-hello-sg"]
        self.assertEqual(hello_sg["prompt"], "Mhoro")
        self.assertEqual(hello_sg["meaning"], "Hello (one person)")
        for wire_phrase in self.manifest["phrases"]:
            authored = next(p for p in self.phrases if p["id"] == wire_phrase["id"])
            self.assertEqual(wire_phrase["prompt"], authored["targetText"])
            self.assertEqual(wire_phrase["meaning"], authored["meaning"])
        # dialogue_turn/listening activities follow the identical text/translation
        # (target-language / known-language) direction.
        greetings_lesson = next(l for l in self.manifest["lessons"] if l["id"] == "shona-pilot-lesson-greetings")
        turn = next(a for a in greetings_lesson["activities"] if a["id"] == "t1")
        self.assertEqual(turn["text"], "Mangwanani")
        self.assertEqual(turn["translation"], "Good morning")

    def test_phrase_provenance_is_honestly_unreviewed_and_unknown_rights(self):
        for phrase in self.phrases:
            prov = phrase["provenance"]
            self.assertEqual(prov["rightsStatus"], "unknown")
            self.assertEqual(prov["reviewStatus"], "source_checked")

    def test_gates_refuse_to_treat_pilot_content_as_publishable(self):
        for phrase in self.phrases:
            prov = phrase["provenance"]
            record = ComponentRecord(
                component_kind=prov["componentKind"],
                license=prov["componentLicense"],
                rights_status=prov["rightsStatus"],
                review_status=prov["reviewStatus"],
                review_revision=prov["reviewRevision"],
            )
            full_gate = check_component_publishable(record)
            dev_gate = check_source_checked_only(record)
            self.assertFalse(full_gate.allowed, f"{phrase['id']} must not pass the full publish gate")
            self.assertFalse(dev_gate.allowed, f"{phrase['id']} must not pass the dev-eligible gate (rights unknown)")

    def test_greetings_lesson_has_six_dialogue_turns_and_a_transfer_task(self):
        greetings = next(l for l in self.lessons if l["id"] == "shona-pilot-lesson-greetings")
        dialogue_activities = [a for a in greetings["activities"] if a["kind"] == "dialogue_turn"]
        transfer_activities = [a for a in greetings["activities"] if a["kind"] == "choice"]
        self.assertEqual(len(dialogue_activities), 6)
        self.assertEqual(len(transfer_activities), 1)
        self.assertIn("c1", transfer_activities[0]["task"]["acceptedChoiceIds"])

    def test_greetings_dialogue_is_coherent_not_six_disconnected_cards(self):
        # Each of the 6 singular-register greeting/courtesy phrases is used
        # exactly once (no repeats, no gaps) across the six turns, and an
        # English reflection activity states the scene (who is speaking, and
        # why the exchange honestly spans morning/afternoon/evening) rather
        # than presenting six unrelated phrase cards as one dialogue.
        greetings = next(l for l in self.lessons if l["id"] == "shona-pilot-lesson-greetings")
        scene_setup = next(a for a in greetings["activities"] if a["id"] == "scene-setup")
        self.assertEqual(scene_setup["kind"], "reflection")
        self.assertNotIn("scene-setup", greetings["requiredActivityIds"])
        self.assertGreater(len(scene_setup["prompt"]), 0)
        dialogue_activities = [a for a in greetings["activities"] if a["kind"] == "dialogue_turn"]
        linked_phrase_ids = [a["linkedPhraseId"] for a in dialogue_activities]
        expected_singular_phrases = {
            "shona-pilot-good-morning", "shona-pilot-welcome", "shona-pilot-thanks-sg",
            "shona-pilot-good-afternoon", "shona-pilot-hello-sg", "shona-pilot-good-evening",
        }
        self.assertEqual(set(linked_phrase_ids), expected_singular_phrases)
        self.assertEqual(len(linked_phrase_ids), len(set(linked_phrase_ids)))

    def test_listening_lesson_is_explicitly_blocked_not_skipped(self):
        listening = next(l for l in self.lessons if l["id"] == "shona-pilot-lesson-listening")
        self.assertEqual(listening["format"], "listening")
        self.assertTrue(all(a["kind"] == "listening" for a in listening["activities"]))
        self.assertTrue(all(a["audioAssetId"] is None for a in listening["activities"]))
        self.assertTrue(all("unavailableReason" in a and a["unavailableReason"] for a in listening["activities"]))
        self.assertTrue(all("comprehension" in a for a in listening["activities"]))
        # listen-1 IS the required (gating) activity -- Kotlin's
        # ContentValidator rejects an empty requiredActivityIds
        # (NO_REQUIRED_ACTIVITIES). It is still honestly un-completable
        # without real audio: the runner returns AUDIO_UNAVAILABLE for a
        # null audioAssetId rather than ever accepting evidence, so the
        # lesson stays blocked -- required, but never fakeable complete.
        self.assertIn("listen-1", listening["requiredActivityIds"])

    def test_gates_refuse_null_audio_only_when_published(self):
        from content_pipeline.gates import check_audio_assets_present_for_publication
        # The pilot manifest is development-status with a null audioAssetId
        # listening activity: the gate must allow this (null is honest, not a
        # missing-data bug, as long as the pack is not published).
        decision = check_audio_assets_present_for_publication(self.manifest)
        self.assertTrue(decision.allowed, decision.reason)
        # But republishing the exact same lessons under publication.status
        # 'published' must be refused -- null audio is never publication-safe.
        published_manifest = dict(self.manifest)
        published_manifest["publication"] = "published"
        blocked = check_audio_assets_present_for_publication(published_manifest)
        self.assertFalse(blocked.allowed)
        self.assertIn("audioAssetId is null", blocked.reason)

    def test_pattern_workshop_has_guided_and_changed_context_construction(self):
        workshop = next(l for l in self.lessons if l["id"] == "shona-pilot-lesson-pattern")
        self.assertEqual(workshop["format"], "pattern_workshop")
        construction_activities = [a for a in workshop["activities"] if a["kind"] == "ordered_tokens"]
        self.assertEqual(len(construction_activities), 2)
        # Second construction activity must target the plural/group form (changed context).
        second_tokens = [t["text"] for t in construction_activities[1]["task"]["tokens"]]
        self.assertEqual(second_tokens, ["Maita", "zvenyu"])

    def test_manifest_schema_validates_via_cli(self):
        result = subprocess.run(
            [sys.executable, "-m", "content_pipeline.cli", "validate-fixtures"],
            cwd=str(ROOT / "tools" / "content"), capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_manifest_hash_is_deterministic_across_rebuild(self):
        script = ROOT / "tools" / "content" / "scripts" / "build_shona_pilot.py"
        before = MANIFEST_PATH.read_bytes()
        result = subprocess.run([sys.executable, str(script)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        after = MANIFEST_PATH.read_bytes()
        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main()
