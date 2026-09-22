"""Idempotency support for re-imports and pack builds.

Re-import is idempotent: running the same adapter against the same pinned
source input twice produces the same accepted-candidate set and does not
duplicate quarantine/report artifacts. This module computes stable
fingerprints and provides a small ledger to detect "already applied" runs.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional

from .canonical import canonical_json_bytes, sha256_hex


def fingerprint_inputs(*parts: Any) -> str:
    """Compute a stable fingerprint over arbitrary JSON-serializable inputs
    (adapter name/version, parameters, pinned release id, and either raw
    input bytes' hash or a listing of source file hashes). Order-sensitive:
    callers should pass parts in a documented, stable order."""
    return sha256_hex(canonical_json_bytes(list(parts)))


@dataclass
class LedgerEntry:
    fingerprint: str
    accepted_count: int
    rejected_count: int
    quarantined_count: int
    report_path: str


class IdempotencyLedger:
    """A tiny JSON-backed ledger mapping input fingerprint -> prior run
    outcome, so a caller can detect and short-circuit an identical re-run
    rather than silently redoing (and potentially double-reporting) work."""

    def __init__(self, path: Path):
        self.path = path
        self._entries: dict[str, LedgerEntry] = {}
        if path.exists():
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            for fp, raw in data.get("entries", {}).items():
                self._entries[fp] = LedgerEntry(**raw)

    def lookup(self, fingerprint: str) -> Optional[LedgerEntry]:
        return self._entries.get(fingerprint)

    def record(self, entry: LedgerEntry) -> None:
        self._entries[entry.fingerprint] = entry

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "entries": {
                fp: {
                    "fingerprint": e.fingerprint,
                    "accepted_count": e.accepted_count,
                    "rejected_count": e.rejected_count,
                    "quarantined_count": e.quarantined_count,
                    "report_path": e.report_path,
                }
                for fp, e in self._entries.items()
            }
        }
        with open(self.path, "wb") as f:
            f.write(canonical_json_bytes(data))
