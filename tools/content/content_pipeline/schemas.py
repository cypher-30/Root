"""JSON Schema loading and validation for content/schemas/*.json.

All schemas live under content/schemas relative to the repository root and
reference each other by filename (e.g. ``{"$ref": "phrase.schema.json"}``).
This module wires a local resolver so validation works fully offline with no
network fetch of $id URLs.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

try:
    import jsonschema
    from jsonschema import Draft202012Validator
    from referencing import Registry, Resource
    _HAVE_JSONSCHEMA = True
except Exception:  # pragma: no cover - exercised only when dependency missing
    _HAVE_JSONSCHEMA = False


class SchemaDependencyMissing(RuntimeError):
    """Raised instead of silently skipping validation when jsonschema/referencing
    are not installed. Callers must report this as a blocked/missing-dependency
    condition, never treat it as an implicit pass."""


def repo_root() -> Path:
    # tools/content/content_pipeline/schemas.py -> repo root is 3 parents up.
    return Path(__file__).resolve().parents[3]


def schemas_dir() -> Path:
    return repo_root() / "content" / "schemas"


def _load_schema_file(name: str) -> dict:
    path = schemas_dir() / name
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


_SCHEMA_FILENAMES = [
    "language-reference.schema.json",
    "provenance.schema.json",
    "credits.schema.json",
    "choice-task.schema.json",
    "ordered-token-task.schema.json",
    "evaluable-task.schema.json",
    "asset.schema.json",
    "phrase.schema.json",
    "activity-step.schema.json",
    "lesson.schema.json",
    "manifest.schema.json",
    "catalog.schema.json",
    "source-registry.schema.json",
]


class SchemaCatalog:
    """Loads all local schemas once and builds validators with a resolver
    that serves $ref by plain filename, matching how the schemas reference
    each other (no network access required to validate)."""

    def __init__(self) -> None:
        if not _HAVE_JSONSCHEMA:
            raise SchemaDependencyMissing(
                "jsonschema/referencing packages are not installed; install "
                "tools/content dev dependencies (pip install -e .[dev]) to "
                "run schema validation. This is a missing-dependency report, "
                "not an implicit validation pass."
            )
        self._raw: dict[str, dict] = {name: _load_schema_file(name) for name in _SCHEMA_FILENAMES}
        resources = [
            (name, Resource.from_contents(schema))
            for name, schema in self._raw.items()
        ]
        # Also register each schema under its declared $id, and under the
        # bare filename, so "phrase.schema.json"-style relative $ref values
        # resolve regardless of base URI handling.
        entries = []
        for name, resource in resources:
            entries.append((name, resource))
            schema_id = self._raw[name].get("$id")
            if schema_id:
                entries.append((schema_id, resource))
        self._registry = Registry().with_resources(entries)
        self._validators = {
            name: Draft202012Validator(schema, registry=self._registry)
            for name, schema in self._raw.items()
        }

    def validator_for(self, filename: str) -> "Draft202012Validator":
        return self._validators[filename]

    def validate(self, filename: str, instance: Any) -> list[str]:
        """Return a list of human-readable error strings (empty if valid)."""
        validator = self.validator_for(filename)
        errors = sorted(validator.iter_errors(instance), key=lambda e: list(e.path))
        return [f"{'/'.join(str(p) for p in e.path) or '<root>'}: {e.message}" for e in errors]


_catalog_singleton: SchemaCatalog | None = None


def get_catalog() -> SchemaCatalog:
    global _catalog_singleton
    if _catalog_singleton is None:
        _catalog_singleton = SchemaCatalog()
    return _catalog_singleton
