"""Shared adapter interface and bounded ingestion contract.

Every source adapter (FLEURS, KenCorpus, Common Voice, Tatoeba) implements
``SourceAdapter``: a ``discover`` step (metadata-first, no bulk archive
fetch) and an ``import_candidates`` step bounded by
``DEFAULT_SMOKE_ITEM_LIMIT`` unless the caller explicitly raises the limit.
Adapters never fabricate rows: an unavailable download, unsupported
language, or missing authorization produces a ``blocked_reason`` on the
returned ``ImportReport`` instead of invented content.
"""
from __future__ import annotations

import abc
from dataclasses import dataclass
from typing import Any, Optional

from ..limits import DEFAULT_SMOKE_ITEM_LIMIT
from ..reports import ImportReport


@dataclass
class DiscoveryResult:
    """Result of a metadata-first discovery call."""
    source_id: str
    release_id: str
    available: bool
    languages: list[str]
    item_count: Optional[int]
    detail: str
    blocked_reason: Optional[str] = None


class SourceAdapter(abc.ABC):
    source_id: str
    adapter_version: str = "0.1.0"

    @abc.abstractmethod
    def discover(self, **kwargs: Any) -> DiscoveryResult:
        """Metadata-first inspection: confirm release/version, languages,
        and item counts without downloading bulk content."""

    @abc.abstractmethod
    def import_candidates(
        self,
        language: str,
        limit: int = DEFAULT_SMOKE_ITEM_LIMIT,
        **kwargs: Any,
    ) -> ImportReport:
        """Import up to ``limit`` candidate rows for ``language``. Must
        return a well-formed ImportReport even when the run is blocked
        (auth required, language unsupported, archive budget exceeded)."""
