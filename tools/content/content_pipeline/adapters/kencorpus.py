"""KenCorpus adapter (Harvard Dataverse, doi:10.7910/DVN/6N5V1K).

Pinned release: Dataverse dataset persistent ID above, latest version 9.1,
CC BY 4.0 (declared at dataset level; see content/sources/registry.json).
The dataset-level "language" field currently reads Swahili despite the
broader Dholuo/Swahili/Luhya description in the dataset abstract, so this
adapter classifies language from *file-level* metadata (the Dataverse
``directoryLabel`` and filename suffix), never the single dataset-level
field, matching the plan's explicit guidance.

KenCorpus is two loosely related corpora (collected text, and separately
recorded spontaneous speech) rather than a parallel sentence+audio corpus.
There is no supplied alignment id linking a specific text file to a specific
audio file. This adapter therefore only pairs a text/audio candidate when
both share the same leading numeric file id *and* the same language tag, and
even then marks the pair "unverified_alignment" and adds it to quarantine;
standalone text or standalone audio candidates are accepted directly (as
text-only or audio-only material), never presented as a verified match.
"""
from __future__ import annotations

import re
from typing import Any, Optional

from .. import net
from ..limits import DEFAULT_SMOKE_ITEM_LIMIT
from ..reports import ImportReport, RowIssue
from ..idempotency import fingerprint_inputs
from ..quarantine import QuarantineEntry, quarantine_key
from .base import DiscoveryResult, SourceAdapter

API_BASE = "https://dataverse.harvard.edu/api"
PERSISTENT_ID = "doi:10.7910/DVN/6N5V1K"

# Keyword -> our canonical language code. Checked against directoryLabel and
# filename, in this order, first match wins. This is deliberately explicit
# rather than inferring from the single dataset-level language field.
LANGUAGE_KEYWORDS: list[tuple[str, str]] = [
    ("dholuo", "luo"),
    ("_dho", "luo"),
    ("swahili", "sw"),
    ("_swa", "sw"),
    ("lhy_logooli", "luhya-logooli"),
    ("lhy_lumarachi", "luhya-lumarachi"),
    ("lhy_bukusu", "luhya-lubukusu"),
    ("lhy_lubukusu", "luhya-lubukusu"),
    ("lubukusu", "luhya-lubukusu"),
    ("logooli", "luhya-logooli"),
    ("lumarachi", "luhya-lumarachi"),
]

AUDIO_EXTENSIONS = {".m4a", ".aac", ".wav", ".mp3"}
TEXT_EXTENSIONS = {".txt"}

_NUMERIC_PREFIX = re.compile(r"^(\d+)_")


def classify_language(directory_label: Optional[str], filename: str) -> Optional[str]:
    haystack = f"{(directory_label or '').lower()} {filename.lower()}"
    for keyword, lang in LANGUAGE_KEYWORDS:
        if keyword in haystack:
            return lang
    return None


def classify_kind(filename: str, content_type: Optional[str]) -> Optional[str]:
    lower = filename.lower()
    for ext in AUDIO_EXTENSIONS:
        if lower.endswith(ext):
            return "audio"
    for ext in TEXT_EXTENSIONS:
        if lower.endswith(ext):
            return "text"
    if content_type and content_type.startswith("audio/"):
        return "audio"
    if content_type == "text/plain":
        return "text"
    return None


def numeric_prefix(filename: str) -> Optional[str]:
    m = _NUMERIC_PREFIX.match(filename)
    return m.group(1) if m else None


