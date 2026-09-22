import bz2
import io
import unittest

from content_pipeline import net
from content_pipeline.adapters.tatoeba import TatoebaAdapter


class FakeResponse:
    def __init__(self, body: bytes):
        self.body = body
        self.status = 200
        self.headers = {}

    def json(self):
        import json
        return json.loads(self.body.decode("utf-8"))


def bz2_tsv(rows):
    text = "\n".join("\t".join(r) for r in rows) + "\n"
    return bz2.compress(text.encode("utf-8"))


LISTING_HTML = """<html><body><pre>
<a href="sna-eng_links.tsv.bz2">sna-eng_links.tsv.bz2</a> 19-Sep-2026 06:39 284
<a href="sna_sentences.tsv.bz2">sna_sentences.tsv.bz2</a> 19-Sep-2026 06:31 705
</pre></body></html>"""


class TatoebaAdapterTests(unittest.TestCase):
    def _make_get(self, sentence_rows, link_rows, translation_lookup):
        def fake_get(url, timeout=15.0, max_bytes=10_000_000, headers=None):
            if url.endswith("/sna/"):
                return FakeResponse(LISTING_HTML.encode("utf-8"))
            if "sna_sentences.tsv.bz2" in url:
                return FakeResponse(bz2_tsv(sentence_rows))
            if "sna-eng_links.tsv.bz2" in url:
                return FakeResponse(bz2_tsv(link_rows))
            if "/sentences/" in url:
                translation_id = url.rsplit("/", 1)[-1]
                payload = translation_lookup.get(translation_id)
                if payload is None:
                    raise net.NetworkUnavailable("no such sentence")
                import json
                return FakeResponse(json.dumps({"data": payload}).encode("utf-8"))
            raise AssertionError("Unexpected URL: " + url)
        return fake_get

    def _make_head(self, content_length):
        def fake_head(url, timeout=15.0, headers=None):
            return {"Content-Length": str(content_length)}
        return fake_head

    def test_discover_success(self):
        adapter = TatoebaAdapter(http_get=self._make_get([], [], {}), http_head=self._make_head(0))
        result = adapter.discover(language="sna")
        self.assertTrue(result.available)

    def test_discover_unsupported_language(self):
        def fake_get(url, timeout=15.0, max_bytes=10_000_000, headers=None):
            return FakeResponse(b"<html><body><pre></pre></body></html>")
        adapter = TatoebaAdapter(http_get=fake_get, http_head=self._make_head(0))
        result = adapter.discover(language="zzz")
        self.assertFalse(result.available)
        self.assertEqual(result.blocked_reason, "unsupported_language")

    def test_import_candidates_with_translation_and_audio_budget_block(self):
        sentence_rows = [
            ["2888670", "sna", "Ndiri kushambira munyanza."],
            ["2888671", "sna", "Ndinokuda."],
        ]
        link_rows = [
            ["2888670", "2708792"],
            ["2888671", "1434"],
        ]
        translation_lookup = {
            "2708792": {"id": 2708792, "text": "I'm swimming in the ocean.", "lang": "eng", "license": "CC BY 2.0 FR"},
            "1434": {"id": 1434, "text": "I love you.", "lang": "eng", "license": "CC BY 2.0 FR"},
        }
        adapter = TatoebaAdapter(
            http_get=self._make_get(sentence_rows, link_rows, translation_lookup),
            http_head=self._make_head(71_715_888),  # real observed size, exceeds budget
        )
        report = adapter.import_candidates(language="sna", limit=10, target_language="eng")
        self.assertIsNone(report.blocked_reason)
        self.assertEqual(len(report.accepted), 2)
        first = report.accepted[0]
        self.assertEqual(first["translations"][0]["text"], "I'm swimming in the ocean.")
        self.assertEqual(first["audioStatus"], "blocked")
        self.assertTrue(any(q.reason == "archive_budget_exceeded" for q in report.quarantined))

    def test_import_candidates_with_local_audio_metadata(self):
        import tempfile
        from pathlib import Path
        sentence_rows = [["2888670", "sna", "Ndiri kushambira munyanza."]]
        with tempfile.TemporaryDirectory() as tmp:
            audio_path = Path(tmp) / "sentences_with_audio.csv"
            audio_path.write_text("2888670\taudio123\tuser1\tCC BY 2.0 FR\thttp://example.com\n", encoding="utf-8")
            adapter = TatoebaAdapter(
                http_get=self._make_get(sentence_rows, [], {}),
                http_head=self._make_head(0),
            )
            report = adapter.import_candidates(
                language="sna", limit=10, target_language="eng", audio_metadata_path=audio_path,
            )
            self.assertEqual(report.accepted[0]["audioStatus"], "cleared")
            self.assertEqual(report.accepted[0]["audio"]["audioId"], "audio123")

    def test_network_unavailable_is_blocked_honestly(self):
        def fake_get(url, timeout=15.0, max_bytes=10_000_000, headers=None):
            raise net.NetworkUnavailable("dns failure")
        adapter = TatoebaAdapter(http_get=fake_get, http_head=self._make_head(0))
        report = adapter.import_candidates(language="sna", limit=5)
        self.assertIn("network_unavailable", report.blocked_reason)

    def test_language_mismatch_rows_rejected(self):
        sentence_rows = [["1", "eng", "Hello"], ["2", "sna", "Mhoro"]]
        adapter = TatoebaAdapter(
            http_get=self._make_get(sentence_rows, [], {}),
            http_head=self._make_head(0),
        )
        report = adapter.import_candidates(language="sna", limit=10)
        self.assertEqual(len(report.accepted), 1)
        self.assertEqual(report.accepted[0]["itemId"], "2")
        self.assertEqual(len(report.rejected), 1)
        self.assertEqual(report.rejected[0].reason, "language_mismatch")


if __name__ == "__main__":
    unittest.main()
