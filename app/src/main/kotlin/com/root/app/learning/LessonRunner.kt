package com.root.app.learning

import androidx.room.withTransaction
import com.root.app.content.Activity
import com.root.app.content.ChoiceTask
import com.root.app.content.ContentJson
import com.root.app.content.EvaluableTask
import com.root.app.content.Lesson
import com.root.app.content.OrderedTokenTask
import com.root.app.content.PackManifest
import com.root.app.data.AppDatabase
import com.root.app.data.ContentDao
import com.root.app.data.LearningDao
import com.root.app.data.LearningEventEntity
import com.root.app.data.LearningEventKind
import com.root.app.data.LessonRunCommandEntity
import com.root.app.data.LessonRunEntity
import com.root.app.data.LessonRunStatus
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Resolves a pinned (packId, packVersion) to its [Lesson] content. Kept as an
 *  interface so pure/unit tests can supply fixtures without Room. */
fun interface LessonContentSource {
    suspend fun loadLesson(packId: String, packVersion: Int, lessonId: String): Lesson?
}

/** The production source: reads the immutable manifest already persisted by
 *  [ContentDao.insertPackVersion] and looks up the lesson within it. */
class RoomLessonContentSource(private val contentDao: ContentDao) : LessonContentSource {
    override suspend fun loadLesson(packId: String, packVersion: Int, lessonId: String): Lesson? {
        val row = contentDao.getPackVersion(packId, packVersion) ?: return null
        val manifest = ContentJson.decodeFromString<PackManifest>(row.manifestJson)
        return manifest.lessons.firstOrNull { it.id == lessonId }
    }
}

/**
 * The one durable offline runner shared by all three teaching formats (guided
 * conversation, listening/story, pattern workshop) — see docs/TEACHING_CONTRACTS.md.
 * Every command commits inside a single Room transaction: the idempotency
 * ledger entry, the response/evidence event, and the run's cursor/status all
 * change together or not at all.
 *
 * [assetAvailable] lets the caller (which owns the actual installer/filesystem)
 * report whether a listening activity's audio is actually present on-device;
 * a missing clip rejects that comprehension submission rather than silently
 * completing on transcript alone.
 */
