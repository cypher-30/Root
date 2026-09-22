"""Quarantine store for candidates that need editorial attention before they
can be accepted or rejected outright (unclear text/audio alignment,
ambiguous rights, possible duplicates, disputed variants).

Quarantine entries are content-addressed by a stable key derived from
(source_id, release_id, item_id) so repeated imports do not create duplicate
quarantine rows and a later resolution can be looked up deterministically.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any, Optional

from .canonical import canonical_json_bytes, sha256_hex


@dataclass
class QuarantineEntry:
    key: str
    source_id: str
    release_id: str
    item_id: str
    reason: str
    detail: Optional[str]
    payload: dict[str, Any]
    resolved: bool = False
    resolution: Optional[str] = None


def quarantine_key(source_id: str, release_id: str, item_id: str) -> str:
    return sha256_hex(f"{source_id}|{release_id}|{item_id}".encode("utf-8"))[:16]


class QuarantineStore:
    """A JSON-file-backed store, one file per source, under a quarantine
    directory (typically tools/content/build/quarantine or a directory the
    caller supplies -- never inside content/fixtures)."""

    def __init__(self, path: Path):
        self.path = path
        self._entries: dict[str, QuarantineEntry] = {}
        if path.exists():
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            for raw in data.get("entries", []):
                entry = QuarantineEntry(**raw)
                self._entries[entry.key] = entry

    def add(self, entry: QuarantineEntry) -> None:
        self._entries[entry.key] = entry

    def get(self, key: str) -> Optional[QuarantineEntry]:
        return self._entries.get(key)

    def resolve(self, key: str, resolution: str) -> None:
        entry = self._entries.get(key)
        if entry is None:
            raise KeyError(f"No quarantine entry for key {key}")
        entry.resolved = True
        entry.resolution = resolution

    def unresolved(self) -> list[QuarantineEntry]:
        return [e for e in self._entries.values() if not e.resolved]

    def all(self) -> list[QuarantineEntry]:
        return list(self._entries.values())

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        data = {"entries": [asdict(e) for e in self._entries.values()]}
        with open(self.path, "wb") as f:
            f.write(canonical_json_bytes(data))
