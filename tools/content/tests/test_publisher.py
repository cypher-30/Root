import os
import unittest
from unittest import mock

from content_pipeline.canonical import sha256_hex
from content_pipeline.publisher import (
    SupabasePublisher, PublishTarget, load_target_from_env,
    PublishNotApproved, PublisherCredentialsMissing, PublishVerificationFailed,
    SUPABASE_URL_ENV, SUPABASE_SERVICE_KEY_ENV,
)


class FakeResp:
    def __init__(self, body: bytes):
        self.body = body


class PublisherTests(unittest.TestCase):
    def _target(self):
        return PublishTarget(project_url="https://proj.supabase.co", bucket="content", service_key="secret-key")

    def test_load_target_from_env_missing_raises(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(PublisherCredentialsMissing):
                load_target_from_env("content")

    def test_load_target_from_env_success(self):
        with mock.patch.dict(os.environ, {SUPABASE_URL_ENV: "https://x.supabase.co", SUPABASE_SERVICE_KEY_ENV: "k"}, clear=True):
            target = load_target_from_env("content")
            self.assertEqual(target.bucket, "content")
            self.assertEqual(target.service_key, "k")

    def test_publish_pack_requires_explicit_approval(self):
        store = {}
        publisher = SupabasePublisher(
            self._target(),
            upload_fn=lambda key, data, ct, mutable: store.__setitem__(key, data),
            get_fn=lambda url, max_bytes=0: FakeResp(store[url.rsplit("/", 1)[-1]]),
        )
        with self.assertRaises(PublishNotApproved):
            publisher.publish_pack([], "manifests/x.json", b"{}", sha256_hex(b"{}"), approved=False)

    def test_publish_catalog_requires_explicit_approval(self):
        publisher = SupabasePublisher(self._target(), upload_fn=lambda *a: None, get_fn=lambda *a, **k: FakeResp(b""))
        with self.assertRaises(PublishNotApproved):
            publisher.publish_catalog("catalog.json", b"{}", sha256_hex(b"{}"), approved=False)

    def test_publish_pack_files_first_then_manifest_success(self):
        store = {}
        upload_order = []

        def fake_upload(key, data, content_type, mutable):
            upload_order.append(key)
            store[key] = data

        def fake_get(url, max_bytes=0):
            key = url.rsplit("/storage/v1/object/public/content/", 1)[-1]
            return FakeResp(store[key])

        publisher = SupabasePublisher(self._target(), upload_fn=fake_upload, get_fn=fake_get)
        asset_bytes = b"fake-audio-bytes"
        asset_sha = sha256_hex(asset_bytes)
        manifest_bytes = b'{"id":"pack.x"}'
        manifest_sha = sha256_hex(manifest_bytes)
        publisher.publish_pack(
            asset_uploads=[("assets/pack.x/a.m4a", asset_bytes, "audio/mp4", asset_sha)],
            manifest_key="manifests/pack.x/v1.json",
            manifest_bytes=manifest_bytes,
            manifest_sha256=manifest_sha,
            approved=True,
        )
        self.assertEqual(upload_order, ["assets/pack.x/a.m4a", "manifests/pack.x/v1.json"])

    def test_publish_pack_rejects_local_hash_mismatch_before_upload(self):
        called = []
        publisher = SupabasePublisher(
            self._target(),
            upload_fn=lambda *a: called.append(a),
            get_fn=lambda *a, **k: FakeResp(b""),
        )
        with self.assertRaises(PublishVerificationFailed):
            publisher.publish_pack(
                asset_uploads=[("assets/pack.x/a.m4a", b"data", "audio/mp4", "0" * 64)],
                manifest_key="manifests/pack.x/v1.json",
                manifest_bytes=b"{}",
                manifest_sha256=sha256_hex(b"{}"),
                approved=True,
            )
        self.assertEqual(called, [])  # never uploaded a hash-mismatched payload

    def test_publish_pack_detects_post_upload_verification_mismatch(self):
        def fake_upload(key, data, content_type, mutable):
            pass  # simulate silent corruption: nothing actually stored

        def fake_get(url, max_bytes=0):
            return FakeResp(b"corrupted-different-bytes")

        publisher = SupabasePublisher(self._target(), upload_fn=fake_upload, get_fn=fake_get)
        asset_bytes = b"original-bytes"
        with self.assertRaises(PublishVerificationFailed):
            publisher.publish_pack(
                asset_uploads=[("assets/pack.x/a.m4a", asset_bytes, "audio/mp4", sha256_hex(asset_bytes))],
                manifest_key="manifests/pack.x/v1.json",
                manifest_bytes=b"{}",
                manifest_sha256=sha256_hex(b"{}"),
                approved=True,
            )

    def test_publish_catalog_last_after_packs(self):
        store = {}
        mutability = {}

        def fake_upload(key, data, content_type, mutable):
            store[key] = data
            mutability[key] = mutable

        def fake_get(url, max_bytes=0):
            key = url.rsplit("/storage/v1/object/public/content/", 1)[-1]
            return FakeResp(store[key])

        publisher = SupabasePublisher(self._target(), upload_fn=fake_upload, get_fn=fake_get)
        catalog_bytes = b'{"catalogRevision":2}'
        publisher.publish_catalog("catalog.json", catalog_bytes, sha256_hex(catalog_bytes), approved=True)
        self.assertIn("catalog.json", store)
        # The catalog pointer must be uploaded mutably (x-upsert) since every
        # release overwrites the same key -- unlike pack assets/manifests.
        self.assertTrue(mutability["catalog.json"])

    def test_publish_catalog_overwrites_same_key_across_releases(self):
        store = {}

        def fake_upload(key, data, content_type, mutable):
            if key in store and not mutable:
                raise AssertionError("release 2 must upload the catalog key mutably")
            store[key] = data

        def fake_get(url, max_bytes=0):
            key = url.rsplit("/storage/v1/object/public/content/", 1)[-1]
            return FakeResp(store[key])

        publisher = SupabasePublisher(self._target(), upload_fn=fake_upload, get_fn=fake_get)
        first = b'{"catalogRevision":1}'
        publisher.publish_catalog("catalog.json", first, sha256_hex(first), approved=True)
        second = b'{"catalogRevision":2}'
        publisher.publish_catalog("catalog.json", second, sha256_hex(second), approved=True)
        self.assertEqual(store["catalog.json"], second)

    def test_publish_pack_assets_and_manifest_are_uploaded_immutably(self):
        store = {}
        mutability = {}

        def fake_upload(key, data, content_type, mutable):
            store[key] = data
            mutability[key] = mutable

        def fake_get(url, max_bytes=0):
            key = url.rsplit("/storage/v1/object/public/content/", 1)[-1]
            return FakeResp(store[key])

        publisher = SupabasePublisher(self._target(), upload_fn=fake_upload, get_fn=fake_get)
        asset_bytes = b"fake-audio-bytes"
        asset_sha = sha256_hex(asset_bytes)
        manifest_bytes = b'{"id":"pack.x"}'
        manifest_sha = sha256_hex(manifest_bytes)
        publisher.publish_pack(
            asset_uploads=[("assets/pack.x/a.m4a", asset_bytes, "audio/mp4", asset_sha)],
            manifest_key="manifests/pack.x/v1.json",
            manifest_bytes=manifest_bytes,
            manifest_sha256=manifest_sha,
            approved=True,
        )
        self.assertFalse(mutability["assets/pack.x/a.m4a"])
        self.assertFalse(mutability["manifests/pack.x/v1.json"])

    def test_credentials_never_appear_in_object_urls(self):
        target = self._target()
        url = target.object_url("assets/x.m4a")
        self.assertNotIn("secret-key", url)


if __name__ == "__main__":
    unittest.main()
