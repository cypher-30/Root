import unittest
from pathlib import Path

from content_pipeline.adapters.common_voice import CommonVoiceAdapter

FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures" / "common_voice_release"


class CommonVoiceAdapterTests(unittest.TestCase):
    def test_discover_missing_release_dir_is_authorization_required(self):
        adapter = CommonVoiceAdapter(release_dir=Path("does/not/exist"))
        result = adapter.discover()
        self.assertFalse(result.available)
        self.assertEqual(result.blocked_reason, "authorization_required")

    def test_discover_success_on_real_fixture_shape(self):
        adapter = CommonVoiceAdapter(release_dir=FIXTURE_DIR, release_id="cv-test-release")
        result = adapter.discover()
        self.assertTrue(result.available)
        self.assertIn("sw", result.languages)
        self.assertIn("fr", result.languages)

    def test_import_candidates_only_validated_matching_locale(self):
        adapter = CommonVoiceAdapter(release_dir=FIXTURE_DIR, release_id="cv-test-release")
        report = adapter.import_candidates(language="sw", limit=10)
        self.assertIsNone(report.blocked_reason)
        accepted_ids = {c["itemId"] for c in report.accepted}
        self.assertEqual(accepted_ids, {"s0001", "s0002"})
        # No demographic columns leak into the candidate payload.
        for c in report.accepted:
            self.assertNotIn("client_id", c)
            self.assertNotIn("age", c)
            self.assertNotIn("gender", c)
            self.assertNotIn("accents", c)

    def test_reported_sentence_is_quarantined_not_imported(self):
        adapter = CommonVoiceAdapter(release_dir=FIXTURE_DIR, release_id="cv-test-release")
        report = adapter.import_candidates(language="sw", limit=10)
        quarantined_ids = {q.detail for q in report.quarantined if q.reason == "sentence_reported"}
        self.assertIn("s0003", quarantined_ids)
        accepted_ids = {c["itemId"] for c in report.accepted}
        self.assertNotIn("s0003", accepted_ids)

    def test_missing_clip_file_is_quarantined(self):
        adapter = CommonVoiceAdapter(release_dir=FIXTURE_DIR, release_id="cv-test-release")
        report = adapter.import_candidates(language="sw", limit=10)
        reasons = {q.reason for q in report.quarantined}
        self.assertIn("clip_file_missing", reasons)
        accepted_ids = {c["itemId"] for c in report.accepted}
        self.assertNotIn("s0004", accepted_ids)

    def test_unknown_locale_is_honest_blocked_result(self):
        adapter = CommonVoiceAdapter(release_dir=FIXTURE_DIR, release_id="cv-test-release")
        report = adapter.import_candidates(language="zzz", limit=10)
        self.assertEqual(report.blocked_reason, "no_rows_for_locale:zzz")

    def test_release_shape_unrecognized_when_columns_missing(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "validated.tsv"
            path.write_text("path\tsentence\nfoo.mp3\thi\n", encoding="utf-8")
            adapter = CommonVoiceAdapter(release_dir=Path(tmp), release_id="cv-bad")
            result = adapter.discover()
            self.assertEqual(result.blocked_reason, "release_shape_unrecognized")


if __name__ == "__main__":
    unittest.main()
