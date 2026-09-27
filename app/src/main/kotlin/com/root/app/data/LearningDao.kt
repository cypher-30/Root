package com.root.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LearningDao {
    @Insert
    suspend fun insertRun(run: LessonRunEntity)

    @Query("SELECT * FROM lesson_runs WHERE id = :id")
    suspend fun getRun(id: String): LessonRunEntity?

    /** The single unfinished (ACTIVE/PAUSED) run for a lesson within one pack,
     *  if any — the basis for "at most one unfinished run per lesson on this
     *  device." Scoped by [packId] as well as [lessonId] since a lesson id is
     *  only unique within its own manifest, not globally across packs. */
    @Query(
        """
        SELECT * FROM lesson_runs
        WHERE pack_id = :packId AND lesson_id = :lessonId AND status IN ('ACTIVE', 'PAUSED') AND superseded_by_run_id IS NULL
        ORDER BY started_at DESC LIMIT 1
        """
    )
    suspend fun getOpenRun(packId: String, lessonId: String): LessonRunEntity?

    @Query("SELECT * FROM lesson_runs WHERE lesson_id = :lessonId ORDER BY started_at DESC")
    suspend fun runsForLesson(lessonId: String): List<LessonRunEntity>

    /** The newest completed run of one lesson at one content revision — what an
     *  ordinary reopen shows instead of silently starting a fresh attempt. */
    @Query(
        """
        SELECT * FROM lesson_runs
        WHERE pack_id = :packId AND lesson_id = :lessonId AND status = 'COMPLETED' AND lesson_revision = :lessonRevision
        ORDER BY completed_at DESC, started_at DESC LIMIT 1
        """
    )
    suspend fun latestCompletedRun(packId: String, lessonId: String, lessonRevision: Int): LessonRunEntity?

    @Query("SELECT * FROM lesson_runs WHERE pack_id = :packId")
    suspend fun runsForPack(packId: String): List<LessonRunEntity>

    /** Emits whenever any lesson run changes, so Learn progress stays live. */
    @Query("SELECT * FROM lesson_runs")
    fun observeAllRuns(): kotlinx.coroutines.flow.Flow<List<LessonRunEntity>>

    /** The most recent non-superseded run for one lesson within one pack,
     *  regardless of status — read-only projection for the Learn path's
     *  Start/Resume/Completed display (see [com.root.app.learning.LessonRunner.progressForPack]).
     *  Superseded rows are excluded since a newer run already replaced them. */
    @Query(
        """
        SELECT * FROM lesson_runs
        WHERE pack_id = :packId AND lesson_id = :lessonId AND superseded_by_run_id IS NULL
        ORDER BY started_at DESC LIMIT 1
        """
    )
    suspend fun latestRun(packId: String, lessonId: String): LessonRunEntity?

    @Query(
        """
        UPDATE lesson_runs SET status = :status, current_step_index = :currentStepIndex,
            revealed_activity_ids = :revealedActivityIds, superseded_by_run_id = :supersededByRunId,
            completed_at = :completedAt, updated_at = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateRun(
        id: String,
        status: LessonRunStatus,
        currentStepIndex: Int,
        revealedActivityIds: String,
        supersededByRunId: String?,
        completedAt: Long?,
        updatedAt: Long,
    )

    @Insert
    suspend fun insertEvent(event: LearningEventEntity)

    @Query("SELECT * FROM learning_events WHERE run_id = :runId ORDER BY occurred_at, id")
    suspend fun eventsForRun(runId: String): List<LearningEventEntity>

    @Query(
        """
        SELECT * FROM learning_events WHERE run_id = :runId AND activity_id = :activityId
        ORDER BY occurred_at DESC, id DESC LIMIT 1
        """
    )
    suspend fun latestEventForActivity(runId: String, activityId: String): LearningEventEntity?

    @Insert
    suspend fun insertCommand(command: LessonRunCommandEntity)

    @Query("SELECT * FROM lesson_run_commands WHERE command_id = :commandId")
    suspend fun getCommand(commandId: String): LessonRunCommandEntity?
}
