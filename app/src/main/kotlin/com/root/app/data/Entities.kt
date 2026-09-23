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

/**
 * Local-only consent record for a personally-contributed phrase's optional
 * reference recording (see [com.root.app.ui.ContributeScreen]/
 * [RootRepository.contribute]). Exists only while its [PhraseEntity] does —
 * `ON DELETE CASCADE` means deleting the phrase (see
 * [RootRepository.deletePersonalPhrase]) permanently removes this consent
 * record too, never leaving an orphaned consent trail for audio that no
 * longer exists. This models "consent covers local recording only": there is
 * no server-side record, no re-sharing, and no separate opt-out flow — the
 * only way this consent record stops existing is the phrase (and its
 * recording) being deleted from this device. [speakerLabel] is optional
 * free text the contributor entered to remember who was recorded (e.g. "Aunt
 * Rudo"), never a real identity/contact record.
 */
@Entity(
    tableName = "phrase_consents",
    foreignKeys = [
        ForeignKey(
            entity = PhraseEntity::class,
            parentColumns = ["id"],
            childColumns = ["phrase_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PhraseConsentEntity(
    @PrimaryKey @ColumnInfo(name = "phrase_id") val phraseId: String,
    @ColumnInfo(name = "speaker_label") val speakerLabel: String?,
    @ColumnInfo(name = "consent_given_at") val consentGivenAt: Long = System.currentTimeMillis(),
    /** Which revision of the consent wording the contributor agreed to (see
     *  [CURRENT_CONSENT_VERSION]). Bumping the constant does not retroactively
     *  change what an existing recording's consent means; it only requires a
     *  fresh acknowledgement next time that wording changes meaningfully. */
    @ColumnInfo(name = "consent_version", defaultValue = "1") val consentVersion: Int = CURRENT_CONSENT_VERSION,
    /** Always "local recording only" today — there is no server-side record,
     *  re-sharing, or separate opt-out flow. Stored explicitly (rather than
     *  assumed) so a future scope actually has to introduce a new value
     *  instead of silently reinterpreting an old row. */
    @ColumnInfo(name = "consent_scope", defaultValue = "LOCAL_RECORDING_ONLY") val consentScope: String = "LOCAL_RECORDING_ONLY",
) {
    companion object {
        const val CURRENT_CONSENT_VERSION = 1
    }
}

/**
 * A private note the learner attached to their own personal phrase (see
 * [RootRepository.deletePersonalPhrase] and the archive detail view). Never
 * shown to anyone else, never leaves the device, and is always deleted with
 * its phrase (`ON DELETE CASCADE`) — a personal-word deletion must not leave
 * an orphaned note behind. Exactly one note per phrase; upserting overwrites.
 */
@Entity(
    tableName = "personal_notes",
    foreignKeys = [
        ForeignKey(
            entity = PhraseEntity::class,
            parentColumns = ["id"],
            childColumns = ["phrase_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PersonalNoteEntity(
    @PrimaryKey @ColumnInfo(name = "phrase_id") val phraseId: String,
    @ColumnInfo(name = "note_text") val noteText: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * A learner's self-reported "I practiced this out loud" acknowledgement for a
 * phrase — entirely separate from the recall scheduler. Keying on `phraseId`
 * as the primary key (not an autoincrement id) makes repeated taps a natural
 * upsert onto the same row rather than a growing log, which is what makes
 * marking practiced idempotent: tapping it five times in a row leaves exactly
 * one row, same as tapping it once. Writing this row must NEVER create an
 * [AttemptEntity] or touch a phrase's `next_due_at` — see [RootRepository.markPracticed].
 */
@Entity(
    tableName = "practice_marks",
    foreignKeys = [
        ForeignKey(
            entity = PhraseEntity::class,
            parentColumns = ["id"],
            childColumns = ["phrase_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PracticeMarkEntity(
    @PrimaryKey @ColumnInfo(name = "phrase_id") val phraseId: String,
    @ColumnInfo(name = "marked_at") val markedAt: Long = System.currentTimeMillis(),
)


/** Lifecycle of one [ContributionDraftEntity]'s recorded audio. Mirrors the
 *  recording flow in [com.root.app.ui.ContributeScreen]/[RootAudioSession]:
 *  a fresh draft has no audio; [RECORDING] and [RECORDED] track an in-flight
 *  or completed take; [DISCARDED] means the learner explicitly threw away a
 *  take and can record again; [COMMITTED] means the draft was promoted into a
 *  real [PhraseEntity] and its consent/reference audio and must not be reused
 *  or shown as an editable draft again. */
enum class ContributionAudioState { NONE, RECORDING, RECORDED, DISCARDED, COMMITTED }

/**
 * A durable, resumable "add a word" draft. Replaces screen-level
 * `rememberSaveable` state in [com.root.app.ui.ContributeScreen]: process
 * death, a phone call, or a low-memory kill mid-recording must not silently
 * lose typed text or a just-finished take before the learner chooses Save or
 * Discard. [audioDraftPath] points at a temporary file distinct from any
 * phrase's permanent reference recording — see [MediaFileFactEntity] for how
 * promoting that temp file into a permanent one is tracked separately from
 * activating the DB row that points at it, so a process death between "file
 * copied" and "phrase row committed" has a restartable recovery path rather
 * than either losing the take or committing a phrase with no audio.
 */
@Entity(tableName = "contribution_drafts")
data class ContributionDraftEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "language_id") val languageId: String,
    @ColumnInfo(name = "pack_id") val packId: String?,
    @ColumnInfo(name = "prompt_draft") val promptDraft: String,
    @ColumnInfo(name = "answer_draft") val answerDraft: String,
    @ColumnInfo(name = "speaker_label_draft") val speakerLabelDraft: String?,
    @ColumnInfo(name = "audio_draft_path") val audioDraftPath: String?,
    @ColumnInfo(name = "audio_state") val audioState: ContributionAudioState = ContributionAudioState.NONE,
    @ColumnInfo(name = "committed_phrase_id") val committedPhraseId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

/** What is actually true on disk for a piece of learner-owned media, checked
 *  independently of whatever a DB pointer claims. [PRESENT] means the file
 *  exists and (when [MediaFileFactEntity.expectedSha256] is set) matches its
 *  expected hash; [MISSING] means the path does not exist; [CORRUPT] means it
 *  exists but fails a hash/read check; [PENDING_CLEANUP] means an old file a
 *  DB pointer no longer references still needs its bytes removed — used so a
 *  failed delete is reported as "cleanup pending" rather than silently
 *  claimed as erased (see [RootRepository.deletePersonalPhrase]). */
enum class MediaFileStatus { PRESENT, MISSING, CORRUPT, PENDING_CLEANUP }

/** What kind of learner-owned media a [MediaFileFactEntity] describes.
 *  [PACK_VERSION_MEDIA] is the one non-learner-owned case: a whole managed
 *  content pack version's media directory (subjectId is `"$packId:$version"`)
 *  whose deletion was deferred because it was still pinned by an open lesson
 *  run/practice session at uninstall time — see
 *  [com.root.app.content.ContentLibrary.uninstall]/[retryPendingContentCleanup]. */
enum class MediaFileSubject { PHRASE_REFERENCE_AUDIO, CONTRIBUTION_DRAFT_AUDIO, LEARNER_TAKE_AUDIO, PACK_VERSION_MEDIA }

/**
 * Ground truth about one on-disk media file, separate from whichever entity
 * points at it. This is what lets byte promotion (copying/renaming a file
 * into place), DB pointer activation (a phrase/draft row referencing it), and
 * deferred cleanup (removing bytes an old pointer no longer needs) be tracked
 * as three distinct steps: a process death between any two of them leaves a
 * row here that a recovery pass can use to finish or roll back safely,
 * instead of the app silently trusting a DB pointer that may be one step
 * ahead of or behind the real file.
 */
@Entity(
    tableName = "media_file_facts",
    indices = [Index("subject", "subject_id")],
)
data class MediaFileFactEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val subject: MediaFileSubject,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "expected_sha256") val expectedSha256: String?,
    val status: MediaFileStatus,
    @ColumnInfo(name = "checked_at") val checkedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)