class LessonRunner(
    private val db: AppDatabase,
    private val contentSource: LessonContentSource = RoomLessonContentSource(db.contentDao()),
    private val assetAvailable: suspend (packId: String, packVersion: Int, assetId: String) -> Boolean = { _, _, _ -> true },
) {
    private val learningDao: LearningDao = db.learningDao()

    suspend fun execute(command: LearningCommand): CommandResult = db.withTransaction {
        val payloadHash = LearningWire.hashCommand(command)
        val existing = learningDao.getCommand(command.commandId)
        if (existing != null) {
            return@withTransaction if (existing.payloadHash == payloadHash) {
                replayOf(LearningWire.decodeResult(existing.resultJson))
            } else {
                CommandResult.Rejected(
                    RejectionReason.COMMAND_CONFLICT,
                    "commandId '${command.commandId}' was already used with a different payload",
                )
            }
        }

        val result = when (command) {
            is LearningCommand.BeginOrResume -> handleBeginOrResume(command)
            is LearningCommand.Restart -> handleRestart(command)
            is LearningCommand.SubmitResponse -> handleSubmitResponse(command)
            is LearningCommand.RevealSupport -> handleRevealSupport(command)
            is LearningCommand.Advance -> handleAdvance(command)
            is LearningCommand.Pause -> handlePause(command)
        }

        val runId = (result as? CommandResult.Applied)?.state?.runId
            ?: ((result as? CommandResult.Rejected)?.let { runIdFromCommand(command) } ?: "")
        learningDao.insertCommand(
            LessonRunCommandEntity(
                commandId = command.commandId,
                runId = runId,
                commandType = command::class.simpleName ?: "unknown",
                payloadHash = payloadHash,
                resultJson = LearningWire.encodeResult(result),
            )
        )
        result
    }

    /** Read-only re-query of a run's current snapshot — issues no command, no
     *  ledger entry, no state mutation. Useful for a UI recomposing after
     *  process death/backgrounding without wanting to fabricate a new
     *  [LearningCommand.BeginOrResume]/[LearningCommand.commandId]. Returns
     *  null only if [runId] does not exist. */
    suspend fun currentState(runId: String): LessonRunState? {
        val run = learningDao.getRun(runId) ?: return null
        return buildState(run)
    }

    /** Read-only lookup of the existing open (ACTIVE/PAUSED) run for a lesson,
     *  if any — issues no command, creates no run, no ledger entry. Lets a UI
     *  render "Resume"/"Start" state for a lesson it has never opened this
     *  process without fabricating a [LearningCommand.BeginOrResume] just to
     *  find out. Returns null if the lesson has no currently-open run (a
     *  completed/never-started/superseded/unavailable lesson all return
     *  null here — callers wanting to distinguish those should still issue
     *  BeginOrResume, which is the only command that creates a run). */
    suspend fun openRunState(packId: String, lessonId: String): LessonRunState? {
        val run = learningDao.getOpenRun(packId, lessonId) ?: return null
        return buildState(run)
    }

    private fun runIdFromCommand(command: LearningCommand): String = when (command) {
        is LearningCommand.SubmitResponse -> command.runId
        is LearningCommand.RevealSupport -> command.runId
        is LearningCommand.Advance -> command.runId
        is LearningCommand.Pause -> command.runId
        else -> ""
    }

    /** A stored Applied result replayed for a duplicate commandId is reported
     *  as AlreadyApplied, not a fresh Applied — the command was not re-run. A
     *  stored Rejected result stays Rejected on replay. */
    private fun replayOf(stored: CommandResult): CommandResult = when (stored) {
        is CommandResult.Applied -> CommandResult.AlreadyApplied(stored.state)
        is CommandResult.AlreadyApplied -> stored
        is CommandResult.Rejected -> stored
    }

    private suspend fun handleBeginOrResume(cmd: LearningCommand.BeginOrResume): CommandResult {
        val open = learningDao.getOpenRun(cmd.packId, cmd.lessonId)
        if (open != null) return CommandResult.Applied(buildState(open))
        val lesson = contentSource.loadLesson(cmd.packId, cmd.packVersion, cmd.lessonId)
            ?: return CommandResult.Rejected(RejectionReason.LESSON_NOT_FOUND, "lesson '${cmd.lessonId}' not found in pack ${cmd.packId}@${cmd.packVersion}")
        val run = LessonRunEntity(
            lessonId = cmd.lessonId,
            packId = cmd.packId,
            packVersion = cmd.packVersion,
            lessonRevision = lesson.revision,
            status = LessonRunStatus.ACTIVE,
        )
        learningDao.insertRun(run)
        return CommandResult.Applied(buildState(run))
    }

    private suspend fun handleRestart(cmd: LearningCommand.Restart): CommandResult {
        val lesson = contentSource.loadLesson(cmd.packId, cmd.packVersion, cmd.lessonId)
            ?: return CommandResult.Rejected(RejectionReason.LESSON_NOT_FOUND, "lesson '${cmd.lessonId}' not found in pack ${cmd.packId}@${cmd.packVersion}")
        val previouslyOpen = learningDao.getOpenRun(cmd.packId, cmd.lessonId)
        val run = LessonRunEntity(
            lessonId = cmd.lessonId,
            packId = cmd.packId,
            packVersion = cmd.packVersion,
            lessonRevision = lesson.revision,
            status = LessonRunStatus.ACTIVE,
        )
        learningDao.insertRun(run)
        // The previous run's evidence (events) is preserved untouched; only its
        // pointer is updated so it is no longer treated as "the" open run.
        if (previouslyOpen != null) {
            learningDao.updateRun(
                id = previouslyOpen.id,
                status = previouslyOpen.status,
                currentStepIndex = previouslyOpen.currentStepIndex,
                revealedActivityIds = previouslyOpen.revealedActivityIds,
                supersededByRunId = run.id,
                completedAt = previouslyOpen.completedAt,
                updatedAt = System.currentTimeMillis(),
            )
        }
        return CommandResult.Applied(buildState(run))
    }

    private suspend fun handleSubmitResponse(cmd: LearningCommand.SubmitResponse): CommandResult {
        val run = learningDao.getRun(cmd.runId) ?: return CommandResult.Rejected(RejectionReason.RUN_NOT_FOUND, "no run '${cmd.runId}'")
        if (run.status.isEnded()) return CommandResult.Rejected(RejectionReason.RUN_ENDED, "run already completed or unavailable")
        val lesson = contentSource.loadLesson(run.packId, run.packVersion, run.lessonId)
            ?: return CommandResult.Rejected(RejectionReason.LESSON_NOT_FOUND, "pinned lesson content missing")
        val activity = lesson.activities.getOrNull(run.currentStepIndex)
            ?: return CommandResult.Rejected(RejectionReason.STEP_MISMATCH, "run has no current step")
        if (activity.id != cmd.activityId) {
            return CommandResult.Rejected(RejectionReason.ACTIVITY_MISMATCH, "current step is '${activity.id}', not '${cmd.activityId}'")
        }

        val evaluation = evaluate(activity, cmd.response, run.packId, run.packVersion)
            ?: return CommandResult.Rejected(RejectionReason.INVALID_RESPONSE, "response does not match activity '${activity.id}'")
        if (evaluation == Evaluation.AUDIO_UNAVAILABLE) {
            return CommandResult.Rejected(RejectionReason.AUDIO_UNAVAILABLE, "listening audio is not available on this device")
        }

        val alreadyAttempted = learningDao.latestEventForActivity(run.id, activity.id) != null
        val alreadyAssisted = activity.id in revealedSet(run.revealedActivityIds)
        val kind = when {
            evaluation == Evaluation.NOT_EVALUABLE -> LearningEventKind.EXPOSURE
            evaluation == Evaluation.SELF_REPORT -> LearningEventKind.SELF_REPORT
            alreadyAttempted || alreadyAssisted -> LearningEventKind.ASSISTED
            else -> LearningEventKind.CHECKED
        }
        val correct = when (evaluation) {
            Evaluation.CORRECT -> true
            Evaluation.INCORRECT -> false
            else -> null
        }
        learningDao.insertEvent(
            LearningEventEntity(
                runId = run.id,
                activityId = activity.id,
                kind = kind,
                responseJson = ContentJson.encodeToString(cmd.response),
                correct = correct,
            )
        )
        return CommandResult.Applied(buildState(learningDao.getRun(run.id)!!))
    }

    private suspend fun handleRevealSupport(cmd: LearningCommand.RevealSupport): CommandResult {
        val run = learningDao.getRun(cmd.runId) ?: return CommandResult.Rejected(RejectionReason.RUN_NOT_FOUND, "no run '${cmd.runId}'")
        if (run.status.isEnded()) return CommandResult.Rejected(RejectionReason.RUN_ENDED, "run already completed or unavailable")
        val revealed = revealedSet(run.revealedActivityIds)
        if (cmd.activityId !in revealed) {
            learningDao.updateRun(
                id = run.id,
                status = run.status,
                currentStepIndex = run.currentStepIndex,
                revealedActivityIds = (revealed + cmd.activityId).joinToString(","),
                supersededByRunId = run.supersededByRunId,
                completedAt = run.completedAt,
                updatedAt = System.currentTimeMillis(),
            )
        }
        return CommandResult.Applied(buildState(learningDao.getRun(run.id)!!))
    }

    private suspend fun handleAdvance(cmd: LearningCommand.Advance): CommandResult {
        val run = learningDao.getRun(cmd.runId) ?: return CommandResult.Rejected(RejectionReason.RUN_NOT_FOUND, "no run '${cmd.runId}'")
        if (run.status.isEnded()) return CommandResult.Rejected(RejectionReason.RUN_ENDED, "run already completed or unavailable")
        val lesson = contentSource.loadLesson(run.packId, run.packVersion, run.lessonId)
            ?: return CommandResult.Rejected(RejectionReason.LESSON_NOT_FOUND, "pinned lesson content missing")
        val activity = lesson.activities.getOrNull(run.currentStepIndex)
            ?: return CommandResult.Rejected(RejectionReason.STEP_MISMATCH, "run has no current step")
        if (activity.id != cmd.expectedActivityId) {
            return CommandResult.Rejected(
                RejectionReason.STEP_MISMATCH,
                "run's current step is '${activity.id}', not '${cmd.expectedActivityId}' — it already advanced",
            )
        }
        if (learningDao.latestEventForActivity(run.id, activity.id) == null) {
            return CommandResult.Rejected(RejectionReason.STEP_MISMATCH, "current step '${activity.id}' has no recorded response yet")
        }

        val nextIndex = run.currentStepIndex + 1
        val now = System.currentTimeMillis()
        if (nextIndex >= lesson.activities.size) {
            val requiredMissing = lesson.requiredActivityIds.any { reqId -> !isRequiredActivitySatisfied(run.id, lesson, reqId) }
            if (requiredMissing) {
                return CommandResult.Rejected(RejectionReason.STEP_MISMATCH, "required steps are not all completed yet")
            }
            learningDao.updateRun(
                id = run.id,
                status = LessonRunStatus.COMPLETED,
                currentStepIndex = nextIndex,
                revealedActivityIds = run.revealedActivityIds,
                supersededByRunId = run.supersededByRunId,
                completedAt = now,
                updatedAt = now,
            )
        } else {
            learningDao.updateRun(
                id = run.id,
                status = run.status,
                currentStepIndex = nextIndex,
                revealedActivityIds = run.revealedActivityIds,
                supersededByRunId = run.supersededByRunId,
                completedAt = run.completedAt,
                updatedAt = now,
            )
        }
        return CommandResult.Applied(buildState(learningDao.getRun(run.id)!!, lesson))
    }

    private suspend fun handlePause(cmd: LearningCommand.Pause): CommandResult {
        val run = learningDao.getRun(cmd.runId) ?: return CommandResult.Rejected(RejectionReason.RUN_NOT_FOUND, "no run '${cmd.runId}'")
        if (run.status.isEnded()) return CommandResult.Rejected(RejectionReason.RUN_ENDED, "run already completed or unavailable")
        if (run.status != LessonRunStatus.PAUSED) {
            learningDao.updateRun(
                id = run.id,
                status = LessonRunStatus.PAUSED,
                currentStepIndex = run.currentStepIndex,
                revealedActivityIds = run.revealedActivityIds,
                supersededByRunId = run.supersededByRunId,
                completedAt = run.completedAt,
                updatedAt = System.currentTimeMillis(),
            )
        }
        return CommandResult.Applied(buildState(learningDao.getRun(run.id)!!))
    }

    private fun LessonRunStatus.isEnded(): Boolean = this == LessonRunStatus.COMPLETED || this == LessonRunStatus.UNAVAILABLE

    /**
     * Whether [reqId]'s required-activity evidence actually counts toward
     * completion. A required [Activity.Listening] step whose *only* recorded
     * evidence is an [LearningEventKind.EXPOSURE] acknowledgement of missing/
     * unavailable audio does not count: the learner can still Advance past it
     * and explore other lessons (this is not a per-step gate), but the lesson
     * as a whole never reaches COMPLETED until real playable audio and a real
     * comprehension attempt exist. Every other required activity kind (and a
     * Listening step with real CHECKED/ASSISTED comprehension evidence) counts
     * as soon as any event is recorded, exactly as before.
     */
    private suspend fun isRequiredActivitySatisfied(runId: String, lesson: Lesson, reqId: String): Boolean {
        val event = learningDao.latestEventForActivity(runId, reqId) ?: return false
        val activity = lesson.activities.firstOrNull { it.id == reqId }
        return !(activity is Activity.Listening && event.kind == LearningEventKind.EXPOSURE)
    }

    private enum class Evaluation { CORRECT, INCORRECT, NOT_EVALUABLE, SELF_REPORT, AUDIO_UNAVAILABLE }

    /** Returns null when [response]'s shape does not match [activity]'s kind at
     *  all (a genuinely invalid submission), otherwise the evaluation outcome. */
    private suspend fun evaluate(activity: Activity, response: ActivityResponse, packId: String, packVersion: Int): Evaluation? =
        when (activity) {
            is Activity.DialogueTurn, is Activity.PatternExplanation, is Activity.Reflection ->
                if (response is ActivityResponse.Acknowledged) Evaluation.NOT_EVALUABLE else null

            is Activity.SpeakingPrompt ->
                if (response is ActivityResponse.SelfReport) Evaluation.SELF_REPORT else null

            is Activity.ChoiceActivity ->
                if (response is ActivityResponse.Choice) evaluateChoice(activity.task, response.choiceId) else null

            is Activity.OrderedTokenActivity ->
                if (response is ActivityResponse.OrderedTokens) evaluateOrderedTokens(activity.task, response.occurrenceIds) else null

            is Activity.Listening -> {
                if (activity.audioAssetId == null || !assetAvailable(packId, packVersion, activity.audioAssetId)) {
                    // Honest permanent/temporary unavailability: an actual comprehension
                    // attempt is meaningless (there is no audio to judge), so it is
                    // rejected rather than faked — but a plain [Acknowledged] response
                    // records that the learner encountered the honest unavailable state,
                    // which IS real evidence (same EXPOSURE treatment as Reflection/
                    // DialogueTurn). Without this, any lesson that requires a Listening
                    // activity whose audio is null could never be completed, since no
                    // event would ever be recorded for it.
                    if (response is ActivityResponse.Acknowledged) Evaluation.NOT_EVALUABLE else Evaluation.AUDIO_UNAVAILABLE
                } else {
                    when (val c = activity.comprehension) {
                        is EvaluableTask.Choice ->
                            if (response is ActivityResponse.Choice) evaluateChoice(c.task, response.choiceId) else null
                        is EvaluableTask.OrderedTokens ->
                            if (response is ActivityResponse.OrderedTokens) evaluateOrderedTokens(c.task, response.occurrenceIds) else null
                    }
                }
            }
        }

    private fun evaluateChoice(task: ChoiceTask, choiceId: String): Evaluation =
        if (ChoiceEvaluator.evaluate(task, choiceId)) Evaluation.CORRECT else Evaluation.INCORRECT

    private fun evaluateOrderedTokens(task: OrderedTokenTask, order: List<String>): Evaluation =
        if (OrderedTokenEvaluator.evaluate(task, order)) Evaluation.CORRECT else Evaluation.INCORRECT

    private fun revealedSet(raw: String): Set<String> = raw.split(",").filter { it.isNotBlank() }.toSet()

    private suspend fun buildState(run: LessonRunEntity, lessonHint: Lesson? = null): LessonRunState {
        val lesson = lessonHint ?: contentSource.loadLesson(run.packId, run.packVersion, run.lessonId)
        val total = lesson?.activities?.size ?: 0
        val currentActivity = lesson?.activities?.getOrNull(run.currentStepIndex)
        val requiredTotal = lesson?.requiredActivityIds?.size ?: 0
        val requiredCompleted = lesson?.requiredActivityIds?.count { reqId ->
            isRequiredActivitySatisfied(run.id, lesson, reqId)
        } ?: 0
        val feedback = currentActivity?.let { learningDao.latestEventForActivity(run.id, it.id) }?.let { event ->
            event.responseJson?.let { json ->
                ActivityFeedback(
                    response = ContentJson.decodeFromString(json),
                    correct = event.correct,
                    kind = event.kind.name,
                )
            }
        }
        return LessonRunState(
            runId = run.id,
            lessonId = run.lessonId,
            packId = run.packId,
            packVersion = run.packVersion,
            lessonRevision = run.lessonRevision,
            status = run.status.name,
            currentActivityId = currentActivity?.id,
            currentActivityIndex = run.currentStepIndex,
            totalActivities = total,
            requiredCompletedCount = requiredCompleted,
            requiredTotalCount = requiredTotal,
            assistanceUsedActivityIds = revealedSet(run.revealedActivityIds).toList(),
            currentActivityFeedback = feedback,
            completed = run.status == LessonRunStatus.COMPLETED,
        )
    }
}
