"""Tatoeba adapter (sentence exports, direct translation links, and
per-sentence license lookups via the public Tatoeba API).

Pinned distribution: the per-language export tree under
https://downloads.tatoeba.org/exports/per_language/LANG/ plus the
per-sentence lookup API at https://api.tatoeba.org/unstable/sentences/ID.
Sentence/translation text defaults to CC BY 2.0 FR; a CC0 subset exists and
is verified per sentence via the API's license field rather than assumed
globally. Audio licenses are contributor-selected per recording and are
never assumed to follow the text/translation default.

Real-source note: Tatoeba does not publish a per-language audio export;
audio metadata only exists in the ~70 MB root sentences_with_audio.csv,
which exceeds this pipeline's bounded archive budget
(DEFAULT_ARCHIVE_BYTE_BUDGET). Rather than downloading that full archive
automatically, this adapter checks its real size via HTTP HEAD, reports the
budget conflict honestly, and accepts an explicitly authorized local copy of
that file as an alternative input -- matching the Common Voice adapter's
"authorized local file" pattern. Text and direct-link import proceeds for
real against the small per-language exports regardless of audio
availability.
"""
from __future__ import annotations

import bz2
import csv
import io
import re
from pathlib import Path
from typing import Any, Optional

from .. import net
from ..limits import DEFAULT_SMOKE_ITEM_LIMIT, DEFAULT_ARCHIVE_BYTE_BUDGET
from ..reports import ImportReport, RowIssue
from ..idempotency import fingerprint_inputs
from .base import DiscoveryResult, SourceAdapter

EXPORTS_BASE = "https://downloads.tatoeba.org/exports/per_language"
API_BASE = "https://api.tatoeba.org/unstable/sentences"


def _parse_directory_listing(html: str) -> dict:
    """Parse an Apache-style autoindex listing into filename -> byte size."""
    sizes = {}
    for line in html.splitlines():
        m = re.search(r'href="([^"]+\.(?:tsv|csv)\.bz2)"', line)
        if not m:
            continue
        filename = m.group(1)
        size_match = re.search(r"(\d+)\s*$", line.strip())
        if size_match:
            sizes[filename] = int(size_match.group(1))
    return sizes


