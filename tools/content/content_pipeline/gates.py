"""Publication and ingestion gates.

Raw acquisition and candidate import never imply editorial approval. These
functions turn registry rights/review records and editorial revision state
into explicit accept/block decisions consumed by the pack builder. Nothing
here silently treats "development" as public-safe, and stale approvals
(content edited after review) are rejected rather than carried forward.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from .registry import ComponentRecord, SourceRegistry


@dataclass
class GateDecision:
    allowed: bool
    reason: str


def check_component_publishable(record: ComponentRecord) -> GateDecision:
    if record.rights_status != "cleared":
        return GateDecision(False, f"rights_status={record.rights_status}, must be 'cleared'")
    if record.review_status == "stale":
        return GateDecision(False, "review is stale; content changed after the pinned review revision")
    if record.review_status != "native_reviewed":
        return GateDecision(False, f"review_status={record.review_status}, must be 'native_reviewed'")
    return GateDecision(True, "cleared and native_reviewed")


def check_source_checked_only(record: ComponentRecord) -> GateDecision:
    """A weaker gate used for development-only material: source_checked is
    accepted, but unknown/incompatible rights are never accepted even for
    development content."""
    if record.rights_status == "incompatible":
        return GateDecision(False, "rights_status=incompatible")
    if record.rights_status == "unknown":
        return GateDecision(False, "rights_status=unknown; unresolved rights block even development use")
    return GateDecision(True, f"rights_status=cleared, review_status={record.review_status} (development-eligible)")


def check_publication_status_allowed(status: str) -> GateDecision:
    if status != "published":
        return GateDecision(False, f"publication='{status}' packs are never published to the public catalog")
    return GateDecision(True, "publication=published")


def check_review_revision_matches(record: ComponentRecord, current_revision: int) -> GateDecision:
    """Reject stale approvals: if the recorded reviewRevision does not match
    the content's current revision, the approval no longer covers this
    content and must be treated as unreviewed."""
    if record.review_status != "native_reviewed":
        return check_component_publishable(record)
    if record.review_revision is None or record.review_revision != current_revision:
        return GateDecision(
            False,
            f"stale approval: reviewRevision={record.review_revision} != content revision={current_revision}",
        )
    return GateDecision(True, "review revision matches content revision")


def check_audio_assets_present_for_publication(manifest: dict) -> GateDecision:
    """listening activities may honestly have a null audioAssetId when no
    authentic recording exists yet -- but ONLY for a manifest whose
    publication is not 'published'. A non-null audioAssetId must
    always name an asset that actually exists in this manifest's assets[]
    (never a placeholder id invented just to satisfy a required-string
    field). This is a semantic check no JSON Schema alone can express,
    since it spans multiple parts of the document (publication,
    every lesson activity, and the assets list). Mirrors the Kotlin
    ContentValidator's PUBLISHED_LISTENING_MISSING_AUDIO check."""
    status = manifest.get("publication")
    asset_ids = {a["id"] for a in manifest.get("assets", [])}
    problems = []
    for lesson in manifest.get("lessons", []):
        for activity in lesson.get("activities", []):
            if activity.get("kind") != "listening":
                continue
            audio_id = activity.get("audioAssetId")
            activity_ref = f"{lesson.get('id')}/{activity.get('id')}"
            if audio_id is None:
                if status == "published":
                    problems.append(
                        f"{activity_ref}: audioAssetId is null, which is only permitted for "
                        "development (non-published) manifests"
                    )
            elif audio_id not in asset_ids:
                problems.append(
                    f"{activity_ref}: audioAssetId '{audio_id}' has no matching entry in manifest.assets"
                )
    if problems:
        return GateDecision(False, "; ".join(problems))
    return GateDecision(True, "every listening activity is null-only-if-not-published or references a real asset")

