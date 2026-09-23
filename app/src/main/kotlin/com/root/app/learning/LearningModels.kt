package com.root.app.learning

import com.root.app.content.ContentJson
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Typed durable-runner contracts: commands the UI issues, the response shapes
 * a learner can submit, and the results the runner returns. See
 * docs/TEACHING_CONTRACTS.md for the frozen API summary. Every command carries
 * its own stable [LearningCommand.commandId]; replaying the same id with an
 * identical payload is a no-op (AlreadyApplied), and replaying it with a
 * different payload is rejected as a conflict — never silently re-applied.
 */
@Serializable
sealed interface LearningCommand {
    val commandId: String

    @Serializable
    @SerialName("begin_or_resume")
    data class BeginOrResume(
        override val commandId: String,
        val packId: String,
        val packVersion: Int,
        val lessonId: String,
    ) : LearningCommand

    @Serializable
    @SerialName("submit_response")
    data class SubmitResponse(
        override val commandId: String,
        val runId: String,
        val activityId: String,
        val response: ActivityResponse,
    ) : LearningCommand

    /** Persists that assistance (a hint/translation/explanation reveal) was
     *  requested for [activityId] *before* the caller shows that support to the
     *  learner — the durable record always precedes the UI reveal, across
     *  process death. */
    @Serializable
    @SerialName("reveal_support")
    data class RevealSupport(
        override val commandId: String,
        val runId: String,
        val activityId: String,
    ) : LearningCommand

    /** Acknowledges the current step's feedback and moves the cursor forward
     *  one step (or completes the run at the final step). [expectedActivityId]
     *  must equal the run's actual current activity: this closes a
     *  delayed-retry gap where a distinct [commandId] (not a literal
     *  duplicate, so idempotency alone cannot catch it) arrives after an
     *  earlier Advance already moved the cursor — without this check it would
     *  silently advance a second time past a step the caller never saw. */
    @Serializable
    @SerialName("advance")
    data class Advance(override val commandId: String, val runId: String, val expectedActivityId: String) : LearningCommand

    /** Durably parks an ACTIVE run — leaving the lesson calls this, not a
     *  client-only pause. Idempotent. */
    @Serializable
    @SerialName("pause")
    data class Pause(override val commandId: String, val runId: String) : LearningCommand

    /** Starts a brand-new run for the same lesson without erasing the previous
     *  run's evidence; any still-open prior run is marked superseded, not
     *  deleted or resumed. */
    @Serializable
    @SerialName("restart")
    data class Restart(
        override val commandId: String,
        val packId: String,
        val packVersion: Int,
        val lessonId: String,
    ) : LearningCommand
}

/** What a learner actually submitted for one activity. */
@Serializable
sealed interface ActivityResponse {
    @Serializable
    @SerialName("choice")
    data class Choice(val choiceId: String) : ActivityResponse

    @Serializable
    @SerialName("ordered_tokens")
    data class OrderedTokens(val occurrenceIds: List<String>) : ActivityResponse

    /** Self-assessed speaking report — never independently checked, never
     *  blocks completion. */
    @Serializable
    @SerialName("self_report")
    data class SelfReport(val practiced: Boolean) : ActivityResponse

    /** Acknowledgement of a display-only step (dialogue turn, pattern
     *  explanation, reflection) — there is nothing to evaluate. */
    @Serializable
    @SerialName("acknowledged")
    data object Acknowledged : ActivityResponse
}

enum class RejectionReason {
    COMMAND_CONFLICT,
    RUN_NOT_FOUND,
    RUN_ENDED,
    LESSON_NOT_FOUND,
    ACTIVITY_MISMATCH,
    STEP_MISMATCH,
    INVALID_RESPONSE,
    AUDIO_UNAVAILABLE,
    /** The run's pack has been retired (uninstalled) since the run was opened,
     *  or a new/restarted run was requested for a pack that is not currently
     *  installed. Distinct from [RUN_ENDED]: the run itself may still be
     *  ACTIVE/PAUSED in storage, but its content is no longer available to
     *  play, so every further mutation on it is refused. */
    PACK_UNAVAILABLE,
}

/** A UI-facing snapshot of one run — never a raw Room entity. */
@Serializable
data class LessonRunState(
    val runId: String,
    val lessonId: String,
    val packId: String,
    val packVersion: Int,
    val lessonRevision: Int,
    val status: String,
    val currentActivityId: String?,
    val currentActivityIndex: Int,
    val totalActivities: Int,
    val requiredCompletedCount: Int,
    val requiredTotalCount: Int,
    val assistanceUsedActivityIds: List<String>,
    /** The last recorded response/evidence for [currentActivityId] in this run,
     *  if any — lets a resuming UI restore exactly what was submitted and
     *  whether it was correct, instead of showing a blank step the learner
     *  already answered before leaving. Null if the current step has no
     *  recorded response yet (a fresh, unanswered step). */
    val currentActivityFeedback: ActivityFeedback? = null,
    val completed: Boolean,
)

/** What a resuming UI needs to redraw the exact feedback state of the current
 *  activity without re-deriving it from raw [com.root.app.data.LearningEventEntity]
 *  rows itself. [kind] mirrors [com.root.app.data.LearningEventKind] by name
 *  (e.g. "CHECKED"/"ASSISTED"/"EXPOSURE"/"SELF_REPORT"). */
@Serializable
data class ActivityFeedback(
    val response: ActivityResponse,
    val correct: Boolean?,
    val kind: String,
)

@Serializable
sealed interface CommandResult {
    @Serializable
    @SerialName("applied")
    data class Applied(val state: LessonRunState) : CommandResult

    /** The same [LearningCommand.commandId] was already committed with an
     *  identical payload; this is the stored outcome replayed verbatim. */
    @Serializable
    @SerialName("already_applied")
    data class AlreadyApplied(val state: LessonRunState) : CommandResult

    @Serializable
    @SerialName("rejected")
    data class Rejected(val reason: RejectionReason, val message: String) : CommandResult
}

/** Canonical, stable JSON encoding used both to hash a command's payload for
 *  idempotency-conflict detection and to persist a command's stored result. */
internal object LearningWire {
    fun hashCommand(command: LearningCommand): String {
        val json = ContentJson.encodeToString(command)
        return sha256Hex(json)
    }

    fun encodeResult(result: CommandResult): String = ContentJson.encodeToString(result)

    fun decodeResult(json: String): CommandResult = ContentJson.decodeFromString(json)

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
