"""FLEURS adapter (Google FLEURS via the Hugging Face datasets-server API).

Pinned release: ``google/fleurs`` main revision, per-language configs such as
``sn_zw`` (Shona), ``luo_ke`` (Dholuo), ``sw_ke`` (Swahili). FLEURS is CC BY
4.0 sentence-read speech, not a beginner course; this adapter proves
discovery (config/split/item-count) and attempts bounded row-level metadata
import through the public datasets-server API, which serves JSON metadata
without requiring a bulk parquet/audio archive download.

Real-source note: the datasets-server ``/rows`` endpoint has been observed to
fail (HTTP 500) specifically for FLEURS's audio-bearing configs at the time
this adapter was written, while ``/splits`` and ``/info`` succeed. This
adapter reports that failure honestly as a blocked row-level import rather
than parsing the underlying multi-hundred-megabyte parquet files (which
would exceed the bounded smoke-import archive budget) or fabricating rows.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Optional

from .. import net
from ..limits import DEFAULT_SMOKE_ITEM_LIMIT
from ..reports import ImportReport, RowIssue
from ..idempotency import fingerprint_inputs
from .base import DiscoveryResult, SourceAdapter

API_BASE = "https://datasets-server.huggingface.co"
DATASET = "google/fleurs"

# Confirmed by the plan's research pass against the official FLEURS card.
LANGUAGE_TO_CONFIG = {
    "sn": "sn_zw",   # Shona (Zimbabwe)
    "luo": "luo_ke",  # Dholuo (Kenya)
    "sw": "sw_ke",   # Swahili (Kenya)
}


class FleursAdapter(SourceAdapter):
    source_id = "fleurs"
    adapter_version = "0.1.0"
    release_id = "google/fleurs@main"

    def __init__(self, http_get=net.get):
        self._get = http_get

    def discover(self, language: str = "sn", split: str = "test", **kwargs: Any) -> DiscoveryResult:
        config = LANGUAGE_TO_CONFIG.get(language)
        if config is None:
            return DiscoveryResult(
                source_id=self.source_id,
                release_id=self.release_id,
                available=False,
                languages=list(LANGUAGE_TO_CONFIG.keys()),
                item_count=None,
                detail=f"Language '{language}' is not among the confirmed FLEURS configs.",
                blocked_reason="unsupported_language",
            )
        try:
            splits_resp = self._get(
                f"{API_BASE}/splits?dataset={DATASET}&config={config}", max_bytes=2_000_000
            )
            splits = splits_resp.json().get("splits", [])
            split_names = {s["split"] for s in splits}
            if split not in split_names:
                return DiscoveryResult(
                    source_id=self.source_id, release_id=self.release_id, available=False,
                    languages=list(LANGUAGE_TO_CONFIG.keys()), item_count=None,
                    detail=f"Split '{split}' not present for config {config}: {sorted(split_names)}",
                    blocked_reason="split_unavailable",
                )
            info_resp = self._get(
                f"{API_BASE}/info?dataset={DATASET}&config={config}", max_bytes=2_000_000
            )
            info = info_resp.json()
            split_info = info.get("dataset_info", {}).get("splits", {}).get(split, {})
            item_count = split_info.get("num_examples")
            return DiscoveryResult(
                source_id=self.source_id,
                release_id=self.release_id,
                available=True,
                languages=list(LANGUAGE_TO_CONFIG.keys()),
                item_count=item_count,
                detail=f"config={config} split={split} num_examples={item_count}",
            )
        except net.AuthorizationRequired as e:
            return DiscoveryResult(
                source_id=self.source_id, release_id=self.release_id, available=False,
                languages=list(LANGUAGE_TO_CONFIG.keys()), item_count=None,
                detail=str(e), blocked_reason="authorization_required",
            )
        except net.NetworkUnavailable as e:
            return DiscoveryResult(
                source_id=self.source_id, release_id=self.release_id, available=False,
                languages=list(LANGUAGE_TO_CONFIG.keys()), item_count=None,
                detail=str(e), blocked_reason="network_unavailable",
            )

    def import_candidates(
        self, language: str = "sn", limit: int = DEFAULT_SMOKE_ITEM_LIMIT,
        split: str = "test", **kwargs: Any,
    ) -> ImportReport:
        config = LANGUAGE_TO_CONFIG.get(language)
        params = {"language": language, "split": split, "limit": limit}
        fp = fingerprint_inputs(self.source_id, self.release_id, params)
        report = ImportReport(
            adapter="fleurs", adapter_version=self.adapter_version,
            source_id=self.source_id, release_id=self.release_id,
            parameters=params, input_fingerprint=fp,
        )
        if config is None:
            report.blocked_reason = "unsupported_language"
            return report

        length = min(limit, 100)
        url = f"{API_BASE}/rows?dataset={DATASET}&config={config}&split={split}&offset=0&length={length}"
        try:
            resp = self._get(url, max_bytes=5_000_000)
        except net.AuthorizationRequired as e:
            report.blocked_reason = f"authorization_required: {e}"
            return report
        except net.NetworkUnavailable as e:
            report.blocked_reason = f"network_unavailable: {e}"
            return report

        try:
            payload = resp.json()
        except Exception as e:
            report.blocked_reason = f"invalid_response: {e}"
            return report

        if "error" in payload:
            # The datasets-server API returns a JSON {"error": "..."} body
            # with a non-200-looking success status for some audio configs;
            # treat this as an honest blocked row-level import, matching the
            # observed HTTP 500 behavior for FLEURS's audio-bearing configs.
            report.blocked_reason = f"rows_endpoint_error: {payload['error']}"
            return report

        rows = payload.get("rows", [])
        if not rows:
            report.blocked_reason = "no_rows_returned"
            return report

        seen_ids = set()
        for entry in rows:
            row = entry.get("row", {})
            row_idx = entry.get("row_idx")
            item_id = str(row.get("id", row_idx))
            ref = f"row:{item_id}"
            transcription = row.get("transcription")
            raw_transcription = row.get("raw_transcription")
            lang_id = row.get("lang_id")
            path = row.get("path")
            num_samples = row.get("num_samples")
            if not transcription or not raw_transcription:
                report.rejected.append(RowIssue(row_ref=ref, reason="missing_transcription"))
                continue
            if item_id in seen_ids:
                report.rejected.append(RowIssue(row_ref=ref, reason="duplicate_item_id"))
                continue
            seen_ids.add(item_id)
            report.accepted.append({
                "sourceId": self.source_id,
                "releaseId": self.release_id,
                "itemId": item_id,
                "config": config,
                "split": split,
                "targetText": raw_transcription,
                "normalizedTranscription": transcription,
                "langId": lang_id,
                "audioPathHint": path,
                "numSamples": num_samples,
            })
        return report