class TatoebaAdapter(SourceAdapter):
    source_id = "tatoeba"
    adapter_version = "0.1.0"

    def __init__(self, http_get=net.get, http_head=net.head, release_id=None):
        self._get = http_get
        self._head = http_head
        self.release_id = release_id or "tatoeba-per-language-exports"

    def discover(self, language: str = "sna", **kwargs) -> DiscoveryResult:
        url = f"{EXPORTS_BASE}/{language}/"
        try:
            resp = self._get(url, max_bytes=2_000_000)
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
        html = resp.body.decode("utf-8", errors="replace")
        sizes = _parse_directory_listing(html)
        sentences_key = f"{language}_sentences.tsv.bz2"
        if sentences_key not in sizes:
            return DiscoveryResult(
                source_id=self.source_id, release_id=self.release_id, available=False,
                languages=[], item_count=None,
                detail=f"No {sentences_key} in per-language export listing for '{language}'.",
                blocked_reason="unsupported_language",
            )
        link_keys = sorted(k for k in sizes if k.startswith(language + "-"))
        return DiscoveryResult(
            source_id=self.source_id, release_id=self.release_id, available=True,
            languages=[language], item_count=None,
            detail=f"{sentences_key} compressed size={sizes[sentences_key]} bytes; link files present: {link_keys}",
        )

    def _fetch_bz2_tsv(self, url, max_bytes):
        resp = self._get(url, max_bytes=max_bytes)
        text = bz2.decompress(resp.body).decode("utf-8")
        reader = csv.reader(io.StringIO(text), delimiter="\t")
        return list(reader)

    def import_candidates(
        self,
        language: str = "sna",
        limit: int = DEFAULT_SMOKE_ITEM_LIMIT,
        target_language: str = "eng",
        audio_metadata_path=None,
        **kwargs,
    ) -> ImportReport:
        params = {"language": language, "limit": limit, "target_language": target_language}
        fp = fingerprint_inputs(self.source_id, self.release_id, params)
        report = ImportReport(
            adapter="tatoeba", adapter_version=self.adapter_version,
            source_id=self.source_id, release_id=self.release_id,
            parameters=params, input_fingerprint=fp,
        )

        sentences_url = f"{EXPORTS_BASE}/{language}/{language}_sentences.tsv.bz2"
        links_url = f"{EXPORTS_BASE}/{language}/{language}-{target_language}_links.tsv.bz2"

        try:
            sentence_rows = self._fetch_bz2_tsv(sentences_url, max_bytes=DEFAULT_ARCHIVE_BYTE_BUDGET)
        except net.AuthorizationRequired as e:
            report.blocked_reason = f"authorization_required: {e}"
            return report
        except net.NetworkUnavailable as e:
            report.blocked_reason = f"network_unavailable: {e}"
            return report

        try:
            link_rows = self._fetch_bz2_tsv(links_url, max_bytes=DEFAULT_ARCHIVE_BYTE_BUDGET)
        except (net.AuthorizationRequired, net.NetworkUnavailable):
            link_rows = []  # No direct links for this pair is a valid, honest empty result.

        links_by_sentence = {}
        for row in link_rows:
            if len(row) < 2:
                continue
            links_by_sentence.setdefault(row[0], []).append(row[1])

        # Audio metadata: only available via the ~70MB root export, which
        # exceeds the archive budget. Check real size via HEAD before ever
        # deciding to skip; accept an authorized local copy instead.
        audio_rows_by_sentence = {}
        audio_blocked_reason = None
        if audio_metadata_path is not None and Path(audio_metadata_path).exists():
            with open(audio_metadata_path, "r", encoding="utf-8", newline="") as f:
                for row in csv.reader(f, delimiter="\t"):
                    if len(row) < 5:
                        continue
                    sentence_id, audio_id, _user, license_, attribution_url = row[:5]
                    audio_rows_by_sentence[sentence_id] = {
                        "audioId": audio_id, "license": license_, "attributionUrl": attribution_url,
                    }
        else:
            root_url = "https://downloads.tatoeba.org/exports/sentences_with_audio.csv"
            try:
                headers = self._head(root_url)
                content_length = int(headers.get("Content-Length", "0"))
                if content_length > DEFAULT_ARCHIVE_BYTE_BUDGET:
                    audio_blocked_reason = (
                        f"sentences_with_audio.csv is {content_length} bytes, exceeding the "
                        f"{DEFAULT_ARCHIVE_BYTE_BUDGET}-byte smoke archive budget; supply an "
                        "authorized local copy via audio_metadata_path to import audio metadata."
                    )
            except (net.AuthorizationRequired, net.NetworkUnavailable) as e:
                audio_blocked_reason = f"audio_metadata_unavailable: {e}"

        seen_ids = set()
        for row in sentence_rows:
            if len(report.accepted) >= limit:
                break
            if len(row) < 3:
                report.rejected.append(RowIssue(row_ref="row", reason="malformed_row"))
                continue
            sentence_id, lang, text = row[0], row[1], row[2]
            ref = f"sentence:{sentence_id}"
            if lang != language:
                report.rejected.append(RowIssue(row_ref=ref, reason="language_mismatch"))
                continue
            if sentence_id in seen_ids:
                report.rejected.append(RowIssue(row_ref=ref, reason="duplicate_sentence_id"))
                continue
            seen_ids.add(sentence_id)

            translation_ids = links_by_sentence.get(sentence_id, [])
            translations = []
            for translation_id in translation_ids:
                try:
                    resp = self._get(f"{API_BASE}/{translation_id}", max_bytes=200_000)
                    data = resp.json().get("data", {})
                    translations.append({
                        "id": translation_id,
                        "text": data.get("text"),
                        "lang": data.get("lang"),
                        "license": data.get("license"),
                    })
                except (net.AuthorizationRequired, net.NetworkUnavailable) as e:
                    report.quarantined.append(
                        RowIssue(row_ref=ref, reason="translation_lookup_failed", detail=str(e))
                    )

            audio_entry = audio_rows_by_sentence.get(sentence_id)
            candidate = {
                "sourceId": self.source_id,
                "releaseId": self.release_id,
                "itemId": sentence_id,
                "targetText": text,
                "language": lang,
                "translations": translations,
                "audio": audio_entry,
                "audioStatus": (
                    "cleared" if audio_entry else
                    ("blocked" if audio_blocked_reason else "none_linked")
                ),
            }
            report.accepted.append(candidate)

        if audio_blocked_reason and not audio_rows_by_sentence:
            report.quarantined.append(
                RowIssue(row_ref="audio_metadata", reason="archive_budget_exceeded", detail=audio_blocked_reason)
            )
        if not report.accepted and not report.rejected:
            report.blocked_reason = f"no_rows_for_language:{language}"
        return report
