package com.root.app.content

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.root.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Instrumented coverage for the `delivery` package's finished slice: a
 * corrupt cached catalog is discarded rather than crashing startup, a
 * still-referenced pack's media is not deleted out from under an open
 * practice session at uninstall time (and the deferred deletion is later
 * completed once nothing pins it), [PackFiles.replacePromoted] repairs a
 * corrupt orphaned promoted-version directory, and [ContentLibrary.storageUsage]
 * reports installed/pending-cleanup figures consistent with the DB.
 */
class ContentDeliveryTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var db: AppDatabase
    private lateinit var library: ContentLibrary
    private lateinit var contentRoot: File

    @Before fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        contentRoot = directory.newFolder()
        library = ContentLibrary(context, db, contentRoot)
    }

    @After fun close() = db.close()

    @Test fun corruptCachedCatalogIsDiscardedRatherThanCrashingStartup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = sha256(com.root.app.BuildConfig.CONTENT_CATALOG_URL.toByteArray())
        val catalogFile = File(context.filesDir, "catalog-$identity.json")
        catalogFile.parentFile?.mkdirs()
        catalogFile.writeText("{ this is not valid json at all")

        try {
            // Must not throw — a corrupt local cache is treated as absent.
            library.initialize()
        } finally {
            catalogFile.delete()
        }
    }

    @Test fun uninstallDefersMediaDeletionWhileAnOpenPracticeSessionPinsItThenRetryCompletesIt() = runBlocking {
        library.installBundledDevelopment(bytes(pack(1)))
        val installedDir = File(contentRoot, "versions/fixture-pack/1")
        assertTrue(installedDir.isDirectory)

        // An open (non-ENDED) practice session snapshotting this pack pins its media.
        db.languageDao().insertMissing(listOf(LanguageEntity("fixture-language", "Fixture", false)))
        db.practiceDao().insertSession(
            PracticeSessionEntity(id = "session-1", languageId = "fixture-language", packId = "fixture-pack",
                status = PracticeSessionStatus.ACTIVE, startedAt = System.currentTimeMillis()),
        )
        db.practiceDao().insertEntry(
            PracticeQueueEntryEntity(id = "entry-1", sessionId = "session-1", phraseId = "fixture-word",
                packIdSnapshot = "fixture-pack", promptSnapshot = "Hello", answerSnapshot = "Hello",
                audioSnapshot = null, phraseRevision = 0, position = 0, isRetry = false, originEntryId = null,
                state = QueueEntryState.PENDING),
        )

        library.uninstall("fixture-pack")

        // Deferred: the on-disk media is still there, and the deferral is durably recorded.
        assertTrue("media must survive while an open session still pins it", installedDir.isDirectory)
        val pending = db.mediaFileFactDao().getPendingCleanup().filter { it.subject == MediaFileSubject.PACK_VERSION_MEDIA }
        assertEquals(1, pending.size)
        assertEquals("fixture-pack", pending.single().subjectId)
        assertEquals(InstalledPackStatus.RETIRED, db.contentDao().getInstalledPack("fixture-pack")!!.status)

        // Retrying while still pinned resolves nothing.
        assertEquals(0, library.retryPendingContentCleanup())
        assertTrue(installedDir.isDirectory)

        // Ending the session releases the pin; retry now completes the deferred deletion.
        db.practiceDao().updateSession("session-1", PracticeSessionStatus.ENDED, System.currentTimeMillis(),
            PracticeEndReason.STOPPED, 0, System.currentTimeMillis())
        assertEquals(1, library.retryPendingContentCleanup())
        assertFalse(installedDir.exists())
        assertTrue(db.mediaFileFactDao().getPendingCleanup().none { it.subject == MediaFileSubject.PACK_VERSION_MEDIA })
    }

    @Test fun uninstallDeletesMediaImmediatelyWhenNothingPinsIt() = runBlocking {
        library.installBundledDevelopment(bytes(pack(1)))
        val installedDir = File(contentRoot, "versions/fixture-pack/1")
        assertTrue(installedDir.isDirectory)

        library.uninstall("fixture-pack")

        assertFalse(installedDir.exists())
        assertTrue(db.mediaFileFactDao().getPendingCleanup().none { it.subject == MediaFileSubject.PACK_VERSION_MEDIA })
    }

    @Test fun replacePromotedRepairsACorruptOrphanDirectoryWithFreshlyStagedFiles() {
        val files = PackFiles(directory.root)
        val stage = files.stage("pack-x", "op-1")
        files.ensureDirectory(stage)
        File(stage, "manifest.json").writeText("good manifest bytes")
        File(stage, "audio-a1").writeText("good audio bytes")

        // Simulate an orphaned promoted directory (e.g. left by a crash mid-move)
        // whose manifest hash happens to match but whose audio is corrupt/missing.
        val target = files.version("pack-x", 1)
        files.ensureDirectory(target)
        File(target, "manifest.json").writeText("good manifest bytes")
        // No audio-a1 written under target: this stands in for corruption.

        val manifestHash = ContentTransport.hash(File(stage, "manifest.json"))
        val promoted = files.promote("pack-x", 1, "op-1", manifestHash)
        assertEquals(target.canonicalFile, promoted.canonicalFile)
        assertFalse(File(promoted, "audio-a1").isFile) // confirms the orphan was reused, not the good stage

        val repaired = files.replacePromoted("pack-x", 1, "op-1")
        assertEquals(target.canonicalFile, repaired.canonicalFile)
        assertTrue(File(repaired, "audio-a1").isFile)
        assertEquals("good audio bytes", File(repaired, "audio-a1").readText())
        assertFalse(stage.exists()) // staged files were moved, not copied
    }

    @Test fun storageUsageSumsOnlyAssetsWithALiveLocalUri() = runBlocking {
        db.languageDao().insertMissing(listOf(LanguageEntity("lang-1", "Test", false)))
        db.contentDao().insertPackVersion(
            PackVersionEntity(packId = "pack-1", version = 1, schemaVersion = 1, minReaderVersion = 1,
                languageId = "lang-1", languageCode = "sn", languageName = "Shona", title = "Greetings",
                publication = "published", manifestJson = "{}", manifestSha256 = "a".repeat(64),
                phraseCount = 0, lessonCount = 0, assetCount = 2),
        )
        db.contentDao().insertInstalledPack(InstalledPackEntity(packId = "pack-1", currentVersion = 1, status = InstalledPackStatus.READY))
        db.contentDao().insertAssets(
            listOf(
                ContentAssetEntity(packId = "pack-1", packVersion = 1, assetId = "a1", key = "k1",
                    sha256 = "b".repeat(64), bytes = 1000L, mimeType = "audio/mpeg", localUri = "/present/a1"),
                ContentAssetEntity(packId = "pack-1", packVersion = 1, assetId = "a2", key = "k2",
                    sha256 = "c".repeat(64), bytes = 2000L, mimeType = "audio/mpeg", localUri = null),
            ),
        )

        val report = library.storageUsage()
        assertEquals(1000L, report.installedBytes) // the reclaimed (null-URI) asset is excluded
        assertEquals(0, report.pendingCleanupPackCount)

        db.mediaFileFactDao().upsert(
            MediaFileFactEntity(subject = MediaFileSubject.PACK_VERSION_MEDIA, subjectId = "pack-1",
                filePath = "pack:pack-1", expectedSha256 = null, status = MediaFileStatus.PENDING_CLEANUP),
        )
        assertEquals(1, library.storageUsage().pendingCleanupPackCount)
    }

    private fun bytes(pack: PackManifest) = ContentJson.encodeToString(pack).toByteArray()

    private fun pack(version: Int) = PackManifest(
        schemaVersion = 1, minReaderVersion = 1, id = "fixture-pack", version = version,
        language = ContentLanguage("fixture-language", "en", "English test fixture"),
        title = "Fixture", objective = "Test delivery, not language teaching",
        publication = PublicationStatus.DEVELOPMENT,
        phrases = listOf(ManagedPhrase("fixture-word", "Hello", "Hello")),
        lessons = listOf(Lesson(
            id = "fixture-lesson", revision = 1, title = "Fixture lesson", objective = "Read",
            format = LessonFormat.GUIDED_CONVERSATION,
            activities = listOf(Activity.DialogueTurn("fixture-turn", "Test", "Hello")),
            requiredActivityIds = listOf("fixture-turn"),
        )),
        assets = emptyList(),
    )

    private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
