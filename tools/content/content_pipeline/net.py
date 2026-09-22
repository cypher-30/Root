"""Minimal HTTP helper built on the standard library only.

Real network discovery (e.g. Dataverse/HF metadata API calls) goes through
this module so it can be monkeypatched/faked in tests without a live network
dependency, and so every caller gets the same bounded-size, honest-failure
behavior instead of ad hoc urllib usage scattered across adapters.
"""
from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Optional


class NetworkUnavailable(RuntimeError):
    """Raised (and caught by adapters) when a real HTTP call could not be
    completed. Adapters convert this into a structured blocked ImportReport
    rather than fabricating data."""


class AuthorizationRequired(RuntimeError):
    """Raised when a source requires credentials/terms acceptance this
    pipeline does not have. Adapters must not bypass access restrictions;
    this becomes a structured blocked result requesting authorized input."""


@dataclass
class HttpResponse:
    status: int
    body: bytes
    headers: dict[str, str]

    def json(self) -> dict:
        return json.loads(self.body.decode("utf-8"))


def head(url: str, timeout: float = 15.0,
         headers: Optional[dict[str, str]] = None) -> dict[str, str]:
    """Perform a HEAD request and return response headers only, so a caller
    can check Content-Length against a byte budget before ever downloading
    the body. Raises the same NetworkUnavailable/AuthorizationRequired as
    ``get``."""
    req = urllib.request.Request(
        url, headers=headers or {"User-Agent": "root-content-pipeline/0.1"}, method="HEAD"
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return dict(resp.headers)
    except urllib.error.HTTPError as e:
        if e.code in (401, 403):
            raise AuthorizationRequired(
                f"{url} returned HTTP {e.code} on HEAD; authorized credentials/terms "
                "acceptance are required and were not bypassed."
            ) from e
        raise NetworkUnavailable(f"{url} returned HTTP {e.code} on HEAD: {e.reason}") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise NetworkUnavailable(f"Could not HEAD {url}: {e}") from e


def get(url: str, timeout: float = 15.0, max_bytes: int = 10 * 1024 * 1024,
        headers: Optional[dict[str, str]] = None) -> HttpResponse:
    """Perform a bounded GET. Raises NetworkUnavailable on any connection
    failure/timeout, and AuthorizationRequired on 401/403. Enforces
    max_bytes so metadata-first discovery never silently pulls an
    unbounded archive."""
    req = urllib.request.Request(url, headers=headers or {"User-Agent": "root-content-pipeline/0.1"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status = getattr(resp, "status", 200)
            data = resp.read(max_bytes + 1)
            if len(data) > max_bytes:
                raise NetworkUnavailable(
                    f"Response for {url} exceeded max_bytes={max_bytes}; "
                    "refusing to buffer an unbounded/oversized body."
                )
            return HttpResponse(status=status, body=data, headers=dict(resp.headers))
    except urllib.error.HTTPError as e:
        if e.code in (401, 403):
            raise AuthorizationRequired(
                f"{url} returned HTTP {e.code}; authorized credentials/terms "
                "acceptance are required and were not bypassed."
            ) from e
        raise NetworkUnavailable(f"{url} returned HTTP {e.code}: {e.reason}") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise NetworkUnavailable(f"Could not reach {url}: {e}") from e
