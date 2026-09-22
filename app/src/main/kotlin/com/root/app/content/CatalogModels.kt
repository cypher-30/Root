package com.root.app.content

import kotlinx.serialization.Serializable

/**
 * The lightweight, frequently-refreshed summary of every published pack —
 * distinct from a [PackManifest], which is fetched only when a pack is actually
 * installed/updated. Catalog entries are immutable per (id, version): a content
 * edit always produces a new [version] and a new [manifestSha256], never an
 * in-place mutation of a published entry.
 */
@Serializable
data class CatalogEntry(
    val id: String,
    val version: Int,
    val language: ContentLanguage,
    val title: String,
    val publication: PublicationStatus,
    val unitCount: Int,
    val lessonCount: Int,
    val phraseCount: Int,
    val audioCount: Int,
    val manifestKey: String,
    val manifestSha256: String,
    val manifestBytes: Long,
    /** Total bytes a client must fetch to fully install this pack (manifest +
     *  all referenced assets) — distinct from [manifestBytes], which is just
     *  the manifest document itself. */
    val downloadBytes: Long,
    val retired: Boolean = false,
)

@Serializable
data class Catalog(
    val schemaVersion: Int,
    val catalogRevision: Int,
    val entries: List<CatalogEntry>,
)

/** Size and shape budgets enforced by [ContentValidator], not merely aspirational
 *  documentation. Values per the approved plan. */
object ContentLimits {
    const val CATALOG_MAX_BYTES: Long = 2L * 1024 * 1024
    const val MANIFEST_MAX_BYTES: Long = 4L * 1024 * 1024
    const val MAX_ASSETS_PER_PACK: Int = 256
    const val MAX_ASSET_BYTES: Long = 20L * 1024 * 1024
    const val MAX_TOTAL_ASSET_BYTES: Long = 50L * 1024 * 1024
}
