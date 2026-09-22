package com.root.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Focused Room tables backing the durable lesson runner in
 * `com.root.app.learning`. Deliberately separate from [AttemptEntity]/
 * [PracticeSessionEntity]: teaching evidence is never an [AttemptEntity] and
 * never changes the recall scheduler on its own — see docs/TEACHING_CONTRACTS.md.
 */

/** ACTIVE has an unacknowledged current step; PAUSED is durably parked (leaving
 *  the lesson, or an explicit pause) and resumes at its exact pinned revision;
 *  COMPLETED means every required step was completed/acknowledged. UNAVAILABLE
 *  is a terminal state used only when the owning pack/version is retired or
 *  uninstalled while a run was still open — evidence is preserved, but the run
 *  can never resume (its content is gone). Restart never reuses/reopens a
 *  COMPLETED, UNAVAILABLE, or superseded run — it starts a new one. */
enum class LessonRunStatus { ACTIVE, PAUSED, COMPLETED, UNAVAILABLE }

/** What kind of evidence one [LearningEventEntity] row represents. EXPOSURE is
 *  a display-only step being seen/acknowledged (dialogue turn, explanation,
 *  reflection). ASSISTED marks a checked task recorded *after* a hint/reveal or
 *  a retry following a prior wrong answer on the same activity in this run —
 *  supported/repeated practice, never independent evidence. CHECKED is a
 *  first-attempt, unassisted, correctly-evaluated response. SELF_REPORT is the
 *  optional self-assessed speaking step, which can never block completion. */
enum class LearningEventKind { EXPOSURE, ASSISTED, CHECKED, SELF_REPORT }

/**
 * One durable lesson run. Pins [packId]/[packVersion]/[lessonRevision] so a
 * pack update never rewrites an in-progress run's content out from under it.
 * [revealedActivityIds] persists which activities have had assistance
 * revealed *before* an answer was shown, satisfying "assistance persists
 * before reveal, across process death." At most one ACTIVE/PAUSED run exists
 * per ([packId], [lessonId]) on this device — enforced by
 * [LearningDao.getOpenRun], not a unique index, since completed/superseded
 * history rows must coexist. [lessonId] alone is only unique within its own
 * manifest, never globally across packs, so lookups always scope by both.
 */
@Entity(
    tableName = "lesson_runs",
    indices = [
        Index("lesson_id"),
        Index(value = ["lesson_id", "status"]),
        Index(value = ["pack_id", "lesson_id", "status"]),
    ],
)
data class LessonRunEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "lesson_id") val lessonId: String,
    @ColumnInfo(name = "pack_id") val packId: String,
    @ColumnInfo(name = "pack_version") val packVersion: Int,
    @ColumnInfo(name = "lesson_revision") val lessonRevision: Int,
    val status: LessonRunStatus,
    @ColumnInfo(name = "current_step_index") val currentStepIndex: Int = 0,
    /** Comma-separated activity ids (each already ID-charset-constrained, so a
     *  plain separator is unambiguous) with assistance revealed this run. */
    @ColumnInfo(name = "revealed_activity_ids") val revealedActivityIds: String = "",
    @ColumnInfo(name = "superseded_by_run_id") val supersededByRunId: String? = null,
    @ColumnInfo(name = "started_at") val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * One durable piece of learning evidence. Stored separately from
 * [AttemptEntity] by design: a lesson run never manufactures a recall
 * Got-it/Missed attempt on its own (see plan §Durable commands and evidence).
 * [responseJson] is the serialized [com.root.app.learning.ActivityResponse],
 * kept opaque here since only the runner needs to interpret it.
 */
@Entity(
    tableName = "learning_events",
    foreignKeys = [
        ForeignKey(
            entity = LessonRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("run_id"), Index(value = ["run_id", "activity_id"])],
)
data class LearningEventEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "activity_id") val activityId: String,
    val kind: LearningEventKind,
    @ColumnInfo(name = "response_json") val responseJson: String? = null,
    val correct: Boolean? = null,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long = System.currentTimeMillis(),
)

/**
 * Idempotency ledger for every durable runner command. [commandId] is the
 * caller-issued stable id (globally unique — a client never reuses one across
 * different logical commands). Replaying the same [commandId] with an
 * identical [payloadHash] returns the stored [resultJson] verbatim
 * (AlreadyApplied); replaying it with a *different* payload is rejected as a
 * conflict rather than silently re-executed.
 */
@Entity(
    tableName = "lesson_run_commands",
    indices = [Index("run_id")],
)
data class LessonRunCommandEntity(
    @PrimaryKey @ColumnInfo(name = "command_id") val commandId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "command_type") val commandType: String,
    @ColumnInfo(name = "payload_hash") val payloadHash: String,
    @ColumnInfo(name = "result_json") val resultJson: String,
    @ColumnInfo(name = "applied_at") val appliedAt: Long = System.currentTimeMillis(),
)
