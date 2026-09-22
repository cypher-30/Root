"""Controlled Supabase Storage publisher.

Publishes files first, verifies stored objects, then immutable manifests,
and updates the catalog pointer last. Pack assets/manifests are uploaded
immutably (x-upsert: false) since they are content-addressed and must never
be silently overwritten; the catalog pointer is the one object uploaded
mutably (x-upsert: true), since every release updates that same key in
place. Maintainer credentials come only from environment variables (never
committed, never logged, never BuildConfig). No upload proceeds without an
explicit project/bucket selection and an explicit approval flag from the
caller -- this module never infers "publish" from merely having credentials
present.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Callable, Optional

from . import net

SUPABASE_URL_ENV = "ROOT_SUPABASE_URL"
SUPABASE_SERVICE_KEY_ENV = "ROOT_SUPABASE_SERVICE_KEY"


class PublishNotApproved(RuntimeError):
    pass


class PublisherCredentialsMissing(RuntimeError):
    pass


class PublishVerificationFailed(RuntimeError):
    pass


@dataclass
class PublishTarget:
    project_url: str
    bucket: str
    service_key: str

    def object_url(self, object_key: str) -> str:
        return self.project_url.rstrip("/") + "/storage/v1/object/" + self.bucket + "/" + object_key.lstrip("/")

    def public_url(self, object_key: str) -> str:
        return self.project_url.rstrip("/") + "/storage/v1/object/public/" + self.bucket + "/" + object_key.lstrip("/")


def load_target_from_env(bucket: str) -> PublishTarget:
    """Load maintainer credentials from environment variables only. Raises
    PublisherCredentialsMissing (never a silent no-op) if not configured.
    Callers must never print/log the returned service_key."""
    url = os.environ.get(SUPABASE_URL_ENV)
    key = os.environ.get(SUPABASE_SERVICE_KEY_ENV)
    if not url or not key:
        raise PublisherCredentialsMissing(
            "Set " + SUPABASE_URL_ENV + " and " + SUPABASE_SERVICE_KEY_ENV
            + " environment variables (maintainer-only; never commit them) before publishing."
        )
    return PublishTarget(project_url=url, bucket=bucket, service_key=key)


class SupabasePublisher:
    """Thin wrapper around Supabase Storage's REST API. All network calls go
    through an injectable transport function so tests can fully mock upload
    behavior without a real project or credentials."""

    def __init__(
        self,
        target: PublishTarget,
        upload_fn: Optional[Callable] = None,
        get_fn: Optional[Callable] = None,
    ):
        self._target = target
        self._upload_fn = upload_fn or self._default_upload
        self._get_fn = get_fn or net.get

    def _auth_headers(self) -> dict:
        return {
            "Authorization": "Bearer " + self._target.service_key,
            "apikey": self._target.service_key,
        }

    def _default_upload(self, object_key: str, data: bytes, content_type: str, mutable: bool) -> None:
        import urllib.request
        url = self._target.object_url(object_key)
        headers = dict(self._auth_headers())
        headers["Content-Type"] = content_type
        # Pack assets/manifests are content-addressed and must never be
        # overwritten (x-upsert: false). The catalog pointer is the one
        # object that *must* be mutable -- every release updates the same
        # catalog key in place (x-upsert: true) -- otherwise every release
        # after the first would fail with a conflict on this exact object.
        headers["x-upsert"] = "true" if mutable else "false"
        req = urllib.request.Request(url, data=data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                if resp.status not in (200, 201):
                    raise PublishVerificationFailed(
                        "Upload of " + object_key + " returned unexpected status " + str(resp.status)
                    )
        except Exception as e:
            raise PublishVerificationFailed("Upload of " + object_key + " failed: " + str(e)) from e

    def upload_object(
        self, object_key: str, data: bytes, content_type: str, expected_sha256: str, mutable: bool = False,
    ) -> None:
        from .canonical import sha256_hex
        actual = sha256_hex(data)
        if actual != expected_sha256:
            raise PublishVerificationFailed(
                "Refusing to upload " + object_key + ": local hash " + actual
                + " != expected " + expected_sha256
            )
        self._upload_fn(object_key, data, content_type, mutable)

    def verify_object(self, object_key: str, expected_sha256: str) -> bool:
        from .canonical import sha256_hex
        resp = self._get_fn(self._target.public_url(object_key), max_bytes=30 * 1024 * 1024)
        return sha256_hex(resp.body) == expected_sha256

    def publish_pack(
        self,
        asset_uploads: list,  # list[(object_key, bytes, content_type, sha256)]
        manifest_key: str,
        manifest_bytes: bytes,
        manifest_sha256: str,
        approved: bool,
    ) -> None:
        """Files first (verified), then the immutable manifest. Does not
        touch the catalog -- callers publish the catalog pointer separately
        and only after this call succeeds for every referenced pack."""
        if not approved:
            raise PublishNotApproved(
                "publish_pack requires an explicit approved=True from the caller; "
                "raw rights/review clearance alone does not authorize an upload."
            )
        for object_key, data, content_type, expected_sha in asset_uploads:
            self.upload_object(object_key, data, content_type, expected_sha)
            if not self.verify_object(object_key, expected_sha):
                raise PublishVerificationFailed(
                    "Post-upload verification failed for asset " + object_key
                )
        self.upload_object(manifest_key, manifest_bytes, "application/json", manifest_sha256)
        if not self.verify_object(manifest_key, manifest_sha256):
            raise PublishVerificationFailed("Post-upload verification failed for manifest " + manifest_key)

    def publish_catalog(
        self, catalog_key: str, catalog_bytes: bytes, catalog_sha256: str, approved: bool,
    ) -> None:
        """Catalog pointer update -- always the last step of a release."""
        if not approved:
            raise PublishNotApproved("publish_catalog requires an explicit approved=True from the caller.")
        self.upload_object(catalog_key, catalog_bytes, "application/json", catalog_sha256, mutable=True)
        if not self.verify_object(catalog_key, catalog_sha256):
            raise PublishVerificationFailed("Post-upload verification failed for catalog " + catalog_key)
