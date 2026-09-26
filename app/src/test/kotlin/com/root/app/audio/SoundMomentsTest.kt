package com.root.app.audio

import com.root.app.data.ConfidenceLevel
import com.root.app.data.PracticeSessionStatus
import com.root.app.learning.ActivityFeedback
import com.root.app.learning.ActivityResponse
import com.root.app.learning.CommandResult
import com.root.app.learning.LessonRunState
import com.root.app.learning.RejectionReason
import com.root.app.practice.PracticeRateResult
import com.root.app.practice.PracticeSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundMomentsTest {
    private val session = PracticeSessionState(
        sessionId = "s", languageId = "shona", packId = null, status = PracticeSessionStatus.ACTIVE,
        current = null, correctCount = 1, turn = 1, skippedCount = 0, endReason = null, hasMoreAfterPage = false,
    )

    private fun run(
        activityId: String? = "a1",
        feedback: ActivityFeedback? = null,
        completed: Boolean = false,
    ) = LessonRunState(
        runId = "r", lessonId = "l", packId = "p", packVersion = 1, lessonRevision = 1,
        status = if (completed) "COMPLETED" else "ACTIVE", currentActivityId = activityId,
        currentActivityIndex = 0, totalActivities = 2, requiredCompletedCount = 0, requiredTotalCount = 2,
        assistanceUsedActivityIds = emptyList(), currentActivityFeedback = feedback, completed = completed,
    )

    private fun feedback(correct: Boolean?, kind: String = "CHECKED") =
        ActivityFeedback(ActivityResponse.Choice("c"), correct, kind)

    @Test fun `only a fresh Got it commit acknowledges recall`() {
        assertEquals(RootSoundCue.RECALL_ACKNOWLEDGED,
            SoundMoments.recall(PracticeRateResult.Committed(session), ConfidenceLevel.GOT_IT))
        assertNull(SoundMoments.recall(PracticeRateResult.Committed(session), ConfidenceLevel.CLOSE))
        assertNull(SoundMoments.recall(PracticeRateResult.Committed(session), ConfidenceLevel.MISSED))
        listOf(
            PracticeRateResult.AlreadyCommitted(session),
            PracticeRateResult.Conflicting(session),
            PracticeRateResult.Unavailable(session),
            PracticeRateResult.StaleSkipped(session),
            PracticeRateResult.SessionEnded,
        ).forEach { assertNull(it.toString(), SoundMoments.recall(it, ConfidenceLevel.GOT_IT)) }
    }

    @Test fun `only a fresh evaluated correct answer for this activity sounds`() {
        assertEquals(RootSoundCue.ANSWER_CORRECT,
            SoundMoments.answer(CommandResult.Applied(run(feedback = feedback(true))), "a1"))
        assertEquals("assisted but correct still acknowledges the answer", RootSoundCue.ANSWER_CORRECT,
            SoundMoments.answer(CommandResult.Applied(run(feedback = feedback(true, "ASSISTED"))), "a1"))
        assertNull(SoundMoments.answer(CommandResult.Applied(run(feedback = feedback(false))), "a1"))
        assertNull("self-report has no correctness",
            SoundMoments.answer(CommandResult.Applied(run(feedback = feedback(null, "SELF_REPORT"))), "a1"))
        assertNull("a replay is silent",
            SoundMoments.answer(CommandResult.AlreadyApplied(run(feedback = feedback(true))), "a1"))
        assertNull("a different current activity is stale",
            SoundMoments.answer(CommandResult.Applied(run(activityId = "a2", feedback = feedback(true))), "a1"))
        assertNull(SoundMoments.answer(CommandResult.Rejected(RejectionReason.INVALID_RESPONSE, "x"), "a1"))
    }

    @Test fun `only the advance that completes an unfinished run settles the lesson`() {
        val done = run(activityId = null, completed = true)
        assertEquals(RootSoundCue.LESSON_SETTLED, SoundMoments.lessonCompleted(CommandResult.Applied(done), wasCompleted = false))
        assertNull("already completed before", SoundMoments.lessonCompleted(CommandResult.Applied(done), wasCompleted = true))
        assertNull("replayed", SoundMoments.lessonCompleted(CommandResult.AlreadyApplied(done), wasCompleted = false))
        assertNull("a mid-lesson advance", SoundMoments.lessonCompleted(CommandResult.Applied(run()), wasCompleted = false))
        assertNull(SoundMoments.lessonCompleted(CommandResult.Rejected(RejectionReason.RUN_ENDED, "x"), wasCompleted = false))
    }
}
