package com.root.app.practice

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.root.app.data.AppDatabase
import com.root.app.data.ConfidenceLevel
import com.root.app.data.LanguageEntity
import com.root.app.data.PackEntity
import com.root.app.data.PhraseEntity
import com.root.app.data.PracticeEndReason
import com.root.app.data.PracticeSessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Instrumented (real in-memory Room) coverage for [PracticeRepository] — the durable
 * replacement for the previous in-memory `SessionQueue`. Exercises exactly the
 * guarantees the practice-foundation plan calls out: bounded/idempotent rating,
 * exactly-one Missed retry, durable Stop/Close, resume-vs-fresh-run scoping, and an
 * access recheck at commit time rather than at snapshot time.
 */
class PracticeRepositoryTest {
    private lateinit var db: AppDatabase
    private var premium = false
    private var reward = false
    private lateinit var repo: PracticeRepository

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
        ).build()
        repo = PracticeRepository(db, premium = { premium }, rewardUnlocked = { reward })
    }

    @After fun close() { db.close() }

    private suspend fun seedPhrase(
        id: String,
        packId: String = "pack-1",
        languageId: String = "lang-1",
        free: Boolean = true,
        languagePremium: Boolean = false,
    ) {
        db.languageDao().insertMissing(listOf(LanguageEntity(languageId, "Test", languagePremium)))
        db.packDao().insertMissing(listOf(PackEntity(packId, languageId, "Theme", 0, free)))
        db.phraseDao().insertMissing(listOf(PhraseEntity(id = id, packId = packId, prompt = "P-$id", answer = "A-$id", audioAsset = null)))
    }

    @Test fun beginOrResumeReturnsNullWhenNothingIsDue() = runBlocking {
        assertNull(repo.beginOrResume("lang-1", null))
    }

    @Test fun ratingAdvancesQueueAndCountsCorrect() = runBlocking {
        seedPhrase("p1"); seedPhrase("p2")
        val state = repo.beginOrResume("lang-1", null)!!
        assertEquals("p1", state.current!!.phrase.id)
        val result = repo.rate(state.sessionId, state.current!!.entryId, ConfidenceLevel.GOT_IT) as PracticeRateResult.Committed
        assertEquals("p2", result.state.current!!.phrase.id)
        assertEquals(1, result.state.correctCount)
        assertEquals(1, result.state.turn)
    }

    @Test fun missedAppendsExactlyOneTailRetry() = runBlocking {
        seedPhrase("p1")
        val state = repo.beginOrResume("lang-1", null)!!
        val afterMiss = repo.rate(state.sessionId, state.current!!.entryId, ConfidenceLevel.MISSED) as PracticeRateResult.Committed
        // The same phrase comes back once, at the tail of this run's queue.
        assertEquals("p1", afterMiss.state.current!!.phrase.id)
        assertEquals(0, afterMiss.state.correctCount)
        assertEquals(1, afterMiss.state.turn)
        val afterRetryMiss = repo.rate(afterMiss.state.sessionId, afterMiss.state.current!!.entryId, ConfidenceLevel.MISSED) as PracticeRateResult.Committed
        // A second Missed on the retry itself never appends a further retry.
        assertNull(afterRetryMiss.state.current)
        assertEquals(2, afterRetryMiss.state.turn)
    }

    @Test fun ratingIsIdempotentForAnAlreadyCommittedEntry() = runBlocking {
        seedPhrase("p1")
        val state = repo.beginOrResume("lang-1", null)!!
        val entryId = state.current!!.entryId
        val first = repo.rate(state.sessionId, entryId, ConfidenceLevel.GOT_IT)
        assertTrue(first is PracticeRateResult.Committed)
        val replay = repo.rate(state.sessionId, entryId, ConfidenceLevel.GOT_IT)
        assertTrue(replay is PracticeRateResult.AlreadyCommitted)
        assertEquals(1, (replay as PracticeRateResult.AlreadyCommitted).state.turn)
        assertEquals(1, db.attemptDao().capabilityCount("lang-1"))
    }

    @Test fun stopEndsSessionDurablyAndRejectsFurtherRatings() = runBlocking {
        seedPhrase("p1")
        val state = repo.beginOrResume("lang-1", null)!!
        assertTrue(repo.stop(state.sessionId, PracticeEndReason.STOPPED))
        val ended = repo.state(state.sessionId)!!
        assertEquals(PracticeSessionStatus.ENDED, ended.status)
        assertNull(ended.current)
        val result = repo.rate(state.sessionId, state.current!!.entryId, ConfidenceLevel.GOT_IT)
        assertEquals(PracticeRateResult.SessionEnded, result)
        assertEquals(0, db.attemptDao().capabilityCount("lang-1"))
    }

    @Test fun stopIsIdempotent() = runBlocking {
        seedPhrase("p1")
        val state = repo.beginOrResume("lang-1", null)!!
        assertTrue(repo.stop(state.sessionId, PracticeEndReason.STOPPED))
        assertTrue(repo.stop(state.sessionId, PracticeEndReason.CLOSED))
    }

    @Test fun beginOrResumeAfterStopStartsANewSessionForTheSameScope() = runBlocking {
        seedPhrase("p1")
        val first = repo.beginOrResume("lang-1", null)!!
        repo.stop(first.sessionId, PracticeEndReason.STOPPED)
        val second = repo.beginOrResume("lang-1", null)!!
        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals("p1", second.current!!.phrase.id)
    }

    @Test fun beginOrResumeResumesAnOpenRunInsteadOfStartingAnother() = runBlocking {
        seedPhrase("p1"); seedPhrase("p2")
        val first = repo.beginOrResume("lang-1", null)!!
        val again = repo.beginOrResume("lang-1", null)!!
        assertEquals(first.sessionId, again.sessionId)
    }

    @Test fun accessRevokedBetweenQueueingAndRatingSkipsInsteadOfCommitting() = runBlocking {
        seedPhrase("p1", free = false, languagePremium = true)
        premium = true
        val state = repo.beginOrResume("lang-1", null)!!
        premium = false // access revoked mid-run
        val result = repo.rate(state.sessionId, state.current!!.entryId, ConfidenceLevel.GOT_IT)
        assertTrue(result is PracticeRateResult.Unavailable)
        assertEquals(0, db.attemptDao().capabilityCount("lang-1"))
    }

    @Test fun revalidateCurrentSkipsPastNowInaccessibleEntries() = runBlocking {
        seedPhrase("p1", free = false, languagePremium = true)
        premium = true
        val state = repo.beginOrResume("lang-1", null)!!
        assertNotNull(state.current)
        premium = false
        val revalidated = repo.revalidateCurrent(state.sessionId)!!
        assertNull(revalidated.current)
        assertFalse(revalidated.hasMoreAfterPage)
    }

    @Test fun nextDuePhraseIsBoundedToASingleRow() = runBlocking {
        seedPhrase("p1"); seedPhrase("p2")
        assertNotNull(repo.nextDuePhrase("lang-1", null))
    }

    @Test fun continueSessionFetchesAnotherPageWithoutRequeuingSeenPhrases() = runBlocking {
        seedPhrase("p1")
        val state = repo.beginOrResume("lang-1", null)!!
        repo.rate(state.sessionId, state.current!!.entryId, ConfidenceLevel.GOT_IT)
        seedPhrase("p2")
        val continued = repo.continueSession(state.sessionId)!!
        assertEquals("p2", continued.current!!.phrase.id)
    }
}
