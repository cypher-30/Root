"""Common Voice adapter (authorized local release only).

Downloads now go through Mozilla Data Collective and require account
acceptance; this adapter never scrapes or bypasses that. It consumes an
explicitly authorized local release directory containing ``validated.tsv``
(and, if present, ``reported.tsv``) in the standard Common Voice
scripted-speech shape: client_id, path, sentence_id, sentence,
sentence_domain, up_votes, down_votes, age, gender, accents, variant,
locale, segment.

Only validated.tsv rows are imported; reported.tsv rows are always
quarantined/excluded. Contributor demographic fields (client_id, age,
gender, accents) are deliberately omitted from produced candidates -- they
are never needed by the app and must not leak into any published artifact.
"""
from __future__ import annotations

import csv
from pathlib import Path
from typing import Any, Optional

from ..limits import DEFAULT_SMOKE_ITEM_LIMIT
from ..reports import ImportReport, RowIssue
from ..idempotency import fingerprint_inputs
from .base import DiscoveryResult, SourceAdapter

REQUIRED_COLUMNS = {
    "client_id", "path", "sentence_id", "sentence", "sentence_domain",
    "up_votes", "down_votes", "age", "gender", "accents", "variant", "locale", "segment",
}

# Fields we deliberately drop from any produced candidate/app artifact.
DEMOGRAPHIC_COLUMNS = {"client_id", "age", "gender", "accents"}


class CommonVoiceAdapter(SourceAdapter):
    source_id = "common_voice"
    adapter_version = "0.1.0"

    def __init__(self, release_dir: Optional[Path] = None, release_id: Optional[str] = None):
        self.release_dir = Path(release_dir) if release_dir else None
        self.release_id = release_id or "unset-authorized-local-release"

    def discover(self, **kwargs: Any) -> DiscoveryResult:
        if self.release_dir is None or not self.release_dir.exists():
            return DiscoveryResult(
                source_id=self.source_id, release_id=self.release_id, available=False,
                languages=[], item_count=None,
                detail=(
                    "No authorized local Common Voice release directory configured. "
                    "This adapter requires an explicitly authorized local release "
                    "(validated.tsv/+clips) supplied by the caller; it does not "
                    "download or bypass Mozilla Data Collective access controls."
                ),
                blocked_reason="authorization_required",
            )
        validated_path = self.release_dir / "validated.tsv"
        if not validated_path.exists():
            return DiscoveryResult(
                source_id=self.source_id, release_id=self.release_id, available=False,
                languages=[], item_count=None,
                detail=f"validated.tsv not found under {self.release_dir}",
                blocked_reason="release_shape_unrecognized",
            )
        with open(validated_path, "r", encoding="utf-8", newline="") as f:
            reader = csv.DictReader(f, delimiter="\t")
            missing = REQUIRED_COLUMNS - set(reader.fieldnames or [])
            if missing:
                return DiscoveryResult(
                    source_id=self.source_id, release_id=self.release_id, available=False,
                    languages=[], item_count=None,
                    detail=f"validated.tsv missing expected columns: {sorted(missing)}",
                    blocked_reason="release_shape_unrecognized",
                )
            locales = set()
            count = 0
            for row in reader:
                locales.add(row.get("locale", ""))
                count += 1
        return DiscoveryResult(
            source_id=self.source_id, release_id=self.release_id, available=True,
            languages=sorted(l for l in locales if l), item_count=count,
            detail=f"validated.tsv rows={count} locales={sorted(locales)}",
        )

    def import_candidates(
        self, language: str, limit: int = DEFAULT_SMOKE_ITEM_LIMIT, **kwargs: Any,
    ) -> ImportReport:
        params = {"language": language, "limit": limit}
        fp = fingerprint_inputs(self.source_id, self.release_id, params)
        report = ImportReport(
            adapter="common_voice", adapter_version=self.adapter_version,
            source_id=self.source_id, release_id=self.release_id,
            parameters=params, input_fingerprint=fp,
        )
        if self.release_dir is None or not self.release_dir.exists():
            report.blocked_reason = "authorization_required"
            return report
        validated_path = self.release_dir / "validated.tsv"
        if not validated_path.exists():
            report.blocked_reason = "release_shape_unrecognized"
            return report

        reported_ids: set[str] = set()
        reported_path = self.release_dir / "reported.tsv"
        if reported_path.exists():
            with open(reported_path, "r", encoding="utf-8", newline="") as f:
                for row in csv.DictReader(f, delimiter="\t"):
                    sid = row.get("sentence_id")
                    if sid:
                        reported_ids.add(sid)

        clips_dir = self.release_dir / "clips"
        seen_ids = set()
        with open(validated_path, "r", encoding="utf-8", newline="") as f:
            reader = csv.DictReader(f, delimiter="\t")
            missing = REQUIRED_COLUMNS - set(reader.fieldnames or [])
            if missing:
                report.blocked_reason = f"release_shape_unrecognized: missing {sorted(missing)}"
                return report
            for i, row in enumerate(reader):
                if len(report.accepted) >= limit:
                    break
                ref = f"row:{i}"
                locale = row.get("locale", "")
                if locale != language:
                    continue
                sentence_id = row.get("sentence_id", "")
                if not sentence_id or not row.get("sentence") or not row.get("path"):
                    report.rejected.append(RowIssue(row_ref=ref, reason="missing_required_field"))
                    continue
                if sentence_id in reported_ids:
                    report.quarantined.append(
                        RowIssue(row_ref=ref, reason="sentence_reported", detail=sentence_id)
                    )
                    continue
                if sentence_id in seen_ids:
                    report.rejected.append(RowIssue(row_ref=ref, reason="duplicate_sentence_id"))
                    continue
                clip_path = clips_dir / row["path"]
                if not clip_path.exists():
                    report.quarantined.append(
                        RowIssue(row_ref=ref, reason="clip_file_missing", detail=str(clip_path))
                    )
                    continue
                seen_ids.add(sentence_id)
                report.accepted.append({
                    "sourceId": self.source_id,
                    "releaseId": self.release_id,
                    "itemId": sentence_id,
                    "targetText": row["sentence"],
                    "sentenceDomain": row.get("sentence_domain") or None,
                    "variant": row.get("variant") or None,
                    "segment": row.get("segment") or None,
                    "locale": locale,
                    "clipRelativePath": row["path"],
                    "upVotes": int(row.get("up_votes") or 0),
                    "downVotes": int(row.get("down_votes") or 0),
                })
        if not report.accepted and not report.rejected and not report.quarantined:
            report.blocked_reason = f"no_rows_for_locale:{language}"
        return report
