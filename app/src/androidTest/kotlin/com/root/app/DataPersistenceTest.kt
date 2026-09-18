package com.root.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.root.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Instrumented (real Room, in-memory DB) checks for seeding/persistence guarantees
 *  that unit tests can't exercise: non-destructive re-seeding, legacy-random-ID
 *  history preservation, the free Shona starter's language-scoped access, reused
 *  learner-created languages, and deterministic latest-attempt tie-breaking. */
class DataPersistenceTest {
    private lateinit var db: AppDatabase
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
        ).build()
    }
    @After fun close() { db.close() }

    @Test fun seedingTwicePreservesPhrasesAndRecall() = runBlocking {
        SeedData.seedIfEmpty(db)
        val first = db.phraseDao().getForPack("pack-dholuo-greetings")
        db.attemptDao().insert(AttemptEntity(phraseId = first.first().id, confidence = ConfidenceLevel.GOT_IT,
            reviewedAt = 100L, nextDueAt = 500L))
        SeedData.seedIfEmpty(db)
        assertEquals(first.map { it.id }, db.phraseDao().getForPack("pack-dholuo-greetings").map { it.id })
        assertEquals(1, db.attemptDao().capabilityCount("lang-dholuo"))
    }

    @Test fun legacyRandomPhraseAndItsHistoryAreNotReplaced() = runBlocking {
        val language = LanguageEntity("lang-dholuo", "Dholuo", false)
        val pack = PackEntity("pack-dholuo-greetings", language.id, "Greetings", 0, true)
        val phrase = PhraseEntity(packId = pack.id, prompt = "My edited prompt", answer = "My edited word", audioAsset = null)
        db.languageDao().insertMissing(listOf(language))
        db.packDao().insertMissing(listOf(pack))
        db.phraseDao().insertMissing(listOf(phrase))
        db.attemptDao().insert(AttemptEntity(phraseId = phrase.id, confidence = ConfidenceLevel.GOT_IT, nextDueAt = Long.MAX_VALUE))
        SeedData.seedIfEmpty(db)
        assertEquals(listOf(phrase), db.phraseDao().getForPack(pack.id))
        assertEquals(1, db.attemptDao().capabilityCount(language.id))
        assertEquals(8, db.phraseDao().countForPack("pack-shona-greetings"))
    }

    @Test fun shonaStarterIsFreeScopedAndPreservesEditsOnReseeding() = runBlocking {
        SeedData.seedIfEmpty(db)
        val language = db.languageDao().getById("lang-shona")!!
        val pack = db.packDao().getById("pack-shona-greetings")!!
        assertTrue(ContentAccess.canAccess(language, pack, premium = false, rewardUnlocked = false))
        val due = db.attemptDao().dueForLanguage(language.id, 200L,
            listOf(pack.id, "pack-dholuo-greetings"))
        assertEquals(8, due.size)
        assertTrue(due.all { it.packId == pack.id && it.audioAsset == null })
        assertEquals("Mhoro", due.first().answer)
        assertEquals(8, due.map { it.id }.distinct().size)
        val edited = due.first().copy(prompt = "My personal greeting prompt")
        db.phraseDao().upsertAll(listOf(edited))
        db.attemptDao().insert(AttemptEntity(phraseId = edited.id, confidence = ConfidenceLevel.GOT_IT,
            reviewedAt = 100L, nextDueAt = 900L))

        SeedData.seedIfEmpty(db)

        assertEquals(8, db.phraseDao().countForPack(pack.id))
        assertEquals(edited, db.phraseDao().getById(edited.id))
        assertEquals(1, db.attemptDao().capabilityCount(language.id))
        assertEquals(7, db.attemptDao().dueForLanguage(language.id, 200L, listOf(pack.id)).size)
    }

    @Test fun shonaStarterReusesLearnerCreatedLanguage() = runBlocking {
        val language = LanguageEntity(id = "personal-shona", name = "Shona", isPremium = false)
        val personal = PackEntity(ContentAccess.userPackId(language.id), language.id, "Your words", 0, true)
        val phrase = PhraseEntity(packId = personal.id, prompt = "A family word", answer = "My saved word", audioAsset = null)
        db.languageDao().insertMissing(listOf(language))
        db.packDao().insertMissing(listOf(personal))
        db.phraseDao().insertMissing(listOf(phrase))

        SeedData.seedIfEmpty(db)
        SeedData.seedIfEmpty(db)

        assertEquals(1, db.languageDao().getAll().count { it.name == "Shona" })
        assertEquals(language.id, db.packDao().getById("pack-shona-greetings")!!.languageId)
        assertEquals(8, db.phraseDao().countForPack("pack-shona-greetings"))
        assertEquals(phrase, db.phraseDao().getById(phrase.id))
    }

    @Test fun latestTieIsDeterministicAndLockedPacksNeverEnterDueQueue() = runBlocking {
        SeedData.seedIfEmpty(db)
        val pack = "pack-dholuo-greetings"
        val phrase = db.phraseDao().getForPack(pack).first()
        db.attemptDao().insert(AttemptEntity(phraseId = phrase.id, confidence = ConfidenceLevel.MISSED, reviewedAt = 100, nextDueAt = 110))
        db.attemptDao().insert(AttemptEntity(phraseId = phrase.id, confidence = ConfidenceLevel.GOT_IT, reviewedAt = 100, nextDueAt = 900))
        val locked = PhraseEntity(packId = "pack-dholuo-market", prompt = "Locked", answer = "Locked", audioAsset = null)
        db.phraseDao().insertMissing(listOf(locked))
        val due = db.attemptDao().dueForLanguage("lang-dholuo", 200, listOf(pack))
        assertEquals(2, due.size)
        assertFalse(due.any { it.id == phrase.id || it.id == locked.id })
        assertEquals(1, db.attemptDao().capabilityCount("lang-dholuo"))
    }
}
