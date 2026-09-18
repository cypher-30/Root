package com.root.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises [SessionQueue]'s bounded-session rules in isolation from Room/UI:
 *  distinct-ID de-duplication, the initial phrase cap, at most one Missed retry per
 *  phrase, and that only distinct Got it outcomes count toward [SessionQueue.correctCount]. */
class SessionQueueTest {
    private fun phrase(id: String) = PhraseEntity(
        id = id,
        packId = "pack",
        prompt = "Prompt $id",
        answer = "Answer $id",
        audioAsset = null,
        updatedAt = 0,
    )

    @Test
    fun emptySessionIsAlreadyComplete() {
        val queue = SessionQueue(emptyList())
        assertTrue(queue.isComplete)
        assertNull(queue.current)
        assertEquals(0, queue.correctCount)
        assertEquals(0, queue.ratedCount)
        assertEquals(0, queue.reviewedCount)
    }

    @Test
    fun initialSessionHasAtMostEightUniquePhrases() {
        val input = listOf(phrase("0"), phrase("0")) + (1..12).map { phrase("$it") }
        val queue = SessionQueue(input)
        assertEquals((0..7).map { "$it" }, queue.initialPhrases.map { it.id })
        repeat(8) { queue.rate(ConfidenceLevel.GOT_IT) }
        assertTrue(queue.isComplete)
        assertEquals(8, queue.correctCount)
        assertEquals(8, queue.ratedCount)
    }

    @Test
    fun duplicateIdsKeepFirstContentAndCannotGrowRootsTwice() {
        val original = phrase("a")
        val queue = SessionQueue(listOf(original, original.copy(answer = "Changed")))
        assertEquals(original, queue.current)
        queue.rate(ConfidenceLevel.GOT_IT)
        assertTrue(queue.isComplete)
        assertEquals(1, queue.correctCount)
    }

    @Test
    fun missedPhraseReturnsAtTheTailOnlyOnce() {
        val queue = SessionQueue(listOf(phrase("a"), phrase("b")))
        queue.rate(ConfidenceLevel.MISSED)
        assertEquals("b", queue.current?.id)
        queue.rate(ConfidenceLevel.CLOSE)
        assertEquals("a", queue.current?.id)
        queue.rate(ConfidenceLevel.MISSED)
        assertTrue(queue.isComplete)
        assertEquals(3, queue.ratedCount)
        assertEquals(2, queue.reviewedCount)
        assertEquals(0, queue.correctCount)
    }

    @Test
    fun missingEveryPhraseStillClosesAfterSixteenRatings() {
        val queue = SessionQueue((1..20).map { phrase("$it") })
        repeat(16) {
            assertFalse(queue.isComplete)
            queue.rate(ConfidenceLevel.MISSED)
        }
        assertTrue(queue.isComplete)
        assertEquals(16, queue.ratedCount)
        assertEquals(8, queue.reviewedCount)
        assertEquals(0, queue.correctCount)
    }

    @Test
    fun gotItAfterRetryCountsOneUniqueSuccessfulRecall() {
        val queue = SessionQueue(listOf(phrase("a"), phrase("b"), phrase("c")))
        queue.rate(ConfidenceLevel.MISSED)
        queue.rate(ConfidenceLevel.CLOSE)
        queue.rate(ConfidenceLevel.GOT_IT)
        assertEquals(1, queue.correctCount)
        queue.rate(ConfidenceLevel.GOT_IT)
        assertTrue(queue.isComplete)
        assertEquals(2, queue.correctCount)
        assertEquals(4, queue.ratedCount)
        assertEquals(3, queue.reviewedCount)
    }

    @Test
    fun retryCanBeDisabled() {
        val queue = SessionQueue(listOf(phrase("a")), retryLimit = 0)
        queue.rate(ConfidenceLevel.MISSED)
        assertTrue(queue.isComplete)
        assertEquals(1, queue.ratedCount)
    }

    @Test
    fun initialLimitCanBeSmallerOrZero() {
        val phrases = (1..3).map { phrase("$it") }
        assertEquals(2, SessionQueue(phrases, initialLimit = 2).initialPhrases.size)
        assertTrue(SessionQueue(phrases, initialLimit = 0).isComplete)
    }

    @Test
    fun invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SessionQueue(emptyList(), retryLimit = -1) }
        assertThrows(IllegalArgumentException::class.java) { SessionQueue(emptyList(), retryLimit = 2) }
        assertThrows(IllegalArgumentException::class.java) { SessionQueue(emptyList(), initialLimit = -1) }
    }

    @Test
    fun completedSessionRejectsMoreRatingsWithoutChangingCounts() {
        val queue = SessionQueue(listOf(phrase("a")))
        queue.rate(ConfidenceLevel.GOT_IT)
        assertThrows(IllegalStateException::class.java) { queue.rate(ConfidenceLevel.MISSED) }
        assertEquals(1, queue.correctCount)
        assertEquals(1, queue.ratedCount)
        assertTrue(queue.remainingPhrases.isEmpty())
    }

    @Test
    fun replayRestoresCurrentCardCountsAndRetryBudget() {
        val original = SessionQueue(listOf(phrase("a"), phrase("b"), phrase("c")))
        original.rate(ConfidenceLevel.MISSED)
        original.rate(ConfidenceLevel.GOT_IT)
        val restored = SessionQueue(original.initialPhrases)
        original.ratingHistory.forEach(restored::rate)
        assertEquals(original.current, restored.current)
        assertEquals(original.remainingPhrases, restored.remainingPhrases)
        assertEquals(original.correctCount, restored.correctCount)
        assertEquals(original.ratedCount, restored.ratedCount)
        assertEquals(original.reviewedCount, restored.reviewedCount)
        restored.rate(ConfidenceLevel.CLOSE)
        restored.rate(ConfidenceLevel.MISSED)
        assertTrue(restored.isComplete)
    }

    @Test
    fun queueOwnsItsInputAndExportsIndependentSnapshots() {
        val input = mutableListOf(phrase("a"), phrase("b"))
        val queue = SessionQueue(input)
        val remaining = queue.remainingPhrases
        val history = queue.ratingHistory
        input.clear()
        queue.rate(ConfidenceLevel.GOT_IT)
        assertEquals(listOf("a", "b"), remaining.map { it.id })
        assertTrue(history.isEmpty())
        assertEquals("b", queue.current?.id)
    }
}
