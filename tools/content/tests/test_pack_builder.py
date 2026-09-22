import tempfile
import unittest
from pathlib import Path

from content_pipeline.pack_builder import (
    build_manifest, build_catalog, catalog_entry_for, AssetInput,
    PackBuildError, manifest_key_for,
)
from content_pipeline.limits import MAX_ASSET_BYTES, MAX_ASSETS_PER_PACK, MAX_PACK_TOTAL_BYTES


def make_asset_file(tmp: Path, name: str, size: int) -> Path:
    p = tmp / name
    p.write_bytes(b"a" * size)
    return p


class PackBuilderTests(unittest.TestCase):
    def _base_kwargs(self, assets):
        return dict(
            pack_id="pack.shona.pilot",
            version=1,
            language={"id": "sn", "code": "sn", "name": "Shona"},
            title="Greetings",
            objective="Greet someone",
            publication_status="development",
            phrases=[],
            lessons=[],
            assets=assets,
            credits=[{"text": "Root development editorial content"}],
        )

    def test_build_manifest_deterministic_hash_for_identical_inputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            audio = make_asset_file(tmp, "a.m4a", 1000)
            asset = AssetInput(asset_id="asset1", source_path=audio, mime="audio/mp4")
            r1 = build_manifest(**self._base_kwargs([asset]))
            r2 = build_manifest(**self._base_kwargs([asset]))
            self.assertEqual(r1.manifest_sha256, r2.manifest_sha256)

    def test_asset_relative_key_is_derived_from_asset_id_not_source_filename(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            audio = make_asset_file(tmp, "totally-unrelated-source-name.m4a", 1000)
            asset = AssetInput(asset_id="asset1", source_path=audio, mime="audio/mp4")
            result = build_manifest(**self._base_kwargs([asset]))
            self.assertEqual(result.manifest["assets"][0]["key"], "assets/audio-asset1.m4a")

    def test_build_manifest_changes_hash_when_content_changes(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            audio = make_asset_file(tmp, "a.m4a", 1000)
            asset = AssetInput(asset_id="asset1", source_path=audio, mime="audio/mp4")
            r1 = build_manifest(**self._base_kwargs([asset]))
            kwargs2 = self._base_kwargs([asset])
            kwargs2["title"] = "Different title"
            r2 = build_manifest(**kwargs2)
            self.assertNotEqual(r1.manifest_sha256, r2.manifest_sha256)

    def test_rejects_asset_over_20mib(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            big = make_asset_file(tmp, "big.m4a", MAX_ASSET_BYTES + 1)
            asset = AssetInput(asset_id="asset1", source_path=big, mime="audio/mp4")
            with self.assertRaises(PackBuildError):
                build_manifest(**self._base_kwargs([asset]))

    def test_accepts_asset_at_exactly_20mib(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            exact = make_asset_file(tmp, "exact.m4a", MAX_ASSET_BYTES)
            asset = AssetInput(asset_id="asset1", source_path=exact, mime="audio/mp4")
            result = build_manifest(**self._base_kwargs([asset]))
            self.assertEqual(result.total_asset_bytes, MAX_ASSET_BYTES)

    def test_rejects_over_256_assets(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            tiny = make_asset_file(tmp, "tiny.m4a", 10)
            assets = [
                AssetInput(asset_id="asset" + str(i), source_path=tiny, mime="audio/mp4")
                for i in range(MAX_ASSETS_PER_PACK + 1)
            ]
            with self.assertRaises(PackBuildError):
                build_manifest(**self._base_kwargs(assets))

    def test_accepts_exactly_256_assets(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            tiny = make_asset_file(tmp, "tiny.m4a", 10)
            assets = [
                AssetInput(asset_id="asset" + str(i), source_path=tiny, mime="audio/mp4")
                for i in range(MAX_ASSETS_PER_PACK)
            ]
            result = build_manifest(**self._base_kwargs(assets))
            self.assertEqual(len(result.manifest["assets"]), MAX_ASSETS_PER_PACK)

    def test_rejects_asset_id_that_fails_id_pattern(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            audio = make_asset_file(tmp, "a.m4a", 10)
            asset = AssetInput(asset_id="../escape", source_path=audio, mime="audio/mp4")
            with self.assertRaises(PackBuildError):
                build_manifest(**self._base_kwargs([asset]))

    def test_rejects_asset_id_over_120_chars(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            audio = make_asset_file(tmp, "a.m4a", 10)
            asset = AssetInput(asset_id="a" * 121, source_path=audio, mime="audio/mp4")
            with self.assertRaises(PackBuildError):
                build_manifest(**self._base_kwargs([asset]))

    def test_rejects_unsupported_mime_for_relative_key_derivation(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            audio = make_asset_file(tmp, "a.ogg", 10)
            asset = AssetInput(asset_id="asset1", source_path=audio, mime="audio/ogg")
            with self.assertRaises(PackBuildError):
                build_manifest(**self._base_kwargs([asset]))

    def test_rejects_duplicate_asset_ids(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            a = make_asset_file(tmp, "a.m4a", 10)
            b = make_asset_file(tmp, "b.m4a", 10)
            asset1 = AssetInput(asset_id="dup", source_path=a, mime="audio/mp4")
            asset2 = AssetInput(asset_id="dup", source_path=b, mime="audio/mp4")
            with self.assertRaises(PackBuildError):
                build_manifest(**self._base_kwargs([asset1, asset2]))

    def test_rejects_total_pack_payload_over_50mib(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            big = make_asset_file(tmp, "big.m4a", 20 * 1024 * 1024)
            assets = [
                AssetInput(asset_id="a" + str(i), source_path=big, mime="audio/mp4")
                for i in range(3)
            ]
            with self.assertRaises(PackBuildError):
                build_manifest(**self._base_kwargs(assets))

    def _minimal_comprehension(self):
        return {
            "kind": "choice_task",
            "task": {
                "id": "comp-task", "prompt": "Pick one",
                "choices": [{"id": "c1", "text": "A"}, {"id": "c2", "text": "B"}],
                "acceptedChoiceIds": ["c1"],
            },
        }

    def test_build_manifest_allows_null_audio_encounter_when_development(self):
        kwargs = self._base_kwargs([])
        kwargs["lessons"] = [{
            "id": "lesson1", "revision": 1, "title": "Listen", "objective": "Listen", "format": "listening",
            "activities": [{
                "id": "s1", "kind": "listening",
                "audioAssetId": None, "unavailableReason": "No authentic recording exists yet.",
                "comprehension": self._minimal_comprehension(),
            }],
            "requiredActivityIds": ["s1"],
            "linkedPhraseIds": [],
        }]
        result = build_manifest(**kwargs)
        self.assertIsNone(result.manifest["lessons"][0]["activities"][0]["audioAssetId"])

    def test_build_manifest_rejects_null_audio_encounter_when_published(self):
        kwargs = self._base_kwargs([])
        kwargs["publication_status"] = "published"
        kwargs["lessons"] = [{
            "id": "lesson1", "revision": 1, "title": "Listen", "objective": "Listen", "format": "listening",
            "activities": [{
                "id": "s1", "kind": "listening",
                "audioAssetId": None, "unavailableReason": "No authentic recording exists yet.",
                "comprehension": self._minimal_comprehension(),
            }],
            "requiredActivityIds": ["s1"],
            "linkedPhraseIds": [],
        }]
        with self.assertRaises(PackBuildError):
            build_manifest(**kwargs)

    def test_build_manifest_rejects_audio_encounter_referencing_nonexistent_asset(self):
        kwargs = self._base_kwargs([])
        kwargs["lessons"] = [{
            "id": "lesson1", "revision": 1, "title": "Listen", "objective": "Listen", "format": "listening",
            "activities": [{
                "id": "s1", "kind": "listening",
                "audioAssetId": "asset-does-not-exist",
                "comprehension": self._minimal_comprehension(),
            }],
            "requiredActivityIds": ["s1"],
            "linkedPhraseIds": [],
        }]
        with self.assertRaises(PackBuildError):
            build_manifest(**kwargs)

    def test_manifest_schema_validation_catches_bad_shape(self):
        kwargs = self._base_kwargs([])
        kwargs["publication_status"] = "not-a-real-status"
        with self.assertRaises(PackBuildError):
            build_manifest(**kwargs)

    def test_manifest_key_for_matches_parent_storage_convention(self):
        self.assertEqual(
            manifest_key_for("pack.shona.pilot", 3),
            "root_content/versions/pack.shona.pilot/3/manifest.json",
        )

    def test_build_catalog_deterministic_and_validated(self):
        pack_summary = {
            "id": "pack.shona.pilot", "version": 1,
            "language": {"id": "sn", "code": "sn", "name": "Shona"},
            "title": "Greetings", "publication": "development",
            "phraseCount": 0, "unitCount": 0,
            "lessonCount": 0, "audioCount": 0,
            "manifestKey": manifest_key_for("pack.shona.pilot", 1),
            "manifestSha256": "0" * 64, "manifestBytes": 100,
            "downloadBytes": 100, "retired": False,
        }
        doc1, bytes1, sha1 = build_catalog(1, [pack_summary])
        doc2, bytes2, sha2 = build_catalog(1, [pack_summary])
        self.assertEqual(sha1, sha2)
        self.assertEqual(bytes1, bytes2)

    def test_catalog_entry_for_computes_honest_download_bytes_and_lesson_count(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            audio = make_asset_file(tmp, "a.m4a", 1000)
            asset = AssetInput(asset_id="asset1", source_path=audio, mime="audio/mp4")
            kwargs = self._base_kwargs([asset])
            kwargs["lessons"] = [
                {
                    "id": "lesson1", "revision": 1, "title": "Greet", "objective": "Greet",
                    "format": "guided_conversation",
                    "activities": [{"id": "s1", "kind": "dialogue_turn", "speaker": "A", "text": "hi"}],
                    "requiredActivityIds": ["s1"],
                    "linkedPhraseIds": [],
                },
            ]
            result = build_manifest(**kwargs)
            entry = catalog_entry_for(result)
            self.assertEqual(entry["lessonCount"], 1)
            self.assertEqual(entry["unitCount"], 1)
            self.assertEqual(entry["downloadBytes"], len(result.manifest_bytes) + result.total_asset_bytes)
            self.assertGreater(entry["downloadBytes"], entry["manifestBytes"])
            # language/title/phraseCount/audioCount must be derived straight
            # from the built manifest, never re-typed by the caller.
            self.assertEqual(entry["language"], result.manifest["language"])
            self.assertEqual(entry["title"], result.manifest["title"])
            self.assertEqual(entry["phraseCount"], len(result.manifest["phrases"]))
            self.assertEqual(entry["audioCount"], 1)
            # Validate the derived entry itself passes catalog.schema.json.
            doc, _, _ = build_catalog(1, [entry])
            self.assertEqual(doc["entries"][0]["id"], "pack.shona.pilot")

    def test_catalog_entry_for_rejects_download_bytes_over_50mib(self):
        from content_pipeline.limits import MAX_DOWNLOAD_BYTES
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            big1 = make_asset_file(tmp, "big1.m4a", MAX_ASSET_BYTES)
            big2 = make_asset_file(tmp, "big2.m4a", MAX_ASSET_BYTES)
            small = make_asset_file(tmp, "small.m4a", MAX_PACK_TOTAL_BYTES - 2 * MAX_ASSET_BYTES)
            assets = [
                AssetInput(asset_id="a1", source_path=big1, mime="audio/mp4"),
                AssetInput(asset_id="a2", source_path=big2, mime="audio/mp4"),
                AssetInput(asset_id="a3", source_path=small, mime="audio/mp4"),
            ]
            result = build_manifest(**self._base_kwargs(assets))
            self.assertEqual(result.total_asset_bytes, MAX_PACK_TOTAL_BYTES)
            # Assets alone hit the 50 MiB ceiling exactly; adding manifest
            # bytes on top must push downloadBytes over MAX_DOWNLOAD_BYTES,
            # which catalog_entry_for must refuse rather than silently allow.
            with self.assertRaises(PackBuildError):
                catalog_entry_for(result)

    def test_catalog_entry_for_unit_count_override(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            result = build_manifest(**self._base_kwargs([]))
            entry = catalog_entry_for(result, unit_count=3)
            self.assertEqual(entry["unitCount"], 3)
            self.assertEqual(entry["lessonCount"], 0)


if __name__ == "__main__":
    unittest.main()





