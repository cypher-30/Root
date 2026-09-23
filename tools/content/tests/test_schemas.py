import json
import unittest
from pathlib import Path

from content_pipeline.schemas import get_catalog

FIXTURES = Path(__file__).resolve().parents[3] / "content" / "fixtures"


class SchemaFixtureConformanceTests(unittest.TestCase):
    """Every fixture under content/fixtures/{valid,invalid} must match the
    Python validator's accept/reject decision for its named schema. Kotlin
    exercises the identical fixture files for cross-language conformance."""

    def setUp(self):
        self.catalog = get_catalog()

    def _check_dir(self, kind: str, expect_valid: bool):
        directory = FIXTURES / kind
        found_any = False
        for path in sorted(directory.glob("*.json")):
            found_any = True
            schema_filename = path.stem + ".schema.json"
            with open(path, "r", encoding="utf-8") as f:
                instance = json.load(f)
            errors = self.catalog.validate(schema_filename, instance)
            if expect_valid:
                self.assertEqual(errors, [], "Expected valid fixture " + str(path) + " to pass: " + str(errors))
            else:
                self.assertNotEqual(errors, [], "Expected invalid fixture " + str(path) + " to fail")
        self.assertTrue(found_any, "No fixtures found under " + str(directory))

    def test_valid_fixtures_pass(self):
        self._check_dir("valid", expect_valid=True)

    def test_invalid_fixtures_fail(self):
        self._check_dir("invalid", expect_valid=False)


class ManifestSchemaTests(unittest.TestCase):
    def setUp(self):
        self.catalog = get_catalog()

    def _minimal_manifest(self):
        return {
            "schemaVersion": 1,
            "minReaderVersion": 1,
            "id": "pack.test.sample",
            "version": 1,
            "language": {"id": "sn", "code": "sn", "name": "Shona"},
            "title": "Sample",
            "objective": "Test manifest validity",
            "publication": "development",
            "phrases": [],
            "lessons": [],
            "assets": [],
            "credits": [],
        }

    def test_minimal_manifest_valid(self):
        errors = self.catalog.validate("manifest.schema.json", self._minimal_manifest())
        self.assertEqual(errors, [])

    def test_manifest_rejects_unknown_publication_status(self):
        m = self._minimal_manifest()
        m["publication"] = "public"
        errors = self.catalog.validate("manifest.schema.json", m)
        self.assertNotEqual(errors, [])

    def test_manifest_rejects_retired_as_publication_status(self):
        # Kotlin's PublicationStatus has no "retired" value at the manifest
        # level -- retirement is a separate CatalogEntry.retired boolean.
        m = self._minimal_manifest()
        m["publication"] = "retired"
        errors = self.catalog.validate("manifest.schema.json", m)
        self.assertNotEqual(errors, [])

    def test_manifest_rejects_unsafe_asset_key(self):
        m = self._minimal_manifest()
        m["assets"] = [{
            "id": "a1", "key": "../escape.m4a",
            "sha256": "0" * 64, "bytes": 100, "mimeType": "audio/mp4",
        }]
        errors = self.catalog.validate("manifest.schema.json", m)
        self.assertNotEqual(errors, [])

    def test_manifest_rejects_bad_sha256(self):
        m = self._minimal_manifest()
        m["assets"] = [{
            "id": "a1", "key": "assets/audio-0123456789abcdef0123456789abcdef.m4a",
            "sha256": "not-hex", "bytes": 100, "mimeType": "audio/mp4",
        }]
        errors = self.catalog.validate("manifest.schema.json", m)
        self.assertNotEqual(errors, [])

    def test_manifest_rejects_asset_over_20mib(self):
        m = self._minimal_manifest()
        m["assets"] = [{
            "id": "a1", "key": "assets/audio-0123456789abcdef0123456789abcdef.m4a",
            "sha256": "0" * 64, "bytes": 20971521, "mimeType": "audio/mp4",
        }]
        errors = self.catalog.validate("manifest.schema.json", m)
        self.assertNotEqual(errors, [])


