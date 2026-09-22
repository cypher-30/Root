package com.root.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Every entity keys on a UUID (never an autoincrement int) and carries its own
 * `updatedAt`, so content can be re-imported or updated later without breaking a
 * learner's existing history.
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

/** Outcome scale, not a feeling scale — the learner reports what actually happened
 *  ("did I get it right?"), not how confident they feel about it. This is a deliberate
 *  change from Kumbuka's original Blank/Shaky/OK/Solid confidence scale: immediate
 *  self-judgments of *confidence* are a documented weak signal (judgment-of-learning
 *  research shows they're poorly calibrated right after seeing the answer), whereas a
 *  concrete outcome question is far more checkable and closer to what the Duolingo
 *  half-life-regression paper (the AI D1 dataset) actually trains on — real correctness,
 *  not a felt sense of confidence. */
enum class ConfidenceLevel { MISSED, CLOSE, GOT_IT }

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

/** One nudge per week toward the thing recognition-only practice can't build:
 *  Swain's Output Hypothesis argues comprehension and production are different
 *  skills, and the gap between "I understand this" and "I can actually say this"
 *  only shows up when you try to produce it for real. This entity is that nudge —
 *  a themed suggestion to go use a few phrases in an actual conversation, not
 *  another drill. `theme` matches whichever pack the learner's been practicing, so
 *  the suggestion stays relevant instead of generic. */
@Entity(tableName = "weekly_challenges")
data class WeeklyChallengeEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "week_start") val weekStart: Long,
    val theme: String,
    val completed: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

/** Status of one durable local practice run (see [PracticeSessionEntity]). ACTIVE
 *  has at least one PENDING queue entry; PAGE_PAUSED means the current page is
 *  fully rated/skipped but the run is still open — [com.root.app.practice.PracticeRepository.continueSession]
 *  can fetch another page; ENDED is durably closed by Stop for now, Close this
 *  session, or a language/pack scope change, and is never resumed. */
enum class PracticeSessionStatus { ACTIVE, PAGE_PAUSED, ENDED }

/** Why a practice run ended, kept for its persisted summary. */
enum class PracticeEndReason { STOPPED, CLOSED, SCOPE_CHANGED }

/** Whether a queued occurrence is still awaiting a rating (PENDING), has been
 *  rated (RATED — an [AttemptEntity] sharing this row's own id exists), or was
 *  SKIPPED because its content became inaccessible or missing before it was shown. */
enum class QueueEntryState { PENDING, RATED, SKIPPED }

/**
 * One durable local practice run — see [com.root.app.practice.PracticeRepository].
 * Replaces the previous in-memory `SessionQueue` plus Bundle-based
 * `SavedStateHandle` restoration: a run now survives process death and reopening
 * without a 24-hour expiry, and Stop/Close durably end it rather than merely
 * finishing the Activity. [nextPosition] is a per-session monotonic counter used
 * to order the base page and append each Missed phrase's one-time tail retry
 * without ever reusing or colliding on a position.
 */
@Entity(tableName = "practice_sessions")
data class PracticeSessionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "language_id") val languageId: String,
    @ColumnInfo(name = "pack_id") val packId: String?,
    val status: PracticeSessionStatus,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    @ColumnInfo(name = "end_reason") val endReason: PracticeEndReason? = null,
    @ColumnInfo(name = "next_position") val nextPosition: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * One occurrence of a phrase within a [PracticeSessionEntity]: either a base-page
 * item or a single tail-appended Missed retry ([isRetry]/[originEntryId]). Content
 * is snapshotted ([promptSnapshot], [answerSnapshot], [audioSnapshot],
 * [phraseRevision]) so a mid-run content edit cannot silently change an active
 * card. This row's own [id] doubles as the linked [AttemptEntity.id] once rated —
 * that shared id is what makes rating idempotent (see
 * [com.root.app.practice.PracticeRepository.rate]): replaying the same entry id
 * after it is already RATED cannot insert a second attempt or double-advance the run.
 */
@Entity(
    tableName = "practice_queue_entries",
    foreignKeys = [
        ForeignKey(
            entity = PracticeSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id"), Index("phrase_id")],
)
data class PracticeQueueEntryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "phrase_id") val phraseId: String,
    @ColumnInfo(name = "pack_id_snapshot") val packIdSnapshot: String,
    @ColumnInfo(name = "prompt_snapshot") val promptSnapshot: String,
    @ColumnInfo(name = "answer_snapshot") val answerSnapshot: String,
    @ColumnInfo(name = "audio_snapshot") val audioSnapshot: String?,
    @ColumnInfo(name = "phrase_revision") val phraseRevision: Long,
    val position: Int,
    @ColumnInfo(name = "is_retry") val isRetry: Boolean,
    @ColumnInfo(name = "origin_entry_id") val originEntryId: String?,
    val state: QueueEntryState,
)
