"""Import report model.

Every adapter import run produces one ImportReport: accepted/rejected/
quarantined counts, actionable row/file-level errors, the pinned source
version, and reproducibility inputs (adapter name/version, parameters, and an
input fingerprint). Raw acquisition and candidate import never imply
editorial approval; nothing here marks content as reviewed or published.
"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any, Optional

from .canonical import canonical_json_bytes


@dataclass
class RowIssue:
    row_ref: str
    reason: str
    detail: Optional[str] = None


@dataclass
class ImportReport:
    adapter: str
    adapter_version: str
    source_id: str
    release_id: str
    parameters: dict[str, Any]
    input_fingerprint: str
    generated_at: float = field(default_factory=time.time)
    accepted: list[dict] = field(default_factory=list)
    rejected: list[RowIssue] = field(default_factory=list)
    quarantined: list[RowIssue] = field(default_factory=list)
    blocked_reason: Optional[str] = None
    """Set when the entire run could not proceed (auth required, source
    unavailable, unsupported language, archive budget exceeded). Distinct
    from per-row rejects; a blocked run still reports honestly, never
    fabricated rows."""

    @property
    def accepted_count(self) -> int:
        return len(self.accepted)

    @property
    def rejected_count(self) -> int:
        return len(self.rejected)

    @property
    def quarantined_count(self) -> int:
        return len(self.quarantined)

    def summary(self) -> dict:
        return {
            "adapter": self.adapter,
            "adapterVersion": self.adapter_version,
            "sourceId": self.source_id,
            "releaseId": self.release_id,
            "parameters": self.parameters,
            "inputFingerprint": self.input_fingerprint,
            "generatedAt": self.generated_at,
            "acceptedCount": self.accepted_count,
            "rejectedCount": self.rejected_count,
            "quarantinedCount": self.quarantined_count,
            "blockedReason": self.blocked_reason,
        }

    def to_dict(self) -> dict:
        d = self.summary()
        d["accepted"] = self.accepted
        d["rejected"] = [asdict(r) for r in self.rejected]
        d["quarantined"] = [asdict(r) for r in self.quarantined]
        return d

    def write(self, out_dir: Path) -> Path:
        """Write a deterministic-shape (but timestamped) JSON report file
        under out_dir; returns the written path. The timestamp lives in the
        report only, never inside content hashed by the pack builder."""
        out_dir.mkdir(parents=True, exist_ok=True)
        name = f"{self.source_id}-{self.release_id.replace('/', '_').replace(':', '_')}-report.json"
        path = out_dir / name
        with open(path, "wb") as f:
            f.write(canonical_json_bytes(self.to_dict()))
        return path

    def is_ok_to_proceed(self) -> bool:
        return self.blocked_reason is None
