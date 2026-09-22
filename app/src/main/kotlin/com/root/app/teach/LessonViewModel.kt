package com.root.app.teach

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.root.app.content.Activity
import com.root.app.content.Lesson
import com.root.app.data.AppDatabase
import com.root.app.learning.ActivityFeedback
import com.root.app.learning.ActivityResponse
import com.root.app.learning.CommandResult
import com.root.app.learning.LearningCommand
import com.root.app.learning.LessonRunState
import com.root.app.learning.LessonRunner
import com.root.app.learning.RejectionReason
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Owns one durable lesson run, driven entirely through the shared, frozen
 * [LessonRunner]/[com.root.app.learning.LearningModels] contracts — this class
 * holds no lesson evidence/DTOs of its own beyond [runId] (saved across
 * process death, matching the exact-revision resume requirement). Whether the
 * current activity has been responded to, and whether that response was
 * correct, is read directly from the runner's own durable
 * [LessonRunState.currentActivityFeedback] — never reimplemented/re-evaluated
 * client-side, and never lost on process death/resume since it lives in the
 * runner's own state, not a session-only echo.
 */
class LessonViewModel(
    application: Application,
    private val saved: SavedStateHandle,
    private val runner: LessonRunner,
    private val audioResolver: LessonAudioResolver,
) : AndroidViewModel(application) {
    private var runId: String?
        get() = saved["teach.runId"]
        set(value) { saved["teach.runId"] = value }
    private var packId: String? = null
    private var packVersion: Int = 0
    private var lessonId: String? = null

    var lesson by mutableStateOf<Lesson?>(null)
        private set
    var run by mutableStateOf<LessonRunState?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The runner's own durable feedback for the *current* activity (response +
     *  correctness, if machine-checkable), or null if unanswered — sourced
     *  directly from [LessonRunState.currentActivityFeedback], never
     *  reimplemented client-side and never lost on process death. */
    val currentFeedback: ActivityFeedback?
        get() = run?.currentActivityFeedback

    val currentActivity: Activity?
        get() = lesson?.activities?.getOrNull(run?.currentActivityIndex ?: -1)

    fun audioSource(assetId: String?): LessonAudioSource = audioResolver.resolve(assetId)

    fun start(packId: String, packVersion: Int, lessonId: String, lesson: Lesson) = viewModelScope.launch {
        this@LessonViewModel.packId = packId
        this@LessonViewModel.packVersion = packVersion
        this@LessonViewModel.lessonId = lessonId
        this@LessonViewModel.lesson = lesson
        try {
            // BeginOrResume resumes the exact open run for this lesson if one
            // already exists (the runner checks for an open run before ever
            // creating a new one) — safe/idempotent to issue every time this
            // screen opens, so process death/reopen always lands on the exact
            // revision/step/assistance/status previously reached.
            applyResult(runner.execute(LearningCommand.BeginOrResume(newCommandId(), packId, packVersion, lessonId)))
        } catch (e: Exception) {
            error = "Couldn't open this lesson. Please try again."
        }
    }

    fun restart() = viewModelScope.launch {
        val p = packId ?: return@launch
        val l = lessonId ?: return@launch
        try {
            applyResult(runner.execute(LearningCommand.Restart(newCommandId(), p, packVersion, l)))
        } catch (e: Exception) {
            error = "Couldn't restart this lesson. Please try again."
        }
    }

    /** Called when the learner navigates away (back press, process
     *  backgrounding) — never called as part of normal completion. */
    fun pause() = viewModelScope.launch {
        val id = runId ?: return@launch
        try { applyResult(runner.execute(LearningCommand.Pause(newCommandId(), id))) } catch (_: Exception) { /* best-effort */ }
    }

    /** Acknowledging a required Listening activity whose audio is unavailable —
     *  the runner records this as EXPOSURE evidence (matching Reflection/
     *  DialogueTurn), letting the lesson honestly complete without ever
     *  claiming comprehension was checked. An actual comprehension answer
     *  attempt for this step is still separately rejected as
     *  AUDIO_UNAVAILABLE by the runner; this only submits the plain
     *  acknowledgement. */
    fun acknowledgeUnavailableAudio(activityId: String) = respond(activityId, ActivityResponse.Acknowledged)

    fun submitChoice(activityId: String, choiceId: String) = respond(activityId, ActivityResponse.Choice(choiceId))

    fun submitTokens(activityId: String, order: List<String>) = respond(activityId, ActivityResponse.OrderedTokens(order))

    /** Optional self-assessed speaking practice — never independently checked,
     *  never blocks completion, and never automatically rates recall; [practiced]
     *  only records whether the learner chose to mark the prompt as practiced. */
    fun submitSelfAssessment(activityId: String, practiced: Boolean) =
        respond(activityId, ActivityResponse.SelfReport(practiced))

    /** Commits the assistance flag *before* the caller reveals transcript/hint
     *  text: callers must only reveal after this suspend call returns/[run] updates. */
    fun revealSupport(activityId: String) = viewModelScope.launch {
        val id = runId ?: return@launch
        try { applyResult(runner.execute(LearningCommand.RevealSupport(newCommandId(), id, activityId))) }
        catch (e: Exception) { error = "Couldn't reveal that yet. Please try again." }
    }

    private fun respond(activityId: String, response: ActivityResponse) {
        val id = runId ?: return
        viewModelScope.launch {
            try {
                applyResult(runner.execute(LearningCommand.SubmitResponse(newCommandId(), id, activityId, response)))
            } catch (e: Exception) {
                error = "That response wasn't saved. Please try again."
            }
        }
    }

    /** Submits [response] for [activityId] unless it was already responded to
     *  (per the runner's own durable state); returns whether it is now safe to
     *  Advance. */
    private suspend fun ensureResponded(activityId: String, response: ActivityResponse): Boolean {
        if (run?.currentActivityId == activityId && run?.currentActivityFeedback != null) return true
        val id = runId ?: return false
        val result = runner.execute(LearningCommand.SubmitResponse(newCommandId(), id, activityId, response))
        applyResult(result)
        return result !is CommandResult.Rejected
    }

    /**
     * Single UI-facing "Continue" action. Display-only steps (dialogue turn,
     * pattern explanation, reflection) auto-acknowledge; a speaking prompt the
     * learner skipped without self-assessing auto-submits `practiced = false`;
     * a graded step (choice/tokens/listening) without any submitted response
     * yet is rejected with a clear message rather than silently skipped —
     * correctness never gates continuing, only *having answered* does, since
     * that is exactly what the runner itself requires before Advance.
     */
    fun continueStep() = viewModelScope.launch {
        val id = runId ?: return@launch
        val activity = currentActivity ?: return@launch
        try {
            val ok = when (activity) {
                is Activity.DialogueTurn, is Activity.PatternExplanation, is Activity.Reflection ->
                    ensureResponded(activity.id, ActivityResponse.Acknowledged)
                is Activity.SpeakingPrompt ->
                    ensureResponded(activity.id, ActivityResponse.SelfReport(false))
                else -> if (run?.currentActivityId == activity.id && run?.currentActivityFeedback != null) {
                    true
                } else {
                    error = "Please answer this step before continuing."
                    false
                }
            }
            if (!ok) return@launch
            applyResult(runner.execute(LearningCommand.Advance(newCommandId(), id, activity.id)))
        } catch (e: Exception) {
            error = "Couldn't continue the lesson. Please try again."
        }
    }

    private suspend fun applyResult(result: CommandResult) {
        when (result) {
            is CommandResult.Applied -> { run = result.state; runId = result.state.runId }
            is CommandResult.AlreadyApplied -> { run = result.state; runId = result.state.runId }
            is CommandResult.Rejected -> handleRejection(result.reason)
        }
    }

    private suspend fun handleRejection(reason: RejectionReason) {
        // STEP_MISMATCH/ACTIVITY_MISMATCH mean our locally held cursor is stale
        // relative to the durable run (e.g. a command applied out of order) —
        // re-fetch the runner's own current snapshot (a read-only call, no new
        // command) rather than leaving the UI stuck showing an outdated step.
        if (reason == RejectionReason.STEP_MISMATCH || reason == RejectionReason.ACTIVITY_MISMATCH) {
            runId?.let { id -> runner.currentState(id)?.let { run = it } }
        }
        // A runId this VM held is no longer valid — never keep retrying against
        // it; the next start() call issues a fresh BeginOrResume instead.
        if (reason == RejectionReason.RUN_NOT_FOUND) runId = null
        error = when (reason) {
            RejectionReason.AUDIO_UNAVAILABLE ->
                "This lesson needs a real speaker recording that isn't available yet. It can't be marked complete without it."
            RejectionReason.INVALID_RESPONSE, RejectionReason.STEP_MISMATCH -> "Please answer this step before continuing."
            RejectionReason.RUN_ENDED, RejectionReason.RUN_NOT_FOUND -> "This lesson run has ended. Please reopen the lesson."
            RejectionReason.COMMAND_CONFLICT,
            RejectionReason.LESSON_NOT_FOUND, RejectionReason.ACTIVITY_MISMATCH -> "Please try that again."
        }
    }

    fun clearError() { error = null }

    private fun newCommandId() = UUID.randomUUID().toString()

    companion object {
        /** Explicit factory: [LessonViewModel] takes constructor parameters
         *  beyond the platform's default `(Application, SavedStateHandle)`
         *  shape, which the default `SavedStateViewModelFactory` cannot
         *  instantiate reflectively. Must be passed to
         *  `viewModel(factory = ...)` at every call site. [assetAvailable] must
         *  be the same predicate [ContentViewModel.assetAvailable] applies, so
         *  the runner's own AUDIO_UNAVAILABLE gating agrees with the resolver
         *  used for playback. */
        fun factory(
            audioResolver: LessonAudioResolver,
            assetAvailable: suspend (String, Int, String) -> Boolean = { _, _, _ -> true },
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                val runner = LessonRunner(AppDatabase.get(application), assetAvailable = assetAvailable)
                LessonViewModel(application, createSavedStateHandle(), runner, audioResolver)
            }
        }
    }
}