class LessonActivitySchemaTests(unittest.TestCase):
    def setUp(self):
        self.catalog = get_catalog()

    def _minimal_comprehension(self):
        return {
            "kind": "choice_task",
            "task": {
                "id": "comp1", "prompt": "Pick one",
                "choices": [{"id": "c1", "text": "A"}, {"id": "c2", "text": "B"}],
                "acceptedChoiceIds": ["c1"],
            },
        }

    def test_dialogue_turn_activity_valid(self):
        activity = {"id": "step1", "kind": "dialogue_turn", "speaker": "A", "text": "Mhoro!"}
        errors = self.catalog.validate("activity-step.schema.json", activity)
        self.assertEqual(errors, [])

    def test_choice_activity_missing_task_fails(self):
        activity = {"id": "step2", "kind": "choice"}
        errors = self.catalog.validate("activity-step.schema.json", activity)
        self.assertNotEqual(errors, [])

    def test_choice_activity_valid(self):
        activity = {
            "id": "step2", "kind": "choice",
            "task": {
                "id": "step2-task", "prompt": "Pick the greeting",
                "choices": [{"id": "c1", "text": "Mhoro"}, {"id": "c2", "text": "Ndatenda"}],
                "acceptedChoiceIds": ["c1"],
            },
        }
        errors = self.catalog.validate("activity-step.schema.json", activity)
        self.assertEqual(errors, [])

    def test_ordered_tokens_activity_valid(self):
        activity = {
            "id": "step3", "kind": "ordered_tokens",
            "task": {
                "id": "step3-task", "prompt": "Build the sentence",
                "tokens": [
                    {"occurrenceId": "t1", "text": "Ndiri"},
                    {"occurrenceId": "t2", "text": "kushambira"},
                ],
                "acceptedSequences": [["t1", "t2"]],
            },
        }
        errors = self.catalog.validate("activity-step.schema.json", activity)
        self.assertEqual(errors, [])

    def test_listening_requires_comprehension(self):
        activity = {"id": "step4", "kind": "listening", "audioAssetId": "asset1"}
        errors = self.catalog.validate("activity-step.schema.json", activity)
        self.assertNotEqual(errors, [])

    def test_listening_permits_null_audio_asset_id_with_reason(self):
        activity = {
            "id": "step5", "kind": "listening",
            "audioAssetId": None, "unavailableReason": "No authentic recording exists yet.",
            "comprehension": self._minimal_comprehension(),
        }
        errors = self.catalog.validate("activity-step.schema.json", activity)
        self.assertEqual(errors, [])

    def test_listening_null_audio_asset_id_requires_reason(self):
        activity = {
            "id": "step6", "kind": "listening", "audioAssetId": None,
            "comprehension": self._minimal_comprehension(),
        }
        errors = self.catalog.validate("activity-step.schema.json", activity)
        self.assertNotEqual(errors, [])

    def test_listening_with_real_asset_id_valid(self):
        activity = {
            "id": "step7", "kind": "listening", "audioAssetId": "asset1",
            "comprehension": self._minimal_comprehension(),
        }
        errors = self.catalog.validate("activity-step.schema.json", activity)
        self.assertEqual(errors, [])

    def test_pattern_explanation_valid(self):
        activity = {
            "id": "step8", "kind": "pattern_explanation",
            "explanation": "Mhoro becomes Mhoroi in the plural.",
            "examples": ["phrase.sn.greeting.001"],
        }
        errors = self.catalog.validate("activity-step.schema.json", activity)
        self.assertEqual(errors, [])

    def test_reflection_requires_prompt(self):
        activity = {"id": "step9", "kind": "reflection"}
        errors = self.catalog.validate("activity-step.schema.json", activity)
        self.assertNotEqual(errors, [])

    def test_speaking_prompt_valid(self):
        activity = {"id": "step10", "kind": "speaking_prompt", "prompt": "Say hello out loud."}
        errors = self.catalog.validate("activity-step.schema.json", activity)
        self.assertEqual(errors, [])

    def test_lesson_requires_at_least_one_activity(self):
        lesson = {
            "id": "lesson1", "revision": 1, "title": "Greet", "objective": "Greet",
            "format": "guided_conversation", "activities": [],
        }
        catalog = get_catalog()
        errors = catalog.validate("lesson.schema.json", lesson)
        self.assertNotEqual(errors, [])


if __name__ == "__main__":
    unittest.main()
