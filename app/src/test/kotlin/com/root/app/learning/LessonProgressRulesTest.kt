package com.root.app.learning

import com.root.app.data.LessonRunEntity
import com.root.app.data.LessonRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonProgressRulesTest {
    private fun run(status: LessonRunStatus, revision: Int = 1, superseded: String? = null) =
        LessonRunEntity(lessonId = "l", packId = "p", packVersion = 1, lessonRevision = revision, status = status, supersededByRunId = superseded)

    @Test fun noRunsIsNotStarted() {
        assertEquals(LessonProgressSummary(LessonProgress.NOT_STARTED, false), LessonProgressRules.summarize(1, emptyList()))
    }

    @Test fun completionStaysCompletedEvenDuringAReview() {
        val done = LessonProgressRules.summarize(1, listOf(run(LessonRunStatus.COMPLETED)))
        assertEquals(LessonProgress.COMPLETED, done.progress)
        assertFalse(done.hasOpenRun)

        val reviewing = LessonProgressRules.summarize(1, listOf(run(LessonRunStatus.COMPLETED), run(LessonRunStatus.PAUSED)))
        assertEquals(LessonProgress.COMPLETED, reviewing.progress)
        assertTrue(reviewing.hasOpenRun)
    }

    @Test fun openRunWithoutCompletionIsInProgress() {
        assertEquals(LessonProgress.IN_PROGRESS, LessonProgressRules.summarize(1, listOf(run(LessonRunStatus.ACTIVE))).progress)
        assertEquals(
            LessonProgress.NOT_STARTED,
            LessonProgressRules.summarize(1, listOf(run(LessonRunStatus.PAUSED, superseded = "newer"))).progress,
        )
    }

    @Test fun earlierRevisionIsNeverPromoted() {
        val summary = LessonProgressRules.summarize(2, listOf(run(LessonRunStatus.COMPLETED, revision = 1)))
        assertEquals(LessonProgress.COMPLETED_EARLIER_REVISION, summary.progress)
        assertEquals(
            LessonProgress.IN_PROGRESS,
            LessonProgressRules.summarize(2, listOf(run(LessonRunStatus.COMPLETED, 1), run(LessonRunStatus.ACTIVE, 2))).progress,
        )
    }

    @Test fun runsEndedByRemovingTheUnitCountAsNotStarted() {
        assertEquals(LessonProgress.NOT_STARTED, LessonProgressRules.summarize(1, listOf(run(LessonRunStatus.UNAVAILABLE))).progress)
        assertEquals(
            LessonProgress.COMPLETED,
            LessonProgressRules.summarize(1, listOf(run(LessonRunStatus.COMPLETED), run(LessonRunStatus.UNAVAILABLE))).progress,
        )
    }
}
