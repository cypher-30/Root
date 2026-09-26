package com.root.app.audio

import com.root.app.data.ConfidenceLevel
import com.root.app.learning.CommandResult
import com.root.app.practice.PracticeRateResult

/**
 * Which durable outcomes may produce an automatic cue. Only fresh commits
 * count: replayed (AlreadyCommitted/AlreadyApplied), rejected, stale, or
 * restored results are silent. Callers still deduplicate with [SoundEventGate].
 */
object SoundMoments {
    /** A freshly saved Got it self-rating: an acknowledgement, not a grade. */
    fun recall(result: PracticeRateResult, level: ConfidenceLevel): RootSoundCue? =
        RootSoundCue.RECALL_ACKNOWLEDGED.takeIf { result is PracticeRateResult.Committed && level == ConfidenceLevel.GOT_IT }

    /** A freshly applied response the runner itself evaluated as correct.
     *  Self-reports and exposure steps carry no correctness and stay silent. */
    fun answer(result: CommandResult, activityId: String): RootSoundCue? {
        val state = (result as? CommandResult.Applied)?.state ?: return null
        val correct = state.currentActivityId == activityId && state.currentActivityFeedback?.correct == true
        return RootSoundCue.ANSWER_CORRECT.takeIf { correct }
    }

    /** A fresh Advance that moved an unfinished run to completed. */
    fun lessonCompleted(result: CommandResult, wasCompleted: Boolean): RootSoundCue? {
        val state = (result as? CommandResult.Applied)?.state ?: return null
        return RootSoundCue.LESSON_SETTLED.takeIf { !wasCompleted && state.completed }
    }
}