class KenCorpusAdapter(SourceAdapter):
    source_id = "kencorpus"
    adapter_version = "0.1.0"
    release_id = "doi:10.7910/DVN/6N5V1K@v9.1"

    def __init__(self, http_get=net.get):
        self._get = http_get

    def discover(self, **kwargs: Any) -> DiscoveryResult:
        url = f"{API_BASE}/datasets/:persistentId/?persistentId={PERSISTENT_ID}"
        try:
            resp = self._get(url, max_bytes=5_000_000, timeout=45.0)
            data = resp.json()
        except net.AuthorizationRequired as e:
            return DiscoveryResult(
                source_id=self.source_id, release_id=self.release_id, available=False,
                languages=[], item_count=None, detail=str(e), blocked_reason="authorization_required",
            )
        except net.NetworkUnavailable as e:
            return DiscoveryResult(
                source_id=self.source_id, release_id=self.release_id, available=False,
                languages=[], item_count=None, detail=str(e), blocked_reason="network_unavailable",
            )
        latest = data.get("data", {}).get("latestVersion", {})
        version_str = f"{latest.get('versionNumber')}.{latest.get('versionMinorNumber')}"
        license_name = latest.get("license", {}).get("name")
        return DiscoveryResult(
            source_id=self.source_id,
            release_id=self.release_id,
            available=True,
            languages=["luo", "sw", "luhya-logooli", "luhya-lumarachi", "luhya-lubukusu"],
            item_count=None,
            detail=f"latest_version={version_str} license={license_name}",
        )

    def _list_files_page(self, offset: int, limit: int) -> list[dict]:
        url = (
            f"{API_BASE}/datasets/:persistentId/versions/:latest/files"
            f"?persistentId={PERSISTENT_ID}&limit={limit}&offset={offset}"
        )
        resp = self._get(url, max_bytes=5_000_000)
        return resp.json().get("data", [])

    def import_candidates(
        self, language: str = "luo", limit: int = DEFAULT_SMOKE_ITEM_LIMIT,
        max_pages_scanned: int = 20, page_size: int = 100, **kwargs: Any,
    ) -> ImportReport:
        params = {"language": language, "limit": limit}
        fp = fingerprint_inputs(self.source_id, self.release_id, params)
        report = ImportReport(
            adapter="kencorpus", adapter_version=self.adapter_version,
            source_id=self.source_id, release_id=self.release_id,
            parameters=params, input_fingerprint=fp,
        )

        matched: list[dict] = []
        try:
            for page in range(max_pages_scanned):
                entries = self._list_files_page(offset=page * page_size, limit=page_size)
                if not entries:
                    break
                for entry in entries:
                    data_file = entry.get("dataFile", {})
                    filename = data_file.get("filename", "")
                    directory_label = entry.get("directoryLabel")
                    lang = classify_language(directory_label, filename)
                    if lang != language:
                        continue
                    kind = classify_kind(filename, data_file.get("contentType"))
                    if kind is None:
                        report.rejected.append(
                            RowIssue(row_ref=f"file:{data_file.get('id')}", reason="unclassified_content_kind")
                        )
                        continue
                    matched.append({
                        "fileId": data_file.get("id"),
                        "filename": filename,
                        "directoryLabel": directory_label,
                        "kind": kind,
                        "language": lang,
                        "bytes": data_file.get("filesize"),
                        "md5": data_file.get("md5"),
                        "restricted": entry.get("restricted", False),
                    })
                    if len(matched) >= limit:
                        break
                if len(matched) >= limit:
                    break
        except net.AuthorizationRequired as e:
            report.blocked_reason = f"authorization_required: {e}"
            return report
        except net.NetworkUnavailable as e:
            report.blocked_reason = f"network_unavailable: {e}"
            return report

        if not matched:
            report.blocked_reason = f"no_files_matched_language:{language}"
            return report

        by_prefix: dict[str, dict[str, dict]] = {}
        for cand in matched:
            prefix = numeric_prefix(cand["filename"])
            if prefix is None:
                continue
            by_prefix.setdefault(prefix, {})[cand["kind"]] = cand

        emitted_ids = set()
        quarantined_prefixes = set()
        for cand in matched:
            if cand.get("restricted"):
                report.rejected.append(
                    RowIssue(row_ref=f"file:{cand['fileId']}", reason="restricted_file")
                )
                continue
            item_id = str(cand["fileId"])
            if item_id in emitted_ids:
                continue
            emitted_ids.add(item_id)
            prefix = numeric_prefix(cand["filename"])
            pair = by_prefix.get(prefix, {}) if prefix else {}
            has_pair = "text" in pair and "audio" in pair and pair["text"]["fileId"] != pair["audio"]["fileId"]
            row = {
                "sourceId": self.source_id,
                "releaseId": self.release_id,
                "itemId": item_id,
                "filename": cand["filename"],
                "directoryLabel": cand["directoryLabel"],
                "kind": cand["kind"],
                "language": cand["language"],
                "bytes": cand["bytes"],
                "md5": cand["md5"],
                "alignmentStatus": "unverified_pair" if has_pair else "standalone",
            }
            report.accepted.append(row)
            if has_pair and prefix not in quarantined_prefixes:
                quarantined_prefixes.add(prefix)
                qkey = quarantine_key(self.source_id, self.release_id, f"pair:{prefix}:{cand['language']}")
                report.quarantined.append(
                    RowIssue(
                        row_ref=f"pair:{prefix}",
                        reason="unverified_text_audio_alignment",
                        detail=(
                            f"text file {pair['text']['fileId']} and audio file "
                            f"{pair['audio']['fileId']} share numeric prefix {prefix} and language "
                            f"{cand['language']} but KenCorpus supplies no alignment id; do not treat "
                            "as a verified matching pair without manual review."
                        ),
                    )
                )
        return report
