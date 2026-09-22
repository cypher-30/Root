import json
import unittest

from content_pipeline import net
from content_pipeline.adapters.kencorpus import (
    KenCorpusAdapter, classify_language, classify_kind, numeric_prefix,
)


class FakeResponse:
    def __init__(self, payload):
        self.body = json.dumps(payload).encode("utf-8")
        self._payload = payload

    def json(self):
        return self._payload


class ClassifierTests(unittest.TestCase):
    def test_classify_language_by_directory_label(self):
        self.assertEqual(classify_language("voice_dholuo", "0058_dho.m4a"), "luo")
        self.assertEqual(classify_language("collected_data_text_swa", "1683_swa.txt"), "sw")
        self.assertEqual(classify_language("voice_lhy_lumarachi", "0055_lhychi.aac"), "luhya-lumarachi")
        self.assertIsNone(classify_language("metadata", "0001_kencorpus_metadata_CSV.tab"))

    def test_classify_kind(self):
        self.assertEqual(classify_kind("0058_dho.m4a", "application/octet-stream"), "audio")
        self.assertEqual(classify_kind("1683_swa.txt", "text/plain"), "text")
        self.assertIsNone(classify_kind("0001_kencorpus_metadata_JSON.json", "application/json"))

    def test_numeric_prefix(self):
        self.assertEqual(numeric_prefix("0058_dho.m4a"), "0058")
        self.assertIsNone(numeric_prefix("no_prefix.txt"))


def fake_get_factory(responses):
    def fake_get(url, timeout=15.0, max_bytes=10_000_000, headers=None):
        for key, value in responses.items():
            if key in url:
                if isinstance(value, Exception):
                    raise value
                return FakeResponse(value)
        raise AssertionError("Unexpected URL: " + url)
    return fake_get


class KenCorpusAdapterTests(unittest.TestCase):
    def test_discover_success(self):
        payload = {
            "data": {
                "latestVersion": {
                    "versionNumber": 9, "versionMinorNumber": 1,
                    "license": {"name": "CC BY 4.0"},
                }
            }
        }
        adapter = KenCorpusAdapter(http_get=fake_get_factory({"datasets/:persistentId": payload}))
        result = adapter.discover()
        self.assertTrue(result.available)
        self.assertIn("9.1", result.detail)

    def test_discover_network_unavailable(self):
        adapter = KenCorpusAdapter(http_get=fake_get_factory({
            "datasets/:persistentId": net.NetworkUnavailable("timeout"),
        }))
        result = adapter.discover()
        self.assertFalse(result.available)
        self.assertEqual(result.blocked_reason, "network_unavailable")

    def _files_page(self, entries):
        return {"status": "OK", "totalCount": len(entries), "data": entries}

    def test_import_candidates_filters_by_language_and_pairs_alignment(self):
        entries = [
            {"label": "0058_dho.txt", "directoryLabel": "collected_data_text_dho",
             "dataFile": {"id": 1, "filename": "0058_dho.txt", "contentType": "text/plain", "filesize": 100, "md5": "aa"}},
            {"label": "0058_dho.m4a", "directoryLabel": "voice_dholuo",
             "dataFile": {"id": 2, "filename": "0058_dho.m4a", "contentType": "application/octet-stream", "filesize": 200, "md5": "bb"}},
            {"label": "0099_swa.txt", "directoryLabel": "collected_data_text_swa",
             "dataFile": {"id": 3, "filename": "0099_swa.txt", "contentType": "text/plain", "filesize": 50, "md5": "cc"}},
            {"label": "restricted_dho.m4a", "restricted": True, "directoryLabel": "voice_dholuo",
             "dataFile": {"id": 4, "filename": "0100_dho.m4a", "contentType": "application/octet-stream", "filesize": 10, "md5": "dd"}},
        ]

        def fake_get(url, timeout=15.0, max_bytes=10_000_000, headers=None):
            if "offset=0" in url:
                return FakeResponse(self._files_page(entries))
            return FakeResponse(self._files_page([]))

        adapter = KenCorpusAdapter(http_get=fake_get)
        report = adapter.import_candidates(language="luo", limit=10, max_pages_scanned=3, page_size=4)
        self.assertIsNone(report.blocked_reason)
        accepted_ids = {c["itemId"] for c in report.accepted}
        self.assertEqual(accepted_ids, {"1", "2"})  # restricted (4) excluded, swa (3) filtered out
        self.assertEqual(len(report.quarantined), 1)
        self.assertEqual(report.quarantined[0].reason, "unverified_text_audio_alignment")
        self.assertEqual(len(report.rejected), 1)
        self.assertEqual(report.rejected[0].reason, "restricted_file")

    def test_import_candidates_no_match_is_blocked_not_fabricated(self):
        def fake_get(url, timeout=15.0, max_bytes=10_000_000, headers=None):
            return FakeResponse(self._files_page([]))

        adapter = KenCorpusAdapter(http_get=fake_get)
        report = adapter.import_candidates(language="luo", limit=10, max_pages_scanned=2, page_size=4)
        self.assertIsNotNone(report.blocked_reason)
        self.assertEqual(report.accepted, [])


if __name__ == "__main__":
    unittest.main()
