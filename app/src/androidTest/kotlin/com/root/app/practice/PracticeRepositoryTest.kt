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
        seedPhrase("p1"); seedPhrase("p2")
        val state = repo.beginOrResume("lang-1", null)!!
        repo.rate(state.sessionId, state.current!!.entryId, ConfidenceLevel.GOT_IT)
        // p2 already existed before the run started, so continuing the same run
        // still surfaces it — only content created/edited *after* startedAt is held
        // back (see the frozen-membership tests below).
        val continued = repo.continueSession(state.sessionId)!!
        assertEquals("p2", continued.current!!.phrase.id)
    }

    @Test fun newlyAddedPhraseDoesNotJoinAnAlreadyOpenRun() = runBlocking {
        seedPhrase("p1")
        val state = repo.beginOrResume("lang-1", null)!!
        repo.rate(state.sessionId, state.current!!.entryId, ConfidenceLevel.GOT_IT)
        // Added strictly after the run's own startedAt cutoff.
        seedPhrase("p2")
        val continued = repo.continueSession(state.sessionId)!!
        assertNull(continued.current)
        assertFalse(repo.hasMoreDue(state.sessionId))
        // ...but it is eligible once this run ends and a fresh one begins.
        repo.stop(state.sessionId, PracticeEndReason.CLOSED)
        val second = repo.beginOrResume("lang-1", null)!!
        assertNotEquals(state.sessionId, second.sessionId)
        assertEquals("p2", second.current!!.phrase.id)
    }

    @Test fun editedTextAfterAFrozenCutoffIsExcludedFromASubsequentPageFetchAtThatCutoff() = runBlocking {
        // A direct DAO-level check of the cutoff itself: seedPhrase("p1") stands in
        // for another already-queued phrase filling page one, so this asserts what
        // fillPage/continueSession rely on rather than fighting PAGE_SIZE=200 to
        // force p2 out of a first page through the full repository flow.
        seedPhrase("p1")
        db.languageDao().insertMissing(listOf(LanguageEntity("lang-1", "Test", false)))
        db.packDao().insertMissing(listOf(PackEntity("pack-1", "lang-1", "Theme", 0, true)))
        db.phraseDao().insertMissing(listOf(PhraseEntity(id = "p2", packId = "pack-1", prompt = "old", answer = "old", audioAsset = null, updatedAt = 1)))
        val cutoff = 500L // a frozen run-start instant strictly before p2's edit below.
        val beforeEdit = db.attemptDao().dueForLanguagePageExcludingSession(
            sessionId = "no-such-session", languageId = "lang-1", nowMillis = cutoff,
            unlockedPackIds = listOf("pack-1"), packId = null, limit = 200,
        )
        assertTrue(beforeEdit.any { it.id == "p2" })
        // p2 gets edited (a bumped updated_at) after the frozen cutoff instant.
        db.phraseDao().upsertAll(listOf(PhraseEntity(id = "p2", packId = "pack-1", prompt = "new", answer = "new", audioAsset = null, updatedAt = cutoff + 1)))
        val afterEdit = db.attemptDao().dueForLanguagePageExcludingSession(
            sessionId = "no-such-session", languageId = "lang-1", nowMillis = cutoff,
            unlockedPackIds = listOf("pack-1"), packId = null, limit = 200,
        )
        assertFalse(afterEdit.any { it.id == "p2" })
    }

    @Test fun ratingWithADifferentOutcomeThanRecordedIsConflictingNotCommitted() = runBlocking {
        seedPhrase("p1")
        val state = repo.beginOrResume("lang-1", null)!!
        val entryId = state.current!!.entryId
        repo.rate(state.sessionId, entryId, ConfidenceLevel.GOT_IT)
        val conflict = repo.rate(state.sessionId, entryId, ConfidenceLevel.MISSED)
        assertTrue(conflict is PracticeRateResult.Conflicting)
        // The original recorded outcome is untouched.
        assertEquals(1, db.attemptDao().capabilityCount("lang-1"))
    }

    @Test fun ratingAnAlreadySkippedEntryIsStaleSkippedNotAlreadyCommitted() = runBlocking {
        seedPhrase("p1", free = false, languagePremium = true)
        premium = true
        val state = repo.beginOrResume("lang-1", null)!!
        premium = false
        val skipped = repo.rate(state.sessionId, state.current!!.entryId, ConfidenceLevel.GOT_IT)
        assertTrue(skipped is PracticeRateResult.Unavailable)
        val replay = repo.rate(state.sessionId, state.current!!.entryId, ConfidenceLevel.GOT_IT)
        assertTrue(replay is PracticeRateResult.StaleSkipped)
    }

    @Test fun sessionStateReportsDistinctSkippedCountAndEndReason() = runBlocking {
        seedPhrase("p1", packId = "pack-locked", free = false)
        seedPhrase("p2", packId = "pack-free", free = true)
        premium = true
        val state = repo.beginOrResume("lang-1", null)!!
        premium = false // p1 (pack-locked) becomes inaccessible; p2 (pack-free) remains accessible.
        val afterSkip = repo.revalidateCurrent(state.sessionId)!!
        assertEquals(1, afterSkip.skippedCount)
        assertNull(afterSkip.endReason)
        val afterRate = repo.rate(state.sessionId, afterSkip.current!!.entryId, ConfidenceLevel.GOT_IT) as PracticeRateResult.Committed
        assertEquals(1, afterRate.state.skippedCount)
        assertEquals(1, afterRate.state.correctCount)
        repo.stop(state.sessionId, PracticeEndReason.CLOSED)
        val ended = repo.state(state.sessionId)!!
        assertEquals(PracticeEndReason.CLOSED, ended.endReason)
    }

    @Test fun reviewedDetailsPagesOnlyNonPendingEntriesOldestFirst() = runBlocking {
        seedPhrase("p1"); seedPhrase("p2"); seedPhrase("p3")
        val state = repo.beginOrResume("lang-1", null)!!
        val first = repo.rate(state.sessionId, state.current!!.entryId, ConfidenceLevel.GOT_IT) as PracticeRateResult.Committed
        repo.rate(first.state.sessionId, first.state.current!!.entryId, ConfidenceLevel.MISSED)
        val page = repo.reviewedDetails(state.sessionId, limit = 1, offset = 0)
        assertEquals(1, page.size)
        assertEquals("p1", page.first().phraseId)
        assertEquals(ConfidenceLevel.GOT_IT, page.first().confidence)
        val secondPage = repo.reviewedDetails(state.sessionId, limit = 1, offset = 1)
        assertEquals("p2", secondPage.first().phraseId)
        assertEquals(ConfidenceLevel.MISSED, secondPage.first().confidence)
    }

    @Test fun earliestEligibleDueAtReflectsTheSchedulersOwnNextDueColumn() = runBlocking {
        seedPhrase("p1")
        assertNull(repo.earliestEligibleDueAt("lang-1", null)) // never attempted: not "scheduled ahead".
        val state = repo.beginOrResume("lang-1", null)!!
        repo.rate(state.sessionId, state.current!!.entryId, ConfidenceLevel.GOT_IT)
        val earliest = repo.earliestEligibleDueAt("lang-1", null)
        assertNotNull(earliest)
        assertTrue(earliest!! > System.currentTimeMillis())
    }
}
