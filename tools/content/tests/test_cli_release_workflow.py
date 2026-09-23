import io
import json
import shutil
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

from content_pipeline import cli

ROOT = Path(__file__).resolve().parents[3]
WORKSPACES_ROOT = ROOT / "tools" / "content" / "build" / "test-workspaces"


class ReleaseWorkflowCliTests(unittest.TestCase):
    def setUp(self):
        self.workspace = WORKSPACES_ROOT / self.id().replace(".", "_")
        if self.workspace.exists():
            shutil.rmtree(self.workspace)
        self.workspace.mkdir(parents=True)
        self.releases_root = self.workspace / "releases"
        self.catalog_dir = self.workspace / "catalog"
        self.registry_path = self.workspace / "registry.json"

    def tearDown(self):
        if self.workspace.exists():
            shutil.rmtree(self.workspace)

    def _run_cli(self, *argv):
        stdout = io.StringIO()
        stderr = io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = cli.main(list(argv))
        return code, stdout.getvalue(), stderr.getvalue()

    def _write_json(self, path: Path, payload) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    def _registry_doc(self, overrides=None):
        overrides = overrides or {}
        components = []
        for kind in ("target_text", "translation", "activity_wording", "audio"):
            component = {
                "componentKind": kind,
                "license": "CC-BY-4.0",
                "requiredCredit": "Approved source",
                "rightsStatus": "cleared",
                "reviewStatus": "native_reviewed",
                "reviewRevision": 7,
            }
            component.update(overrides.get(kind, {}))
            components.append(component)
        return {
            "schemaVersion": 1,
            "sources": [
                {
                    "sourceId": "approved-source",
                    "releaseId": "release-1",
                    "releaseUrl": "https://example.invalid/release-1",
                    "retrievedAt": "2026-09-23",
                    "components": components,
                }
            ],
        }

    def _base_pack_bundle(
        self,
        *,
        pack_name: str,
        pack_id: str,
        version: int,
        publication: str = "published",
        asset_id: str = "asset-one",
        asset_bytes: bytes = b"audio-one",
        registry_overrides=None,
        component_review_revision: int = 7,
        lesson_audio_asset_id: str | None = "asset-one",
        assets_enabled: bool = True,
        invalid_schema: bool = False,
    ) -> Path:
        pack_dir = self.workspace / pack_name
        source_assets_dir = pack_dir / "source-assets"
        source_assets_dir.mkdir(parents=True, exist_ok=True)
        audio_path = source_assets_dir / "clip.m4a"
        audio_path.write_bytes(asset_bytes)
        self._write_json(self.registry_path, self._registry_doc(registry_overrides))
        metadata = {
            "packId": pack_id,
            "version": version,
            "language": {"id": "sn", "code": "sn", "name": "Shona"},
            "title": "Published Pack " + str(version),
            "objective": "Verify local release orchestration.",
            "publication": publication,
            "credits": [{"text": "Approved source", "license": "CC-BY-4.0"}],
        }
        phrases = [
            {
                "id": "phrase-1",
                "prompt": "Mhoro",
                "meaning": "Hello",
                "audioAssetId": lesson_audio_asset_id,
                "credits": {"text": "Approved source", "license": "CC-BY-4.0"},
            }
        ]
        if invalid_schema:
            lessons = [{"id": "lesson-1", "revision": 1}]
        else:
            activity = {
                "id": "listen-1",
                "kind": "listening",
                "audioAssetId": lesson_audio_asset_id,
                "transcript": "Mhoro",
                "translation": "Hello",
                "comprehension": {
                    "kind": "choice_task",
                    "task": {
                        "id": "listen-1-task",
                        "prompt": "Which transcript means hello?",
                        "choices": [
                            {"id": "c1", "text": "Mhoro"},
                            {"id": "c2", "text": "Mhoroi"},
                        ],
                        "acceptedChoiceIds": ["c1"],
                    },
                },
            }
            if lesson_audio_asset_id is None:
                activity["unavailableReason"] = "No authentic recording exists yet."
            lessons = [
                {
                    "id": "lesson-1",
                    "revision": 1,
                    "title": "Listening",
                    "objective": "Listen to one phrase.",
                    "format": "listening",
                    "prerequisiteLessonIds": [],
                    "linkedPhraseIds": ["phrase-1"],
                    "activities": [activity],
                    "requiredActivityIds": ["listen-1"],
                }
            ]
        assets = []
        if assets_enabled:
            assets = [
                {
                    "id": asset_id,
                    "sourcePath": "source-assets/clip.m4a",
                    "mimeType": "audio/mp4",
                }
            ]
        components = [
            {
                "sourceId": "approved-source",
                "releaseId": "release-1",
                "componentKind": kind,
                "reviewRevision": component_review_revision,
            }
            for kind in ("target_text", "translation", "activity_wording", "audio")
        ]
        self._write_json(pack_dir / "pack.json", metadata)
        self._write_json(pack_dir / "phrases.json", phrases)
        self._write_json(pack_dir / "lessons.json", lessons)
        self._write_json(pack_dir / "assets.json", assets)
        self._write_json(pack_dir / "components.json", components)
        return pack_dir

    def _build_pack(self, pack_dir: Path):
        return self._run_cli(
            "build-pack",
            str(pack_dir),
            "--registry",
            str(self.registry_path),
            "--releases-root",
            str(self.releases_root),
        )

    def _build_catalog(self, *release_dirs: Path, catalog_dir: Path | None = None, revision: int | None = None):
        argv = ["build-catalog"] + [str(path) for path in release_dirs]
        if catalog_dir is not None:
            argv += ["--catalog-dir", str(catalog_dir)]
        if revision is not None:
            argv += ["--catalog-revision", str(revision)]
        return self._run_cli(*argv)

    def test_build_pack_writes_local_release_artifacts(self):
        pack_dir = self._base_pack_bundle(pack_name="build-success", pack_id="pack.release.workflow", version=2)
        code, stdout, stderr = self._build_pack(pack_dir)
        self.assertEqual(code, 0, stderr)
        result = json.loads(stdout)
        release_dir = Path(result["releaseDir"])
        self.assertTrue((release_dir / "manifest.json").is_file())
        self.assertTrue((release_dir / "manifest.sha256").is_file())
        self.assertTrue((release_dir / "asset-files.json").is_file())
        self.assertTrue((release_dir / "catalog-entry.json").is_file())
        asset_rows = json.loads((release_dir / "asset-files.json").read_text(encoding="utf-8"))
        self.assertEqual(asset_rows[0]["assetId"], "asset-one")
        self.assertTrue(asset_rows[0]["key"].startswith("assets/audio-"))
        catalog_entry = json.loads((release_dir / "catalog-entry.json").read_text(encoding="utf-8"))
        self.assertEqual(catalog_entry["id"], "pack.release.workflow")
        self.assertFalse(catalog_entry["retired"])

    def test_build_pack_rejects_non_published_pack(self):
        pack_dir = self._base_pack_bundle(
            pack_name="build-development-blocked",
            pack_id="pack.release.workflow",
            version=2,
            publication="development",
        )
        code, stdout, stderr = self._build_pack(pack_dir)
        self.assertEqual(code, 1)
        self.assertEqual(stdout, "")
        self.assertIn("publication gate", stderr)
        self.assertFalse((_release_dir := self.releases_root / "pack.release.workflow" / "2").exists())

    def test_build_pack_rejects_unapproved_registry_component(self):
        pack_dir = self._base_pack_bundle(
            pack_name="build-audio-review-blocked",
            pack_id="pack.release.workflow",
            version=2,
            registry_overrides={"audio": {"reviewStatus": "source_checked"}},
        )
        code, stdout, stderr = self._build_pack(pack_dir)
        self.assertEqual(code, 1)
        self.assertEqual(stdout, "")
        self.assertIn("failed publish gate", stderr)
        self.assertFalse((self.releases_root / "pack.release.workflow" / "2").exists())

    def test_build_pack_rejects_stale_review_revision(self):
        pack_dir = self._base_pack_bundle(
            pack_name="build-stale-review-blocked",
            pack_id="pack.release.workflow",
            version=2,
            component_review_revision=8,
        )
        code, stdout, stderr = self._build_pack(pack_dir)
        self.assertEqual(code, 1)
        self.assertEqual(stdout, "")
        self.assertIn("review-revision gate", stderr)
        self.assertFalse((self.releases_root / "pack.release.workflow" / "2").exists())

    def test_build_pack_rejects_schema_invalid_inputs(self):
        pack_dir = self._base_pack_bundle(
            pack_name="build-schema-blocked",
            pack_id="pack.release.workflow",
            version=2,
            invalid_schema=True,
        )
        code, stdout, stderr = self._build_pack(pack_dir)
        self.assertEqual(code, 1)
        self.assertEqual(stdout, "")
        self.assertIn("schema validation", stderr)
        self.assertFalse((self.releases_root / "pack.release.workflow" / "2").exists())

    def test_build_pack_rejects_published_listening_without_audio(self):
        pack_dir = self._base_pack_bundle(
            pack_name="build-audio-gate-blocked",
            pack_id="pack.release.workflow",
            version=2,
            lesson_audio_asset_id=None,
            assets_enabled=False,
        )
        code, stdout, stderr = self._build_pack(pack_dir)
        self.assertEqual(code, 1)
        self.assertEqual(stdout, "")
        self.assertIn("audio-asset publication gate", stderr)
        self.assertFalse((self.releases_root / "pack.release.workflow" / "2").exists())

    def test_build_catalog_is_deterministic_for_same_inputs(self):
        pack_dir = self._base_pack_bundle(pack_name="catalog-deterministic", pack_id="pack.release.workflow", version=2)
        code, stdout, stderr = self._build_pack(pack_dir)
        self.assertEqual(code, 0, stderr)
        release_dir = Path(json.loads(stdout)["releaseDir"])
        catalog_a = self.workspace / "catalog-a"
        catalog_b = self.workspace / "catalog-b"
        first_code, first_stdout, first_stderr = self._build_catalog(release_dir, catalog_dir=catalog_a, revision=9)
        second_code, second_stdout, second_stderr = self._build_catalog(release_dir, catalog_dir=catalog_b, revision=9)
        self.assertEqual(first_code, 0, first_stderr)
        self.assertEqual(second_code, 0, second_stderr)
        first = json.loads(first_stdout)
        second = json.loads(second_stdout)
        self.assertEqual(first["catalogSha256"], second["catalogSha256"])
        self.assertEqual(
            Path(first["catalogPath"]).read_bytes(),
            Path(second["catalogPath"]).read_bytes(),
        )

    def test_build_catalog_bumps_revision_from_existing_catalogs(self):
        pack_dir = self._base_pack_bundle(pack_name="catalog-revision", pack_id="pack.release.workflow", version=2)
        code, stdout, stderr = self._build_pack(pack_dir)
        self.assertEqual(code, 0, stderr)
        release_dir = Path(json.loads(stdout)["releaseDir"])
        first_code, first_stdout, first_stderr = self._build_catalog(release_dir, catalog_dir=self.catalog_dir)
        second_code, second_stdout, second_stderr = self._build_catalog(release_dir, catalog_dir=self.catalog_dir)
        self.assertEqual(first_code, 0, first_stderr)
        self.assertEqual(second_code, 0, second_stderr)
        self.assertEqual(json.loads(first_stdout)["catalogRevision"], 1)
        self.assertEqual(json.loads(second_stdout)["catalogRevision"], 2)

    def test_retire_builds_new_catalog_revision_without_mutating_old_one(self):
        pack_dir = self._base_pack_bundle(pack_name="retire-pack", pack_id="pack.release.workflow", version=2)
        code, stdout, stderr = self._build_pack(pack_dir)
        self.assertEqual(code, 0, stderr)
        release_dir = Path(json.loads(stdout)["releaseDir"])
        catalog_code, catalog_stdout, catalog_stderr = self._build_catalog(release_dir, catalog_dir=self.catalog_dir)
        self.assertEqual(catalog_code, 0, catalog_stderr)
        original_catalog_path = Path(json.loads(catalog_stdout)["catalogPath"])
        original_entry = json.loads(original_catalog_path.read_text(encoding="utf-8"))["entries"][0]
        self.assertFalse(original_entry["retired"])
        retire_code, retire_stdout, retire_stderr = self._run_cli(
            "retire",
            "pack.release.workflow",
            "2",
            "--catalog-dir",
            str(self.catalog_dir),
            "--releases-root",
            str(self.releases_root),
        )
        self.assertEqual(retire_code, 0, retire_stderr)
        retired_catalog_path = Path(json.loads(retire_stdout)["catalogPath"])
        retired_entry = json.loads(retired_catalog_path.read_text(encoding="utf-8"))["entries"][0]
        self.assertTrue(retired_entry["retired"])
        unchanged_original = json.loads(original_catalog_path.read_text(encoding="utf-8"))["entries"][0]
        self.assertFalse(unchanged_original["retired"])
        self.assertNotEqual(original_catalog_path, retired_catalog_path)

    def test_rollback_uses_real_older_built_release_summary(self):
        pack_v1 = self._base_pack_bundle(
            pack_name="rollback-v1",
            pack_id="pack.release.workflow",
            version=1,
            asset_bytes=b"audio-v1",
        )
        pack_v2 = self._base_pack_bundle(
            pack_name="rollback-v2",
            pack_id="pack.release.workflow",
            version=2,
            asset_bytes=b"audio-v2",
        )
        first_code, first_stdout, first_stderr = self._build_pack(pack_v1)
        second_code, second_stdout, second_stderr = self._build_pack(pack_v2)
        self.assertEqual(first_code, 0, first_stderr)
        self.assertEqual(second_code, 0, second_stderr)
        release_v2 = Path(json.loads(second_stdout)["releaseDir"])
        catalog_code, catalog_stdout, catalog_stderr = self._build_catalog(release_v2, catalog_dir=self.catalog_dir)
        self.assertEqual(catalog_code, 0, catalog_stderr)
        original_catalog_path = Path(json.loads(catalog_stdout)["catalogPath"])
        original_entry = json.loads(original_catalog_path.read_text(encoding="utf-8"))["entries"][0]
        self.assertEqual(original_entry["version"], 2)
        rollback_code, rollback_stdout, rollback_stderr = self._run_cli(
            "rollback",
            "pack.release.workflow",
            "--to-version",
            "1",
            "--catalog-dir",
            str(self.catalog_dir),
            "--releases-root",
            str(self.releases_root),
        )
        self.assertEqual(rollback_code, 0, rollback_stderr)
        rolled_back_catalog_path = Path(json.loads(rollback_stdout)["catalogPath"])
        rolled_back_entry = json.loads(rolled_back_catalog_path.read_text(encoding="utf-8"))["entries"][0]
        release_v1_entry = json.loads((self.releases_root / "pack.release.workflow" / "1" / "catalog-entry.json").read_text(encoding="utf-8"))
        self.assertEqual(rolled_back_entry["version"], 1)
        self.assertEqual(rolled_back_entry["manifestSha256"], release_v1_entry["manifestSha256"])
        self.assertEqual(original_entry["version"], 2)

    def test_rollback_rejects_version_that_was_never_built(self):
        pack_dir = self._base_pack_bundle(pack_name="rollback-missing", pack_id="pack.release.workflow", version=2)
        code, stdout, stderr = self._build_pack(pack_dir)
        self.assertEqual(code, 0, stderr)
        release_dir = Path(json.loads(stdout)["releaseDir"])
        catalog_code, catalog_stdout, catalog_stderr = self._build_catalog(release_dir, catalog_dir=self.catalog_dir)
        self.assertEqual(catalog_code, 0, catalog_stderr)
        rollback_code, rollback_stdout, rollback_stderr = self._run_cli(
            "rollback",
            "pack.release.workflow",
            "--to-version",
            "99",
            "--catalog-dir",
            str(self.catalog_dir),
            "--releases-root",
            str(self.releases_root),
        )
        self.assertEqual(rollback_code, 1)
        self.assertEqual(rollback_stdout, "")
        self.assertIn("never built locally", rollback_stderr)


if __name__ == "__main__":
    unittest.main()
