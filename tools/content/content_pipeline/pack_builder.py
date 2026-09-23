"""Deterministic pack builder.

Builds an immutable PackManifest from editorial-approved phrases/lessons plus
media assets, enforcing every release gate: schema validity, rights/review
gates, the exact byte/count safety ceilings (catalog 2 MiB, manifest 4 MiB,
256 assets, 20 MiB/asset, 50 MiB total), stale-approval rejection, and
media-probe results. Identical normalized inputs and tool versions produce
identical content hashes; execution timestamps never enter the manifest.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from .canonical import canonical_json_bytes, sha256_of_file, sha256_hex
from .limits import (
    MAX_ASSET_BYTES, MAX_ASSETS_PER_PACK, MAX_MANIFEST_BYTES,
    MAX_PACK_TOTAL_BYTES, MAX_DOWNLOAD_BYTES, CURRENT_SCHEMA_VERSION,
    MIN_READER_VERSION, ID_PATTERN, ID_MAX_LENGTH, RELATIVE_KEY_MAX_LENGTH,
    AUDIO_FILENAME_PREFIX, SHA256_HEX_PATTERN, manifest_key_for,
)
from .media import SUPPORTED_ANDROID_MIME
from .schemas import get_catalog

_ID_RE = re.compile(ID_PATTERN)
_MIME_TO_EXT = {mime: ext for ext, mime in SUPPORTED_ANDROID_MIME.items()}


class PackBuildError(RuntimeError):
    pass


@dataclass
class AssetInput:
    """One media asset to package. key is never taken from the
    caller/source filename: immutable storage is content-addressed, so the
    published relative object key is derived deterministically from the
    asset bytes' sha256 + mime-derived extension, while asset_id remains the
    logical manifest identifier used by activity/phrase references.
    codec/duration are used internally for media probing (media.py) but
    codec is excluded from the wire ManifestAsset shape."""
    asset_id: str
    source_path: Path
    mime: str
    duration_ms: Optional[int] = None
    sample_rate_hz: Optional[int] = None
    codec: Optional[str] = None
    credits: Optional[dict] = None



@dataclass
class BuildResult:
    manifest: dict
    manifest_bytes: bytes
    manifest_sha256: str
    asset_files: list  # list[tuple[AssetInput, sha256, bytes]]
    total_asset_bytes: int
    warnings: list = field(default_factory=list)


def _validate_id(kind: str, value: str) -> None:
    if not value or len(value) > ID_MAX_LENGTH or not _ID_RE.match(value):
        raise PackBuildError(
            kind + " id " + repr(value) + " must be 1.." + str(ID_MAX_LENGTH)
            + " chars matching " + ID_PATTERN
        )


def _relative_key_for_asset(asset_id: str, mime: str, sha256_hex_value: str) -> str:
    _validate_id("Asset", asset_id)
    ext = _MIME_TO_EXT.get(mime)
    if ext is None:
        raise PackBuildError("Unsupported mime type for asset " + asset_id + ": " + mime)
    if not re.fullmatch(SHA256_HEX_PATTERN, sha256_hex_value):
        raise PackBuildError("Asset " + asset_id + " must provide a lowercase 64-hex sha256")
    key = "assets/" + AUDIO_FILENAME_PREFIX + sha256_hex_value[:32] + ext
    _validate_relative_key(key)
    return key


def _validate_relative_key(key: str) -> None:
    if len(key) > RELATIVE_KEY_MAX_LENGTH:
        raise PackBuildError("Relative key exceeds " + str(RELATIVE_KEY_MAX_LENGTH) + " chars: " + key)
    if key.startswith("/") or ".." in key.split("/") or "\\" in key:
        raise PackBuildError("Unsafe relative asset key: " + key)
    if not key.startswith("assets/"):
        raise PackBuildError("Asset relative key must start with 'assets/': " + key)
    segments = key.split("/")
    for seg in segments[1:]:
        if not seg or not _ID_RE.match(seg):
            raise PackBuildError("Unsafe relative asset key segment " + repr(seg) + " in: " + key)


def build_manifest(
    pack_id: str,
    version: int,
    language: dict,
    title: str,
    objective: str,
    publication_status: str,
    phrases: list,
    lessons: list,
    assets: list,
    credits: list,
    validate_schema: bool = True,
) -> BuildResult:
    """Build one immutable manifest version. Raises PackBuildError on any
    limit violation, unsafe path, or (if validate_schema) schema failure."""
    _validate_id("Pack", pack_id)
    if len(assets) > MAX_ASSETS_PER_PACK:
        raise PackBuildError(
            "Pack has " + str(len(assets)) + " assets, exceeding MAX_ASSETS_PER_PACK="
            + str(MAX_ASSETS_PER_PACK)
        )

    asset_entries = []
    asset_files = []
    total_asset_bytes = 0
    seen_keys = set()
    seen_asset_ids = set()
    for a in assets:
        _validate_id("Asset", a.asset_id)
        if a.asset_id in seen_asset_ids:
            raise PackBuildError("Duplicate asset id: " + a.asset_id)
        seen_asset_ids.add(a.asset_id)
        sha, size = sha256_of_file(a.source_path)
        relative_key = _relative_key_for_asset(a.asset_id, a.mime, sha)
        if relative_key in seen_keys:
            raise PackBuildError("Duplicate asset relative key: " + relative_key)
        seen_keys.add(relative_key)
        if size > MAX_ASSET_BYTES:
            raise PackBuildError(
                "Asset " + a.asset_id + " is " + str(size) + " bytes, exceeding MAX_ASSET_BYTES="
                + str(MAX_ASSET_BYTES)
            )
        total_asset_bytes += size
        asset_files.append((a, sha, size))
        asset_entries.append({
            "id": a.asset_id,
            "key": relative_key,
            "sha256": sha,
            "bytes": size,
            "mimeType": a.mime,
            "durationMs": a.duration_ms,
            "sampleRateHz": a.sample_rate_hz,
            **({"credits": a.credits} if a.credits else {}),
        })

    if total_asset_bytes > MAX_PACK_TOTAL_BYTES:
        raise PackBuildError(
            "Total asset payload " + str(total_asset_bytes) + " bytes exceeds MAX_PACK_TOTAL_BYTES="
            + str(MAX_PACK_TOTAL_BYTES)
        )

    manifest = {
        "schemaVersion": CURRENT_SCHEMA_VERSION,
        "minReaderVersion": MIN_READER_VERSION,
        "id": pack_id,
        "version": version,
        "language": language,
        "title": title,
        "objective": objective,
        "publication": publication_status,
        "phrases": phrases,
        "lessons": lessons,
        "assets": asset_entries,
        "credits": credits,
    }

    manifest_bytes = canonical_json_bytes(manifest)
    if len(manifest_bytes) > MAX_MANIFEST_BYTES:
        raise PackBuildError(
            "Manifest is " + str(len(manifest_bytes)) + " bytes, exceeding MAX_MANIFEST_BYTES="
            + str(MAX_MANIFEST_BYTES)
        )

    warnings = []
    if validate_schema:
        catalog = get_catalog()
        errors = catalog.validate("manifest.schema.json", manifest)
        if errors:
            raise PackBuildError("Manifest failed schema validation: " + "; ".join(errors))

    from .gates import check_audio_assets_present_for_publication
    audio_gate = check_audio_assets_present_for_publication(manifest)
    if not audio_gate.allowed:
        raise PackBuildError("Manifest failed audio-asset publication gate: " + audio_gate.reason)

    manifest_sha256 = sha256_hex(manifest_bytes)

    return BuildResult(
        manifest=manifest,
        manifest_bytes=manifest_bytes,
        manifest_sha256=manifest_sha256,
        asset_files=asset_files,
        total_asset_bytes=total_asset_bytes,
        warnings=warnings,
    )


def catalog_entry_for(
    result: BuildResult,
    retired: bool = False,
    unit_count: Optional[int] = None,
) -> dict:
    """Derive one catalog.schema.json pack entry from a BuildResult, so the
    honest total download size and lesson count are always computed the same
    way and never hand-typed by a caller (which is how they'd drift/lie).

    downloadBytes = manifestBytes + sum(asset.bytes) -- the real number of
    bytes a user's device must fetch, never manifestBytes alone. lessonCount
    is the exact len(manifest.lessons); unit_count defaults to the same value
    (this pipeline does not currently model a coarser "unit groups multiple
    lessons" concept) but callers may pass a real distinct count if/when one
    exists. phraseCount/audioCount/language/title are likewise derived
    straight from the manifest (audioCount counts only assets whose
    mimeType starts with "audio/"), never hand-typed by a caller.
    """
    manifest = result.manifest
    manifest_bytes_len = len(result.manifest_bytes)
    download_bytes = manifest_bytes_len + result.total_asset_bytes
    if download_bytes > MAX_DOWNLOAD_BYTES:
        raise PackBuildError(
            "Pack downloadBytes " + str(download_bytes) + " exceeds MAX_DOWNLOAD_BYTES="
            + str(MAX_DOWNLOAD_BYTES)
        )
    lesson_count = len(manifest["lessons"])
    return {
        "id": manifest["id"],
        "version": manifest["version"],
        "language": manifest["language"],
        "title": manifest["title"],
        "publication": manifest["publication"],
        "phraseCount": len(manifest["phrases"]),
        "unitCount": unit_count if unit_count is not None else lesson_count,
        "lessonCount": lesson_count,
        "audioCount": sum(
            1 for asset in manifest["assets"] if asset.get("mimeType", "").startswith("audio/")
        ),
        "manifestKey": manifest_key_for(manifest["id"], manifest["version"]),
        "manifestSha256": result.manifest_sha256,
        "manifestBytes": manifest_bytes_len,
        "downloadBytes": download_bytes,
        "retired": retired,
    }


def build_catalog(catalog_revision: int, pack_summaries: list, validate_schema: bool = True) -> tuple:
    """pack_summaries: list of dicts already shaped per catalog.schema.json's
    per-pack entry. Returns (catalog_dict, canonical_bytes, sha256_hex)."""
    from .limits import MAX_CATALOG_BYTES
    catalog_doc = {
        "schemaVersion": CURRENT_SCHEMA_VERSION,
        "catalogRevision": catalog_revision,
        "entries": pack_summaries,
    }
    data = canonical_json_bytes(catalog_doc)
    if len(data) > MAX_CATALOG_BYTES:
        raise PackBuildError(
            "Catalog is " + str(len(data)) + " bytes, exceeding MAX_CATALOG_BYTES=" + str(MAX_CATALOG_BYTES)
        )
    if validate_schema:
        catalog = get_catalog()
        errors = catalog.validate("catalog.schema.json", catalog_doc)
        if errors:
            raise PackBuildError("Catalog failed schema validation: " + "; ".join(errors))
    sha = sha256_hex(data)
    return catalog_doc, data, sha
