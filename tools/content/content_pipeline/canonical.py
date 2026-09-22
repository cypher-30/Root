"""Deterministic canonical JSON serialization and hashing.

Canonicalization guarantees that identical normalized inputs and tool versions
produce byte-identical content artifacts (manifests, catalog) regardless of
platform, dict insertion order, or locale. Only structural JSON canonical
form is used here -- no external canonicalization library is required.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any


def canonical_json_bytes(obj: Any) -> bytes:
    """Serialize ``obj`` to deterministic, minified, UTF-8 JSON bytes.

    - Object keys are sorted.
    - No insignificant whitespace.
    - Non-ASCII characters are preserved as UTF-8 (not \\uXXXX escaped) so
      diacritics/script stay directly inspectable in committed fixtures.
    """
    text = json.dumps(
        obj,
        sort_keys=True,
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return text.encode("utf-8")


def canonical_json_text(obj: Any) -> str:
    return canonical_json_bytes(obj).decode("utf-8")


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_of_json(obj: Any) -> str:
    return sha256_hex(canonical_json_bytes(obj))


def sha256_of_file(path: Path, chunk_size: int = 1024 * 1024) -> tuple[str, int]:
    """Return (sha256_hex, byte_size) streaming the file in chunks.

    Streaming avoids loading a large media asset fully into memory, and keeps
    the same hashing path usable for arbitrarily large future assets even
    though a single asset is currently capped well below memory limits.
    """
    h = hashlib.sha256()
    size = 0
    with open(path, "rb") as f:
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            h.update(chunk)
            size += len(chunk)
    return h.hexdigest(), size
