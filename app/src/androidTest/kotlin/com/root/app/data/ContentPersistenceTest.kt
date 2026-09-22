package com.root.app.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Instrumented persistence tests for the pack-install/managed-phrase tables:
 *  install-job idempotency, installed-ready state surviving a failed update,
 *  and managed-phrase retirement excluding a phrase while legacy phrases stay
 *  eligible. */
class ContentPersistenceTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After fun tearDown() { db.close() }

    @Test fun installJobRequestIdIsUnique() = runBlocking {
        val job = PackInstallJobEntity(requestId = "req-1", packId = "pack-1", targetVersion = 1, status = PackInstallJobStatus.PENDING)
        db.contentDao().insertInstallJob(job)
        val fetched = db.contentDao().getInstallJobByRequestId("req-1")
        assertNotNull(fetched)

        var threw = false
        try {
            db.contentDao().insertInstallJob(
                PackInstallJobEntity(requestId = "req-1", packId = "pack-1", targetVersion = 2, status = PackInstallJobStatus.PENDING),
            )
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("inserting a duplicate request_id must fail, not silently succeed", threw)
    }

    @Test fun oldRevisionSurvivesAFailedUpdateJob() = runBlocking {
        db.contentDao().insertPackVersion(
            PackVersionEntity(
                packId = "pack-1", version = 1, schemaVersion = 1, minReaderVersion = 1,
                languageId = "lang-1", languageCode = "sn", languageName = "Shona", title = "Greetings",
                publication = "development", manifestJson = "{}", manifestSha256 = "a".repeat(64),
                phraseCount = 0, lessonCount = 1, assetCount = 0,
            ),
        )
        db.contentDao().insertInstalledPack(InstalledPackEntity(packId = "pack-1", currentVersion = 1, status = InstalledPackStatus.READY))

        val job = PackInstallJobEntity(requestId = "req-update", packId = "pack-1", targetVersion = 2, status = PackInstallJobStatus.DOWNLOADING)
        db.contentDao().insertInstallJob(job)
        db.contentDao().updateInstallJob(job.id, PackInstallJobStatus.FAILED, "network error", System.currentTimeMillis())

        // installed_packs still points at version 1 — a failed job never rewrites it.
        val installed = db.contentDao().getInstalledPack("pack-1")!!
        assertEquals(1, installed.currentVersion)
        assertEquals(InstalledPackStatus.READY, installed.status)
        assertEquals(PackInstallJobStatus.FAILED, db.contentDao().getInstallJobByRequestId("req-update")!!.status)
    }

    @Test fun retiredManagedPhraseIsIneligibleWhileLegacyPhraseStaysEligible() = runBlocking {
        db.languageDao().insertMissing(listOf(LanguageEntity("lang-1", "Test", false)))
        db.packDao().insertMissing(listOf(PackEntity("pack-1", "lang-1", "Theme", 0, true)))
        db.phraseDao().insertMissing(
            listOf(
                PhraseEntity(id = "managed-1", packId = "pack-1", prompt = "P1", answer = "A1", audioAsset = null),
                PhraseEntity(id = "legacy-1", packId = "pack-1", prompt = "P2", answer = "A2", audioAsset = null),
            ),
        )
        db.contentDao().insertManagedPhrase(
            ManagedPhraseEntity(phraseId = "managed-1", packId = "pack-shona", packVersion = 1, sourcePhraseId = "phrase-1", retired = false),
        )

        assertTrue(ManagedContentAccess.isEligible(db.contentDao().getManagedPhrase("managed-1")))
        assertTrue(ManagedContentAccess.isEligible(db.contentDao().getManagedPhrase("legacy-1"))) // no row -> legacy, eligible
        assertNull(db.contentDao().getManagedPhrase("legacy-1"))

        db.contentDao().setManagedPhraseRetired("managed-1", true, System.currentTimeMillis())
        assertTrue(!ManagedContentAccess.isEligible(db.contentDao().getManagedPhrase("managed-1")))
        // Retiring never deletes the phrase row or its identity.
        assertNotNull(db.phraseDao().getById("managed-1"))
    }
}
