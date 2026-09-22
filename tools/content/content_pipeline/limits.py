"""Protocol-wide safety and versioning constants.

These mirror the Android client's ceilings exactly (see docs/CONTENT.md and the
plan's "Initial safety ceilings" section) so the builder and the client never
disagree about what is installable. Values are bytes unless noted.
"""
from __future__ import annotations

# JSON document ceilings.
MAX_CATALOG_BYTES = 2 * 1024 * 1024          # 2 MiB
MAX_MANIFEST_BYTES = 4 * 1024 * 1024         # 4 MiB (manifest/content JSON)

# Media/asset ceilings.
MAX_ASSETS_PER_PACK = 256
MAX_ASSET_BYTES = 20 * 1024 * 1024           # 20 MiB per media object
MAX_PACK_TOTAL_BYTES = 50 * 1024 * 1024      # 50 MiB total pack payload

# Total honest download size a user is shown before requesting a pack:
# manifestBytes + sum(asset.bytes). Kept equal to MAX_PACK_TOTAL_BYTES (the
# asset-only ceiling) as the combined ceiling users are promised, since the
# manifest itself is a tiny fraction of any real pack's payload; enforced
# explicitly in pack_builder.catalog_entry_for rather than assumed.
MAX_DOWNLOAD_BYTES = 50 * 1024 * 1024        # 50 MiB

# Bounded ingestion ceiling: never pull more than this many candidate items in
# a single source smoke import, and never fetch an archive above this budget
# without an explicit authorized local copy / approval.
DEFAULT_SMOKE_ITEM_LIMIT = 20
DEFAULT_ARCHIVE_BYTE_BUDGET = 25 * 1024 * 1024  # 25 MiB, well below pack total

# Schema/reader compatibility.
CURRENT_SCHEMA_VERSION = 1
MIN_READER_VERSION = 1

# Supported publication statuses. "development" packs are never published to
# the public catalog (see PublicationGate in gates.py). Kotlin's
# PublicationStatus has no "retired" value -- retirement is tracked as a
# separate boolean on CatalogEntry, not a manifest-level publication status.
PUBLICATION_STATUSES = ("development", "published")

# Supported lesson format kinds, matching Kotlin LessonFormat.
LESSON_KINDS = ("guided_conversation", "listening", "pattern_workshop")

# Supported Activity "kind" discriminator values, matching the frozen Kotlin
# Activity sealed hierarchy in docs/TEACHING_CONTRACTS.md.
ACTIVITY_KINDS = (
    "dialogue_turn",
    "pattern_explanation",
    "choice",
    "ordered_tokens",
    "listening",
    "reflection",
    "speaking_prompt",
)


# Filesystem/transport identifier and relative-key policy, aligned with the
# Android client's ContentTransport: IDs are 1..120 chars matching
# [A-Za-z0-9][A-Za-z0-9._-]*; relative object keys are <=512 chars matching
# [A-Za-z0-9._/-]+ with no empty/'.'/'..' path segments and no percent
# encoding, query strings, colons, or backslashes (guaranteed here because
# every path segment must itself match the ID pattern below, and '.'/'..'
# cannot start with an alphanumeric character).
ID_PATTERN = r"^[A-Za-z0-9][A-Za-z0-9._-]*$"
ID_MAX_LENGTH = 120
RELATIVE_KEY_SEGMENT_PATTERN = r"[A-Za-z0-9][A-Za-z0-9._-]*"
RELATIVE_KEY_MAX_LENGTH = 512
SHA256_HEX_PATTERN = r"^[0-9a-f]{64}$"

# Parent filesystem/transport storage convention: immutable manifests live at
# root_content/versions/<packId>/<version>/manifest.json; downloaded media is
# named by asset id, never by the original source filename.
MANIFEST_KEY_PREFIX = "root_content/versions"
AUDIO_FILENAME_PREFIX = "audio-"


def manifest_key_for(pack_id: str, version: int) -> str:
    """The parent's immutable manifest storage key for one pack version."""
    return MANIFEST_KEY_PREFIX + "/" + pack_id + "/" + str(version) + "/manifest.json"

