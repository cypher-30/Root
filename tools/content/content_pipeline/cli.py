"""Command-line entry point for the content pipeline: discover, import,
validate, and local release-artifact orchestration, matching docs/CONTENT.md's
runbook. Publishing remains a separate explicit step; these commands never
load credentials or make network calls."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

from .adapters.common_voice import CommonVoiceAdapter
from .adapters.fleurs import FleursAdapter
from .adapters.kencorpus import KenCorpusAdapter
from .adapters.tatoeba import TatoebaAdapter
from .canonical import canonical_json_bytes, sha256_hex
from .gates import (
    check_component_publishable,
    check_publication_status_allowed,
    check_review_revision_matches,
)
from .limits import DEFAULT_SMOKE_ITEM_LIMIT, manifest_key_for
from .pack_builder import AssetInput, PackBuildError, build_catalog, build_manifest, catalog_entry_for
from .registry import SourceRegistry
from .schemas import SchemaDependencyMissing, get_catalog, repo_root

ADAPTERS = {
    "fleurs": FleursAdapter,
    "kencorpus": KenCorpusAdapter,
    "common_voice": CommonVoiceAdapter,
    "tatoeba": TatoebaAdapter,
}

DEFAULT_RELEASES_ROOT = repo_root() / "tools" / "content" / "build" / "releases"
DEFAULT_CATALOG_DIR = DEFAULT_RELEASES_ROOT / "catalog"
_PACK_METADATA_FILENAME = "pack.json"
_PHRASES_FILENAME = "phrases.json"
_LESSONS_FILENAME = "lessons.json"
_ASSETS_FILENAME = "assets.json"
_COMPONENTS_FILENAME = "components.json"
_MANIFEST_FILENAME = "manifest.json"
_MANIFEST_SHA_FILENAME = "manifest.sha256"
_ASSET_FILES_FILENAME = "asset-files.json"
_CATALOG_ENTRY_FILENAME = "catalog-entry.json"
_CATALOG_FILENAME_RE = re.compile(r"^catalog-r(\d+)\.json$")


class CliCommandError(RuntimeError):
    """Raised for a user-facing CLI failure that should be reported as a
    concise non-zero exit, never a Python traceback."""


def _json_stdout(payload: dict) -> None:
    print(json.dumps(payload, indent=2, ensure_ascii=False))


def _load_json(path: Path, label: str):
    if not path.is_file():
        raise CliCommandError(label + " not found: " + str(path))
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def _write_json(path: Path, payload) -> None:
    path.write_bytes(canonical_json_bytes(payload))


def _write_sha256(path: Path, sha256_value: str) -> None:
    path.write_text(sha256_value + "\n", encoding="utf-8")


def _resolve_input_path(base_dir: Path, raw_path: str) -> Path:
    path = Path(raw_path)
    if not path.is_absolute():
        path = base_dir / path
    return path.resolve()


def _require_mapping(value, label: str) -> dict:
    if not isinstance(value, dict):
        raise CliCommandError(label + " must be a JSON object")
    return value


def _require_list(value, label: str) -> list:
    if not isinstance(value, list):
        raise CliCommandError(label + " must be a JSON array")
    return value


def _required_field(mapping: dict, field_name: str, label: str):
    if field_name not in mapping:
        raise CliCommandError(label + " is missing required field '" + field_name + "'")
    return mapping[field_name]


def _release_dir_for(releases_root: Path, pack_id: str, version: int) -> Path:
    return releases_root / pack_id / str(version)


def _catalog_paths_for_revision(catalog_dir: Path, revision: int) -> tuple[Path, Path]:
    base = catalog_dir / ("catalog-r" + str(revision).zfill(6))
    return base.with_suffix(".json"), base.with_suffix(".sha256")


def _existing_catalog_revisions(catalog_dir: Path) -> list[int]:
    if not catalog_dir.exists():
        return []
    revisions = []
    for path in catalog_dir.glob("catalog-r*.json"):
        match = _CATALOG_FILENAME_RE.match(path.name)
        if match:
            revisions.append(int(match.group(1)))
    return sorted(revisions)


def _resolved_catalog_revision(catalog_dir: Path, override: int | None) -> int:
    if override is not None:
        if override < 1:
            raise CliCommandError("catalog revision must be >= 1")
        return override
    revisions = _existing_catalog_revisions(catalog_dir)
    return (max(revisions) + 1) if revisions else 1


def _latest_catalog_path(catalog_dir: Path) -> Path:
    revisions = _existing_catalog_revisions(catalog_dir)
    if not revisions:
        raise CliCommandError("No built catalog exists under " + str(catalog_dir))
    path, _ = _catalog_paths_for_revision(catalog_dir, revisions[-1])
    return path


def _load_catalog_document(explicit_path: str | None, catalog_dir: Path) -> tuple[Path, dict]:
    path = Path(explicit_path).resolve() if explicit_path else _latest_catalog_path(catalog_dir)
    return path, _require_mapping(_load_json(path, "Catalog"), "Catalog")


def _required_component_kinds(pack_metadata: dict, phrases: list, lessons: list, assets: list) -> set[str]:
    required = set()
    if phrases:
        required.add("target_text")
    if any(phrase.get("meaning") not in (None, "") for phrase in phrases):
        required.add("translation")
    if pack_metadata.get("title") or pack_metadata.get("objective") or lessons:
        required.add("activity_wording")
    if assets or any(phrase.get("audioAssetId") is not None for phrase in phrases):
        required.add("audio")
    for lesson in lessons:
        for activity in lesson.get("activities", []):
            if activity.get("audioAssetId") is not None:
                required.add("audio")
                break
        if "audio" in required:
            break
    return required


def _validate_components_for_build(
    registry: SourceRegistry,
    components_doc: list,
    required_kinds: set[str],
    publication_status: str,
) -> list[dict]:
    publication_gate = check_publication_status_allowed(publication_status)
    if not publication_gate.allowed:
        raise CliCommandError("Pack failed publication gate: " + publication_gate.reason)

    normalized = []
    present_kinds = set()
    for i, raw_component in enumerate(components_doc):
        component = _require_mapping(raw_component, "components.json[" + str(i) + "]")
        source_id = _required_field(component, "sourceId", "components.json[" + str(i) + "]")
        release_id = _required_field(component, "releaseId", "components.json[" + str(i) + "]")
        component_kind = _required_field(component, "componentKind", "components.json[" + str(i) + "]")
        review_revision = _required_field(component, "reviewRevision", "components.json[" + str(i) + "]")
        if not isinstance(review_revision, int) or review_revision < 1:
            raise CliCommandError(
                "components.json[" + str(i) + "].reviewRevision must be a positive integer"
            )
        record = registry.component_status(source_id, release_id, component_kind)
        publishable = check_component_publishable(record)
        if not publishable.allowed:
            raise CliCommandError(
                "Component " + component_kind + " from (" + source_id + ", " + release_id
                + ") failed publish gate: " + publishable.reason
            )
        review_gate = check_review_revision_matches(record, current_revision=review_revision)
        if not review_gate.allowed:
            raise CliCommandError(
                "Component " + component_kind + " from (" + source_id + ", " + release_id
                + ") failed review-revision gate: " + review_gate.reason
            )
        normalized.append({
            "sourceId": source_id,
            "releaseId": release_id,
            "componentKind": component_kind,
            "reviewRevision": review_revision,
        })
        present_kinds.add(component_kind)

    missing = sorted(required_kinds - present_kinds)
    if missing:
        raise CliCommandError(
            "components.json is missing required approved component kinds: " + ", ".join(missing)
        )
    return normalized


def _load_pack_inputs(pack_dir: Path) -> tuple[dict, list, list, list, list]:
    metadata = _require_mapping(_load_json(pack_dir / _PACK_METADATA_FILENAME, _PACK_METADATA_FILENAME), _PACK_METADATA_FILENAME)
    phrases = _require_list(_load_json(pack_dir / _PHRASES_FILENAME, _PHRASES_FILENAME), _PHRASES_FILENAME)
    lessons = _require_list(_load_json(pack_dir / _LESSONS_FILENAME, _LESSONS_FILENAME), _LESSONS_FILENAME)
    components = _require_list(_load_json(pack_dir / _COMPONENTS_FILENAME, _COMPONENTS_FILENAME), _COMPONENTS_FILENAME)
    assets_path = pack_dir / _ASSETS_FILENAME
    assets_doc = _require_list(_load_json(assets_path, _ASSETS_FILENAME), _ASSETS_FILENAME) if assets_path.exists() else []
    assets = []
    for i, raw_asset in enumerate(assets_doc):
        asset = _require_mapping(raw_asset, _ASSETS_FILENAME + "[" + str(i) + "]")
        source_path = _resolve_input_path(
            pack_dir,
            str(_required_field(asset, "sourcePath", _ASSETS_FILENAME + "[" + str(i) + "]")),
        )
        assets.append(
            AssetInput(
                asset_id=str(_required_field(asset, "id", _ASSETS_FILENAME + "[" + str(i) + "]")),
                source_path=source_path,
                mime=str(_required_field(asset, "mimeType", _ASSETS_FILENAME + "[" + str(i) + "]")),
                duration_ms=asset.get("durationMs"),
                sample_rate_hz=asset.get("sampleRateHz"),
                codec=asset.get("codec"),
                credits=asset.get("credits"),
            )
        )
    return metadata, phrases, lessons, assets, components


def _asset_manifest_rows(result) -> list[dict]:
    by_id = {asset["id"]: asset for asset in result.manifest["assets"]}
    rows = []
    for asset_input, asset_sha256, asset_bytes in result.asset_files:
        manifest_asset = by_id[asset_input.asset_id]
        rows.append({
            "assetId": asset_input.asset_id,
            "sourcePath": str(asset_input.source_path),
            "key": manifest_asset["key"],
            "sha256": asset_sha256,
            "bytes": asset_bytes,
            "mimeType": manifest_asset["mimeType"],
        })
    return rows


def _load_built_release_entry(release_dir: Path) -> dict:
    entry = _require_mapping(
        _load_json(release_dir / _CATALOG_ENTRY_FILENAME, _CATALOG_ENTRY_FILENAME),
        _CATALOG_ENTRY_FILENAME,
    )
    manifest_bytes = (release_dir / _MANIFEST_FILENAME).read_bytes()
    manifest_sha256 = sha256_hex(manifest_bytes)
    recorded_manifest_sha256 = (release_dir / _MANIFEST_SHA_FILENAME).read_text(encoding="utf-8").strip()
    if recorded_manifest_sha256 != manifest_sha256:
        raise CliCommandError(
            "Built manifest hash mismatch in " + str(release_dir) + ": "
            + recorded_manifest_sha256 + " != " + manifest_sha256
        )
    asset_rows = _require_list(
        _load_json(release_dir / _ASSET_FILES_FILENAME, _ASSET_FILES_FILENAME),
        _ASSET_FILES_FILENAME,
    )
    total_asset_bytes = 0
    for i, row in enumerate(asset_rows):
        asset_row = _require_mapping(row, _ASSET_FILES_FILENAME + "[" + str(i) + "]")
        total_asset_bytes += int(_required_field(asset_row, "bytes", _ASSET_FILES_FILENAME + "[" + str(i) + "]"))
    if entry.get("manifestSha256") != manifest_sha256:
        raise CliCommandError("catalog-entry.json manifestSha256 does not match manifest.json in " + str(release_dir))
    if entry.get("manifestBytes") != len(manifest_bytes):
        raise CliCommandError("catalog-entry.json manifestBytes does not match manifest.json in " + str(release_dir))
    if entry.get("downloadBytes") != len(manifest_bytes) + total_asset_bytes:
        raise CliCommandError("catalog-entry.json downloadBytes does not match built asset-files.json in " + str(release_dir))
    if entry.get("manifestKey") != manifest_key_for(str(entry.get("id")), int(entry.get("version"))):
        raise CliCommandError("catalog-entry.json manifestKey does not match id/version in " + str(release_dir))
    return entry


def _write_catalog_build(catalog_dir: Path, revision: int, entries: list[dict]) -> dict:
    sorted_entries = sorted(entries, key=lambda entry: (str(entry["id"]), int(entry["version"])))
    catalog_doc, catalog_bytes, catalog_sha256 = build_catalog(revision, sorted_entries)
    catalog_path, sha_path = _catalog_paths_for_revision(catalog_dir, revision)
    if catalog_path.exists() or sha_path.exists():
        raise CliCommandError("Catalog revision already exists locally: " + str(catalog_path))
    catalog_dir.mkdir(parents=True, exist_ok=True)
    catalog_path.write_bytes(catalog_bytes)
    _write_sha256(sha_path, catalog_sha256)
    return {
        "catalogRevision": revision,
        "catalogPath": str(catalog_path),
        "catalogSha256Path": str(sha_path),
        "catalogSha256": catalog_sha256,
        "entryCount": len(sorted_entries),
    }


def cmd_discover(args: argparse.Namespace) -> int:
    adapter_cls = ADAPTERS[args.source]
    adapter = adapter_cls() if args.source != "common_voice" else adapter_cls(
        release_dir=Path(args.release_dir) if args.release_dir else None
    )
    result = adapter.discover(language=args.language) if args.language else adapter.discover()
    print(json.dumps(result.__dict__, indent=2, default=str))
    return 0 if result.available else 1


def cmd_import(args: argparse.Namespace) -> int:
    adapter_cls = ADAPTERS[args.source]
    adapter = adapter_cls() if args.source != "common_voice" else adapter_cls(
        release_dir=Path(args.release_dir) if args.release_dir else None
    )
    report = adapter.import_candidates(language=args.language, limit=args.limit)
    out_dir = Path(args.out_dir) if args.out_dir else repo_root() / "tools" / "content" / "build" / "reports"
    path = report.write(out_dir)
    print("Wrote report to " + str(path))
    print(json.dumps(report.summary(), indent=2, default=str))
    return 0 if report.is_ok_to_proceed() else 1


def cmd_validate_registry(args: argparse.Namespace) -> int:
    registry = SourceRegistry.load()
    try:
        errors = registry.validate_against_schema()
    except SchemaDependencyMissing as e:
        print("MISSING DEPENDENCY: " + str(e), file=sys.stderr)
        return 2
    if errors:
        for e in errors:
            print("ERROR: " + e, file=sys.stderr)
        return 1
    print("Registry OK.")
    return 0


def cmd_validate_fixtures(args: argparse.Namespace) -> int:
    try:
        catalog = get_catalog()
    except SchemaDependencyMissing as e:
        print("MISSING DEPENDENCY: " + str(e), file=sys.stderr)
        return 2
    fixtures_dir = repo_root() / "content" / "fixtures"
    failures = 0
    checked = 0
    for kind in ("valid", "invalid"):
        for path in sorted((fixtures_dir / kind).glob("*.json")):
            checked += 1
            schema_filename = path.stem + ".schema.json"
            try:
                with open(path, "r", encoding="utf-8") as f:
                    instance = json.load(f)
                errors = catalog.validate(schema_filename, instance)
            except KeyError:
                print("SKIP (no matching schema): " + str(path))
                continue
            is_valid = len(errors) == 0
            expected_valid = kind == "valid"
            status = "OK" if is_valid == expected_valid else "MISMATCH"
            if status == "MISMATCH":
                failures += 1
            print(status + ": " + str(path) + (" errors=" + str(errors) if errors else ""))
    if checked == 0:
        print("ERROR: no fixture files found under " + str(fixtures_dir), file=sys.stderr)
        return 2
    return 1 if failures else 0


def cmd_build_pack(args: argparse.Namespace) -> int:
    pack_dir = Path(args.pack_dir).resolve()
    if not pack_dir.is_dir():
        raise CliCommandError("Pack directory not found: " + str(pack_dir))
    releases_root = Path(args.releases_root).resolve() if args.releases_root else DEFAULT_RELEASES_ROOT
    registry = SourceRegistry.load(Path(args.registry).resolve()) if args.registry else SourceRegistry.load()
    metadata, phrases, lessons, assets, components = _load_pack_inputs(pack_dir)
    pack_id = str(_required_field(metadata, "packId", _PACK_METADATA_FILENAME))
    version = _required_field(metadata, "version", _PACK_METADATA_FILENAME)
    if not isinstance(version, int) or version < 1:
        raise CliCommandError("pack.json version must be a positive integer")
    required_kinds = _required_component_kinds(metadata, phrases, lessons, assets)
    normalized_components = _validate_components_for_build(
        registry,
        components,
        required_kinds,
        str(_required_field(metadata, "publication", _PACK_METADATA_FILENAME)),
    )
    result = build_manifest(
        pack_id=pack_id,
        version=version,
        language=_required_field(metadata, "language", _PACK_METADATA_FILENAME),
        title=str(_required_field(metadata, "title", _PACK_METADATA_FILENAME)),
        objective=str(_required_field(metadata, "objective", _PACK_METADATA_FILENAME)),
        publication_status=str(_required_field(metadata, "publication", _PACK_METADATA_FILENAME)),
        phrases=phrases,
        lessons=lessons,
        assets=assets,
        credits=_required_field(metadata, "credits", _PACK_METADATA_FILENAME),
    )
    release_dir = _release_dir_for(releases_root, pack_id, version)
    if release_dir.exists():
        raise CliCommandError("Release directory already exists: " + str(release_dir))
    release_dir.mkdir(parents=True, exist_ok=False)
    manifest_path = release_dir / _MANIFEST_FILENAME
    manifest_sha_path = release_dir / _MANIFEST_SHA_FILENAME
    asset_files_path = release_dir / _ASSET_FILES_FILENAME
    catalog_entry_path = release_dir / _CATALOG_ENTRY_FILENAME
    manifest_path.write_bytes(result.manifest_bytes)
    _write_sha256(manifest_sha_path, result.manifest_sha256)
    _write_json(asset_files_path, _asset_manifest_rows(result))
    _write_json(catalog_entry_path, catalog_entry_for(result))
    _json_stdout({
        "packId": pack_id,
        "version": version,
        "releaseDir": str(release_dir),
        "manifestPath": str(manifest_path),
        "manifestSha256Path": str(manifest_sha_path),
        "assetFilesPath": str(asset_files_path),
        "catalogEntryPath": str(catalog_entry_path),
        "manifestSha256": result.manifest_sha256,
        "assetCount": len(result.manifest["assets"]),
        "totalAssetBytes": result.total_asset_bytes,
        "approvedComponents": normalized_components,
    })
    return 0


def cmd_build_catalog(args: argparse.Namespace) -> int:
    release_dirs = [Path(path).resolve() for path in args.release_dirs]
    catalog_dir = Path(args.catalog_dir).resolve() if args.catalog_dir else DEFAULT_CATALOG_DIR
    entries = []
    seen_pack_ids = set()
    for release_dir in release_dirs:
        entry = _load_built_release_entry(release_dir)
        if entry["id"] in seen_pack_ids:
            raise CliCommandError("Duplicate pack id in build-catalog inputs: " + str(entry["id"]))
        seen_pack_ids.add(entry["id"])
        entries.append(entry)
    result = _write_catalog_build(catalog_dir, _resolved_catalog_revision(catalog_dir, args.catalog_revision), entries)
    _json_stdout(result)
    return 0


def cmd_retire(args: argparse.Namespace) -> int:
    releases_root = Path(args.releases_root).resolve() if args.releases_root else DEFAULT_RELEASES_ROOT
    catalog_dir = Path(args.catalog_dir).resolve() if args.catalog_dir else DEFAULT_CATALOG_DIR
    source_catalog_path, catalog_doc = _load_catalog_document(args.catalog, catalog_dir)
    source_entries = _require_list(_required_field(catalog_doc, "entries", str(source_catalog_path)), str(source_catalog_path) + ".entries")
    replacement_entry = dict(_load_built_release_entry(_release_dir_for(releases_root, args.pack_id, args.version)))
    replacement_entry["retired"] = True
    new_entries = []
    found = False
    for raw_entry in source_entries:
        entry = _require_mapping(raw_entry, "catalog entry")
        if entry.get("id") == args.pack_id:
            if entry.get("version") != args.version:
                raise CliCommandError(
                    "Latest catalog entry for " + args.pack_id + " is version " + str(entry.get("version"))
                    + ", not requested retire version " + str(args.version)
                )
            new_entries.append(replacement_entry)
            found = True
        else:
            new_entries.append(entry)
    if not found:
        raise CliCommandError("Pack " + args.pack_id + " version " + str(args.version) + " is not present in " + str(source_catalog_path))
    result = _write_catalog_build(catalog_dir, _resolved_catalog_revision(catalog_dir, args.catalog_revision), new_entries)
    result["sourceCatalogPath"] = str(source_catalog_path)
    _json_stdout(result)
    return 0


def cmd_rollback(args: argparse.Namespace) -> int:
    releases_root = Path(args.releases_root).resolve() if args.releases_root else DEFAULT_RELEASES_ROOT
    catalog_dir = Path(args.catalog_dir).resolve() if args.catalog_dir else DEFAULT_CATALOG_DIR
    source_catalog_path, catalog_doc = _load_catalog_document(args.catalog, catalog_dir)
    source_entries = _require_list(_required_field(catalog_doc, "entries", str(source_catalog_path)), str(source_catalog_path) + ".entries")
    rollback_dir = _release_dir_for(releases_root, args.pack_id, args.to_version)
    if not rollback_dir.is_dir():
        raise CliCommandError(
            "Rollback target version was never built locally: " + str(rollback_dir)
        )
    replacement_entry = dict(_load_built_release_entry(rollback_dir))
    replacement_entry["retired"] = False
    new_entries = []
    found = False
    for raw_entry in source_entries:
        entry = _require_mapping(raw_entry, "catalog entry")
        if entry.get("id") == args.pack_id:
            new_entries.append(replacement_entry)
            found = True
        else:
            new_entries.append(entry)
    if not found:
        raise CliCommandError("Pack " + args.pack_id + " is not present in " + str(source_catalog_path))
    result = _write_catalog_build(catalog_dir, _resolved_catalog_revision(catalog_dir, args.catalog_revision), new_entries)
    result["sourceCatalogPath"] = str(source_catalog_path)
    _json_stdout(result)
    return 0


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="content-pipeline")
    sub = parser.add_subparsers(dest="command", required=True)

    p_discover = sub.add_parser("discover", help="Metadata-first source discovery.")
    p_discover.add_argument("source", choices=list(ADAPTERS.keys()))
    p_discover.add_argument("--language", default=None)
    p_discover.add_argument("--release-dir", default=None)
    p_discover.set_defaults(func=cmd_discover)

    p_import = sub.add_parser("import", help="Bounded candidate import.")
    p_import.add_argument("source", choices=list(ADAPTERS.keys()))
    p_import.add_argument("--language", required=True)
    p_import.add_argument("--limit", type=int, default=DEFAULT_SMOKE_ITEM_LIMIT)
    p_import.add_argument("--out-dir", default=None)
    p_import.add_argument("--release-dir", default=None)
    p_import.set_defaults(func=cmd_import)

    p_validate_registry = sub.add_parser("validate-registry", help="Validate content/sources/registry.json.")
    p_validate_registry.set_defaults(func=cmd_validate_registry)

    p_validate_fixtures = sub.add_parser("validate-fixtures", help="Validate content/fixtures/** against schemas.")
    p_validate_fixtures.set_defaults(func=cmd_validate_fixtures)

    p_build_pack = sub.add_parser("build-pack", help="Build one immutable local pack release (no network).")
    p_build_pack.add_argument("pack_dir")
    p_build_pack.add_argument("--releases-root", default=None)
    p_build_pack.add_argument("--registry", default=None)
    p_build_pack.set_defaults(func=cmd_build_pack)

    p_build_catalog = sub.add_parser("build-catalog", help="Build one local catalog revision from built pack releases.")
    p_build_catalog.add_argument("release_dirs", nargs="+")
    p_build_catalog.add_argument("--catalog-dir", default=None)
    p_build_catalog.add_argument("--catalog-revision", type=int, default=None)
    p_build_catalog.set_defaults(func=cmd_build_catalog)

    p_retire = sub.add_parser("retire", help="Build a new local catalog revision retiring one pack version.")
    p_retire.add_argument("pack_id")
    p_retire.add_argument("version", type=int)
    p_retire.add_argument("--catalog", default=None)
    p_retire.add_argument("--catalog-dir", default=None)
    p_retire.add_argument("--releases-root", default=None)
    p_retire.add_argument("--catalog-revision", type=int, default=None)
    p_retire.set_defaults(func=cmd_retire)

    p_rollback = sub.add_parser("rollback", help="Build a new local catalog revision pointing a pack to an older built version.")
    p_rollback.add_argument("pack_id")
    p_rollback.add_argument("--to-version", type=int, required=True)
    p_rollback.add_argument("--catalog", default=None)
    p_rollback.add_argument("--catalog-dir", default=None)
    p_rollback.add_argument("--releases-root", default=None)
    p_rollback.add_argument("--catalog-revision", type=int, default=None)
    p_rollback.set_defaults(func=cmd_rollback)

    return parser


def main(argv=None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except SchemaDependencyMissing as e:
        print("MISSING DEPENDENCY: " + str(e), file=sys.stderr)
        return 2
    except (CliCommandError, FileNotFoundError, KeyError, PackBuildError, ValueError) as e:
        print("ERROR: " + str(e), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
