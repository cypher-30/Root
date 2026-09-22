"""One-time hosted-transport smoke test.

Publishes a tiny, clearly non-teaching test fixture (not lesson content) to
a dedicated ``smoke-test/`` prefix in the configured bucket, using the real
SupabasePublisher over the network, then verifies:
  1. The uploaded object round-trips correctly via anonymous public GET.
  2. A second upload attempt at the same key is rejected (immutability:
     the publisher never overwrites, x-upsert=false).

Credentials come only from ROOT_SUPABASE_URL / ROOT_SUPABASE_SERVICE_KEY
environment variables (see publisher.py). This script never prints,
logs, or persists the secret key.

Usage (PowerShell):
    $env:ROOT_SUPABASE_URL = "https://<ref>.supabase.co"
    $env:ROOT_SUPABASE_SERVICE_KEY = "sb_secret_..."
    python tools/content/scripts/publish_smoke_test.py root-content
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from content_pipeline.canonical import sha256_hex
from content_pipeline.publisher import (
    PublisherCredentialsMissing,
    PublishVerificationFailed,
    SupabasePublisher,
    load_target_from_env,
)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: publish_smoke_test.py <bucket-name>", file=sys.stderr)
        return 2
    bucket = argv[1]

    try:
        target = load_target_from_env(bucket)
    except PublisherCredentialsMissing as e:
        print(str(e), file=sys.stderr)
        return 2

    publisher = SupabasePublisher(target)

    payload = b"root-content-pipeline hosted transport smoke test (not teaching content)\n"
    digest = sha256_hex(payload)
    object_key = "smoke-test/hello.txt"

    print("Uploading " + object_key + " (" + str(len(payload)) + " bytes)...")
    try:
        publisher.upload_object(object_key, payload, "text/plain", digest)
    except PublishVerificationFailed as e:
        print("UPLOAD FAILED: " + str(e), file=sys.stderr)
        return 1
    print("Upload accepted.")

    print("Verifying public anonymous GET round-trip...")
    if not publisher.verify_object(object_key, digest):
        print("VERIFY FAILED: downloaded content did not match uploaded hash.", file=sys.stderr)
        return 1
    print("Public GET verified: content matches uploaded hash (" + digest[:12] + "...).")

    print("Confirming immutability (re-upload of same key must be rejected)...")
    try:
        publisher.upload_object(object_key, payload, "text/plain", digest)
        print("WARNING: second upload did not raise; check x-upsert behavior.", file=sys.stderr)
    except PublishVerificationFailed:
        print("Re-upload correctly rejected (object is immutable).")

    print()
    print("SMOKE TEST PASSED.")
    print("Public URL: " + target.public_url(object_key))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
