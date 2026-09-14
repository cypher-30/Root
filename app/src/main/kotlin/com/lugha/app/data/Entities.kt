package com.lugha.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Schema convention borrowed from Kumbuka's DESIGN.md §7: every entity keys on a UUID
 * (never an autoincrement int) and carries its own `updatedAt`, so content can be
 * re-imported or updated later without breaking a learner's existing history.
 */

@Entity(tableName = "languages")
data class LanguageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,          // e.g. "Dholuo"
    val isPremium: Boolean,    // false for the free intro pack(s), gated by Paywall otherwise
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "packs",
    foreignKeys = [
        ForeignKey(
            entity = LanguageEntity::class,
            parentColumns = ["id"],
            childColumns = ["language_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("language_id")],
)
data class PackEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "language_id") val languageId: String,
    val theme: String,         // "Greetings", "Family", "Market", ...
    val sortOrder: Int,
    val isFree: Boolean,       // true for the first 1-2 packs per language
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "phrases",
    foreignKeys = [
        ForeignKey(
            entity = PackEntity::class,
            parentColumns = ["id"],
            childColumns = ["pack_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pack_id")],
)
data class PhraseEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "pack_id") val packId: String,
    val prompt: String,        // English (or the learner's known language)
    val answer: String,        // the target-language phrase
    @ColumnInfo(name = "audio_asset") val audioAsset: String?, // bundled asset filename, nullable until Block 2 content lands
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

/** Kumbuka's confidence scale, reused verbatim per DESIGN.md — the fuzzy, non-binary
 *  self-rating that stands in for "did you get it right." */
enum class ConfidenceLevel { BLANK, SHAKY, OK, SOLID }

@Entity(
    tableName = "attempts",
    foreignKeys = [
        ForeignKey(
            entity = PhraseEntity::class,
            parentColumns = ["id"],
            childColumns = ["phrase_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("phrase_id")],
)
data class AttemptEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "phrase_id") val phraseId: String,
    val confidence: ConfidenceLevel,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Long = System.currentTimeMillis(),
    /** When the hand-written scheduler wants this phrase shown again.
     *  No model anywhere in this app — see Scheduler.kt. */
    @ColumnInfo(name = "next_due_at") val nextDueAt: Long,
)
