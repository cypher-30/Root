package com.root.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Locks in [Scheduler]'s fixed intervals (4h / 1d / 4d) and its determinism:
 *  the same (outcome, now) pair must always produce the same due time. */
class SchedulerTest {
    private val now = 1_750_000_000_000L

    @Test
    fun missedIsDueInFourHours() {
        assertEquals(now + TimeUnit.HOURS.toMillis(4), Scheduler.nextDueAt(ConfidenceLevel.MISSED, now))
    }

    @Test
    fun closeIsDueInOneDay() {
        assertEquals(now + TimeUnit.DAYS.toMillis(1), Scheduler.nextDueAt(ConfidenceLevel.CLOSE, now))
    }

    @Test
    fun gotItIsDueInFourDays() {
        assertEquals(now + TimeUnit.DAYS.toMillis(4), Scheduler.nextDueAt(ConfidenceLevel.GOT_IT, now))
    }

    @Test
    fun everyOutcomeHasADeterministicFutureDueTime() {
        ConfidenceLevel.entries.forEach {
            assertTrue(Scheduler.nextDueAt(it, now) > now)
            assertEquals(Scheduler.nextDueAt(it, now), Scheduler.nextDueAt(it, now))
        }
    }
}
