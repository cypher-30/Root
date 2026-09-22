import tempfile
import unittest
from pathlib import Path

from content_pipeline.reports import ImportReport, RowIssue
from content_pipeline.quarantine import QuarantineStore, QuarantineEntry, quarantine_key
from content_pipeline.idempotency import IdempotencyLedger, LedgerEntry, fingerprint_inputs


class ImportReportTests(unittest.TestCase):
    def test_counts_and_summary(self):
        report = ImportReport(
            adapter="fleurs", adapter_version="0.1.0", source_id="fleurs",
            release_id="google/fleurs@main", parameters={"language": "sn"},
            input_fingerprint="abc123",
        )
        report.accepted.append({"itemId": "1"})
        report.rejected.append(RowIssue(row_ref="row:2", reason="missing_field"))
        report.quarantined.append(RowIssue(row_ref="row:3", reason="unclear_alignment"))
        self.assertEqual(report.accepted_count, 1)
        self.assertEqual(report.rejected_count, 1)
        self.assertEqual(report.quarantined_count, 1)
        self.assertTrue(report.is_ok_to_proceed())
        summary = report.summary()
        self.assertEqual(summary["acceptedCount"], 1)

    def test_blocked_report_reports_not_ok(self):
        report = ImportReport(
            adapter="x", adapter_version="0.1.0", source_id="x", release_id="y",
            parameters={}, input_fingerprint="fp",
        )
        report.blocked_reason = "authorization_required"
        self.assertFalse(report.is_ok_to_proceed())

    def test_write_report_is_deterministic_shape(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = ImportReport(
                adapter="fleurs", adapter_version="0.1.0", source_id="fleurs",
                release_id="rel1", parameters={"language": "sn"}, input_fingerprint="fp1",
            )
            report.accepted.append({"itemId": "1"})
            path = report.write(Path(tmp))
            self.assertTrue(path.exists())
            self.assertIn("fleurs", path.name)


class QuarantineTests(unittest.TestCase):
    def test_add_and_resolve_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            store_path = Path(tmp) / "quarantine.json"
            store = QuarantineStore(store_path)
            key = quarantine_key("kencorpus", "rel1", "pair:0058:luo")
            store.add(QuarantineEntry(
                key=key, source_id="kencorpus", release_id="rel1", item_id="pair:0058:luo",
                reason="unverified_text_audio_alignment", detail="needs manual review",
                payload={"foo": "bar"},
            ))
            store.save()

            reloaded = QuarantineStore(store_path)
            self.assertEqual(len(reloaded.unresolved()), 1)
            reloaded.resolve(key, resolution="confirmed_match")
            reloaded.save()

            reloaded_again = QuarantineStore(store_path)
            self.assertEqual(len(reloaded_again.unresolved()), 0)
            self.assertEqual(reloaded_again.get(key).resolution, "confirmed_match")

    def test_stable_key_for_same_inputs(self):
        k1 = quarantine_key("tatoeba", "rel1", "item1")
        k2 = quarantine_key("tatoeba", "rel1", "item1")
        k3 = quarantine_key("tatoeba", "rel1", "item2")
        self.assertEqual(k1, k2)
        self.assertNotEqual(k1, k3)


class IdempotencyTests(unittest.TestCase):
    def test_fingerprint_stable_for_same_inputs(self):
        fp1 = fingerprint_inputs("fleurs", "rel1", {"language": "sn", "limit": 5})
        fp2 = fingerprint_inputs("fleurs", "rel1", {"limit": 5, "language": "sn"})
        self.assertEqual(fp1, fp2)

    def test_fingerprint_differs_for_different_inputs(self):
        fp1 = fingerprint_inputs("fleurs", "rel1", {"language": "sn"})
        fp2 = fingerprint_inputs("fleurs", "rel1", {"language": "sw"})
        self.assertNotEqual(fp1, fp2)

    def test_ledger_roundtrip_detects_prior_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "ledger.json"
            ledger = IdempotencyLedger(path)
            fp = fingerprint_inputs("fleurs", "rel1", {"language": "sn"})
            self.assertIsNone(ledger.lookup(fp))
            ledger.record(LedgerEntry(
                fingerprint=fp, accepted_count=3, rejected_count=0,
                quarantined_count=1, report_path="reports/x.json",
            ))
            ledger.save()

            reloaded = IdempotencyLedger(path)
            entry = reloaded.lookup(fp)
            self.assertIsNotNone(entry)
            self.assertEqual(entry.accepted_count, 3)


if __name__ == "__main__":
    unittest.main()
