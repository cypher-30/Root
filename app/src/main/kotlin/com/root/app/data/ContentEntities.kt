package com.root.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Focused Room tables for downloadable teaching packs. Per the approved plan,
 * this stores one immutable, already-[com.root.app.content.ContentValidator]-validated
 * manifest JSON blob per content revision plus indexed metadata — not a table
 * per wire DTO. Filesystem/network installation itself is out of scope here;
 * these entities/DAOs are the contract the installer (owned elsewhere) is
 * expected to drive. See docs/TEACHING_CONTRACTS.md.
 */

/** Installed/ready state of a pack, tracked independently of any install job so
 *  a failed update never disturbs an already-working older revision. */
enum class InstalledPackStatus { READY, RETIRED }

enum class PackInstallJobStatus { PENDING, DOWNLOADING, VALIDATING, INSTALLING, SUCCEEDED, FAILED, CANCELLED }

/**
 * One immutable content revision of a pack. A new editorial revision always
 * inserts a new row with a bumped [version]; existing rows are never mutated,
 * so a lesson run pinned to an old revision keeps working after an update.
 */
@Entity(
    tableName = "pack_versions",
    indices = [Index(value = ["pack_id", "version"], unique = true)],
)
data class PackVersionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "pack_id") val packId: String,
    val version: Int,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "min_reader_version") val minReaderVersion: Int,
    @ColumnInfo(name = "language_id") val languageId: String,
    @ColumnInfo(name = "language_code") val languageCode: String,
    @ColumnInfo(name = "language_name") val languageName: String,
    val title: String,
    /** "development" | "published" — mirrors [com.root.app.content.PublicationStatus]. */
    val publication: String,
    @ColumnInfo(name = "manifest_json") val manifestJson: String,
    @ColumnInfo(name = "manifest_sha256") val manifestSha256: String,
    @ColumnInfo(name = "phrase_count") val phraseCount: Int,
    @ColumnInfo(name = "lesson_count") val lessonCount: Int,
    @ColumnInfo(name = "asset_count") val assetCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

/**
 * The current installed/ready pointer for a pack — separate from
 * [PackInstallJobEntity] so an in-flight or failed job never overwrites a
 * working install. [status] `RETIRED` means uninstalled/unavailable, not
 * deleted: the row (and its [PackVersionEntity] history) is preserved.
 */
@Entity(tableName = "installed_packs")
data class InstalledPackEntity(
    @PrimaryKey @ColumnInfo(name = "pack_id") val packId: String,
    @ColumnInfo(name = "current_version") val currentVersion: Int,
    val status: InstalledPackStatus,
    @ColumnInfo(name = "installed_at") val installedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * One install/update attempt. [requestId] is client-supplied and unique, so
 * retrying the same install request never creates a duplicate job. The
 * filesystem/network work itself belongs to the installer (a different
 * owner); this table only records the job's lifecycle and outcome.
 */
@Entity(
    tableName = "pack_install_jobs",
    indices = [Index(value = ["request_id"], unique = true), Index("pack_id")],
)
data class PackInstallJobEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "request_id") val requestId: String,
    @ColumnInfo(name = "pack_id") val packId: String,
    @ColumnInfo(name = "target_version") val targetVersion: Int,
    val status: PackInstallJobStatus,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "started_at") val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Maps one managed (pack-owned) recall phrase to its underlying
 * [PhraseEntity] row. A managed phrase has exactly one owning pack in this
 * version (no anthology membership yet). [retired] (equivalently: `active =
 * !retired`) flips availability without touching [PhraseEntity]/
 * [AttemptEntity] identity or history — practice selection should exclude
 * retired managed phrases while a phrase absent from this table
 * (legacy/personal) remains eligible unconditionally. Retiring or uninstalling
 * a pack never deletes this row (or the underlying [PhraseEntity]/attempt
 * history); it only flips [retired]. The phrase foreign key deliberately uses
 * `ON DELETE RESTRICT`, not `CASCADE`: a [PhraseEntity] can never be silently
 * deleted out from under an existing managed-phrase/attempt history — history
 * is preserved even across a pack update to a newer [packVersion] (an older
 * pack revision's own immutable [PackVersionEntity] row/manifest is never
 * mutated, so which phrases a prior revision managed remains reconstructable
 * there).
 */
@Entity(
    tableName = "managed_phrases",
    foreignKeys = [
        ForeignKey(
            entity = PhraseEntity::class,
            parentColumns = ["id"],
            childColumns = ["phrase_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("phrase_id", unique = true), Index("pack_id")],
)
data class ManagedPhraseEntity(
    @PrimaryKey @ColumnInfo(name = "phrase_id") val phraseId: String,
    @ColumnInfo(name = "pack_id") val packId: String,
    @ColumnInfo(name = "pack_version") val packVersion: Int,
    @ColumnInfo(name = "source_phrase_id") val sourcePhraseId: String,
    val retired: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * One packaged media object actually referenced by an installed pack version.
 * Kept as its own indexed row (in addition to living inside the immutable
 * [PackVersionEntity.manifestJson]) so retirement/cleanup can find which media
 * is still referenced without re-parsing every manifest. [localUri] is filled
 * in by the installer once the file is materialized; null until then.
 */
@Entity(
    tableName = "content_assets",
    indices = [Index(value = ["pack_id", "pack_version", "asset_id"], unique = true)],
)
data class ContentAssetEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "pack_id") val packId: String,
    @ColumnInfo(name = "pack_version") val packVersion: Int,
    @ColumnInfo(name = "asset_id") val assetId: String,
    val key: String,
    val sha256: String,
    val bytes: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "local_uri") val localUri: String? = null,
)

/** A pure predicate for whether a managed phrase is currently eligible for
 *  practice selection, kept alongside the entity it reads. Legacy/personal
 *  phrases (no [ManagedPhraseEntity] row) are always eligible; only a managed
 *  phrase can be excluded, and only by explicit retirement. */
object ManagedContentAccess {
    fun isEligible(managed: ManagedPhraseEntity?): Boolean = managed == null || !managed.retired
}
