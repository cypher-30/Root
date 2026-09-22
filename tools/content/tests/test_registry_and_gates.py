import unittest

from content_pipeline.registry import SourceRegistry, ComponentRecord
from content_pipeline.gates import (
    check_component_publishable, check_source_checked_only,
    check_publication_status_allowed, check_review_revision_matches,
    check_audio_assets_present_for_publication,
)


class SourceRegistryTests(unittest.TestCase):
    def test_loads_real_registry_file(self):
        registry = SourceRegistry.load()
        releases = registry.all_releases()
        self.assertGreaterEqual(len(releases), 4)
        source_ids = {r.source_id for r in releases}
        self.assertEqual(source_ids, {"fleurs", "kencorpus", "common_voice", "tatoeba"})

    def test_component_status_for_known_release(self):
        registry = SourceRegistry.load()
        record = registry.component_status("fleurs", "google/fleurs@main", "audio")
        self.assertEqual(record.rights_status, "cleared")
        self.assertEqual(record.license, "CC-BY-4.0")

    def test_unknown_release_is_unknown_not_cleared(self):
        registry = SourceRegistry.load()
        record = registry.component_status("fleurs", "nonexistent-release", "audio")
        self.assertEqual(record.rights_status, "unknown")
        self.assertEqual(record.review_status, "unreviewed")

    def test_unknown_component_kind_is_unknown_not_cleared(self):
        registry = SourceRegistry.load()
        record = registry.component_status("fleurs", "google/fleurs@main", "activity_wording")
        self.assertEqual(record.rights_status, "unknown")

    def test_registry_schema_is_valid(self):
        registry = SourceRegistry.load()
        errors = registry.validate_against_schema()
        self.assertEqual(errors, [])


class GateTests(unittest.TestCase):
    def test_publishable_requires_cleared_and_native_reviewed(self):
        rec = ComponentRecord(
            component_kind="audio", license="CC-BY-4.0",
            rights_status="cleared", review_status="native_reviewed", review_revision=3,
        )
        decision = check_component_publishable(rec)
        self.assertTrue(decision.allowed)

    def test_source_checked_only_blocks_publication(self):
        rec = ComponentRecord(
            component_kind="audio", license="CC-BY-4.0",
            rights_status="cleared", review_status="source_checked",
        )
        decision = check_component_publishable(rec)
        self.assertFalse(decision.allowed)
        self.assertIn("source_checked", decision.reason)

    def test_unknown_rights_blocks_even_development_use(self):
        rec = ComponentRecord(
            component_kind="audio", license="UNKNOWN",
            rights_status="unknown", review_status="unreviewed",
        )
        decision = check_source_checked_only(rec)
        self.assertFalse(decision.allowed)

    def test_incompatible_rights_always_blocks(self):
        rec = ComponentRecord(
            component_kind="audio", license="All rights reserved",
            rights_status="incompatible", review_status="native_reviewed", review_revision=1,
        )
        self.assertFalse(check_component_publishable(rec).allowed)
        self.assertFalse(check_source_checked_only(rec).allowed)

    def test_development_status_never_allowed_to_publish(self):
        self.assertFalse(check_publication_status_allowed("development").allowed)
        self.assertFalse(check_publication_status_allowed("retired").allowed)
        self.assertTrue(check_publication_status_allowed("published").allowed)

    def test_stale_review_revision_blocks(self):
        rec = ComponentRecord(
            component_kind="audio", license="CC-BY-4.0",
            rights_status="cleared", review_status="native_reviewed", review_revision=1,
        )
        decision = check_review_revision_matches(rec, current_revision=2)
        self.assertFalse(decision.allowed)
        self.assertIn("stale", decision.reason)

    def test_matching_review_revision_allows(self):
        rec = ComponentRecord(
            component_kind="audio", license="CC-BY-4.0",
            rights_status="cleared", review_status="native_reviewed", review_revision=2,
        )
        decision = check_review_revision_matches(rec, current_revision=2)
        self.assertTrue(decision.allowed)

    def _manifest_with_audio_step(self, status, audio_asset_id, assets=None, unavailable_reason="dev only"):
        step = {"id": "s1", "kind": "listening", "audioAssetId": audio_asset_id}
        if audio_asset_id is None:
            step["unavailableReason"] = unavailable_reason
        return {
            "publication": status,
            "lessons": [{"id": "l1", "activities": [step]}],
            "assets": assets or [],
        }

    def test_audio_gate_allows_null_audio_when_development(self):
        manifest = self._manifest_with_audio_step("development", None)
        decision = check_audio_assets_present_for_publication(manifest)
        self.assertTrue(decision.allowed, decision.reason)

    def test_audio_gate_rejects_null_audio_when_published(self):
        manifest = self._manifest_with_audio_step("published", None)
        decision = check_audio_assets_present_for_publication(manifest)
        self.assertFalse(decision.allowed)
        self.assertIn("audioAssetId is null", decision.reason)

    def test_audio_gate_allows_real_asset_reference(self):
        manifest = self._manifest_with_audio_step(
            "published", "asset1", assets=[{"id": "asset1"}],
        )
        decision = check_audio_assets_present_for_publication(manifest)
        self.assertTrue(decision.allowed, decision.reason)

    def test_audio_gate_rejects_dangling_asset_reference(self):
        manifest = self._manifest_with_audio_step("development", "asset-does-not-exist", assets=[])
        decision = check_audio_assets_present_for_publication(manifest)
        self.assertFalse(decision.allowed)
        self.assertIn("no matching entry", decision.reason)


if __name__ == "__main__":
    unittest.main()
