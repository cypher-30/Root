import json
import unittest

from content_pipeline import net
from content_pipeline.adapters.fleurs import FleursAdapter


class FakeResponse:
    def __init__(self, payload):
        self._payload = payload
        self.body = json.dumps(payload).encode("utf-8")
        self.status = 200
        self.headers = {}

    def json(self):
        return self._payload


def fake_get_factory(responses):
    """responses: dict url-substring -> payload dict or Exception instance."""
    def fake_get(url, timeout=15.0, max_bytes=10_000_000, headers=None):
        for key, value in responses.items():
            if key in url:
                if isinstance(value, Exception):
                    raise value
                return FakeResponse(value)
        raise AssertionError("Unexpected URL requested: " + url)
    return fake_get


class FleursAdapterTests(unittest.TestCase):
    def test_discover_unsupported_language(self):
        adapter = FleursAdapter(http_get=fake_get_factory({}))
        result = adapter.discover(language="xx")
        self.assertFalse(result.available)
        self.assertEqual(result.blocked_reason, "unsupported_language")

    def test_discover_success(self):
        responses = {
            "/splits?": {"splits": [{"config": "sn_zw", "split": "test"}]},
            "/info?": {"dataset_info": {"splits": {"test": {"num_examples": 925}}}},
        }
        adapter = FleursAdapter(http_get=fake_get_factory(responses))
        result = adapter.discover(language="sn", split="test")
        self.assertTrue(result.available)
        self.assertEqual(result.item_count, 925)

    def test_discover_split_unavailable(self):
        responses = {"/splits?": {"splits": [{"config": "sn_zw", "split": "train"}]}}
        adapter = FleursAdapter(http_get=fake_get_factory(responses))
        result = adapter.discover(language="sn", split="test")
        self.assertFalse(result.available)
        self.assertEqual(result.blocked_reason, "split_unavailable")

    def test_discover_network_unavailable(self):
        adapter = FleursAdapter(http_get=fake_get_factory({
            "/splits?": net.NetworkUnavailable("boom"),
        }))
        result = adapter.discover(language="sn")
        self.assertEqual(result.blocked_reason, "network_unavailable")

    def test_import_candidates_success_parses_rows(self):
        responses = {
            "/rows?": {
                "rows": [
                    {"row_idx": 0, "row": {
                        "id": 1, "transcription": "mhoro", "raw_transcription": "Mhoro!",
                        "lang_id": 80, "path": "1.wav", "num_samples": 16000,
                    }},
                    {"row_idx": 1, "row": {
                        "id": 2, "transcription": "", "raw_transcription": "",
                        "lang_id": 80, "path": "2.wav",
                    }},
                ]
            }
        }
        adapter = FleursAdapter(http_get=fake_get_factory(responses))
        report = adapter.import_candidates(language="sn", limit=10)
        self.assertIsNone(report.blocked_reason)
        self.assertEqual(len(report.accepted), 1)
        self.assertEqual(report.accepted[0]["targetText"], "Mhoro!")
        self.assertEqual(len(report.rejected), 1)
        self.assertEqual(report.rejected[0].reason, "missing_transcription")

    def test_import_candidates_handles_rows_endpoint_error_honestly(self):
        adapter = FleursAdapter(http_get=fake_get_factory({"/rows?": {"error": "Internal Server Error"}}))
        report = adapter.import_candidates(language="sn", limit=5)
        self.assertIsNotNone(report.blocked_reason)
        self.assertIn("rows_endpoint_error", report.blocked_reason)
        self.assertEqual(report.accepted, [])

    def test_import_candidates_unsupported_language_blocked(self):
        adapter = FleursAdapter(http_get=fake_get_factory({}))
        report = adapter.import_candidates(language="zz", limit=5)
        self.assertEqual(report.blocked_reason, "unsupported_language")

    def test_import_candidates_deduplicates_ids(self):
        responses = {
            "/rows?": {
                "rows": [
                    {"row_idx": 0, "row": {"id": 1, "transcription": "a", "raw_transcription": "A"}},
                    {"row_idx": 1, "row": {"id": 1, "transcription": "b", "raw_transcription": "B"}},
                ]
            }
        }
        adapter = FleursAdapter(http_get=fake_get_factory(responses))
        report = adapter.import_candidates(language="sn", limit=10)
        self.assertEqual(len(report.accepted), 1)
        self.assertEqual(len(report.rejected), 1)
        self.assertEqual(report.rejected[0].reason, "duplicate_item_id")


if __name__ == "__main__":
    unittest.main()
