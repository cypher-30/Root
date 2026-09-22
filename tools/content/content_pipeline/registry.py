"""Source component rights/review registry.

Wraps content/sources/registry.json: the single machine-readable place that
records, per (sourceId, releaseId, componentKind), the license, required
credit, rights status, and review status. Adapters and the pack builder both
consult this registry; neither invents rights/review decisions inline.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from .schemas import repo_root, get_catalog, SchemaDependencyMissing

REGISTRY_PATH = repo_root() / "content" / "sources" / "registry.json"

RIGHTS_STATUSES = ("cleared", "unknown", "incompatible")
REVIEW_STATUSES = ("unreviewed", "source_checked", "native_reviewed", "stale")
COMPONENT_KINDS = ("target_text", "translation", "editorial_notes", "activity_wording", "audio")


@dataclass(frozen=True)
class ComponentRecord:
    component_kind: str
    license: str
    rights_status: str
    review_status: str
    required_credit: Optional[str] = None
    review_revision: Optional[int] = None
    notes: Optional[str] = None

    def is_publishable(self) -> bool:
        return self.rights_status == "cleared" and self.review_status == "native_reviewed"


@dataclass(frozen=True)
class SourceRelease:
    source_id: str
    release_id: str
    release_url: Optional[str]
    retrieved_at: Optional[str]
    components: tuple[ComponentRecord, ...] = field(default_factory=tuple)

    def component(self, component_kind: str) -> Optional[ComponentRecord]:
        for c in self.components:
            if c.component_kind == component_kind:
                return c
        return None


class SourceRegistry:
    """In-memory view of content/sources/registry.json, keyed by
    (source_id, release_id)."""

    def __init__(self, releases: list[SourceRelease]):
        self._releases = {(r.source_id, r.release_id): r for r in releases}

    @classmethod
    def load(cls, path: Path = REGISTRY_PATH) -> "SourceRegistry":
        if not path.exists():
            return cls([])
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        releases = []
        for entry in data.get("sources", []):
            components = tuple(
                ComponentRecord(
                    component_kind=c["componentKind"],
                    license=c["license"],
                    rights_status=c["rightsStatus"],
                    review_status=c["reviewStatus"],
                    required_credit=c.get("requiredCredit"),
                    review_revision=c.get("reviewRevision"),
                    notes=c.get("notes"),
                )
                for c in entry.get("components", [])
            )
            releases.append(
                SourceRelease(
                    source_id=entry["sourceId"],
                    release_id=entry["releaseId"],
                    release_url=entry.get("releaseUrl"),
                    retrieved_at=entry.get("retrievedAt"),
                    components=components,
                )
            )
        return cls(releases)

    def validate_against_schema(self) -> list[str]:
        """Validate the on-disk registry file against
        source-registry.schema.json. Raises SchemaDependencyMissing if the
        jsonschema/referencing packages are unavailable (never silently
        skipped)."""
        if not REGISTRY_PATH.exists():
            return []
        with open(REGISTRY_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
        catalog = get_catalog()  # raises SchemaDependencyMissing if unavailable
        return catalog.validate("source-registry.schema.json", data)

    def release(self, source_id: str, release_id: str) -> Optional[SourceRelease]:
        return self._releases.get((source_id, release_id))

    def all_releases(self) -> list[SourceRelease]:
        return list(self._releases.values())

    def component_status(
        self, source_id: str, release_id: str, component_kind: str
    ) -> ComponentRecord:
        """Return the registry's rights/review record for a component, or an
        explicit 'unknown/unreviewed' placeholder record if none is
        registered yet. Missing registration is never treated as cleared."""
        release = self.release(source_id, release_id)
        if release is None:
            return ComponentRecord(
                component_kind=component_kind,
                license="UNKNOWN",
                rights_status="unknown",
                review_status="unreviewed",
                notes="No registry entry for this source/release.",
            )
        component = release.component(component_kind)
        if component is None:
            return ComponentRecord(
                component_kind=component_kind,
                license="UNKNOWN",
                rights_status="unknown",
                review_status="unreviewed",
                notes="No registry entry for this component kind.",
            )
        return component
