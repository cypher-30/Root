package com.root.app.content

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.root.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentLibraryTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var db: AppDatabase
    private lateinit var library: ContentLibrary

    @Before fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        library = ContentLibrary(context, db, directory.newFolder())
    }

    @After fun close() = db.close()

    @Test fun updateRetirementAndReinstallPreserveRecallIdentityAndEvents() = runBlocking {
        library.installBundledDevelopment(bytes(pack(1)))
        db.attemptDao().insert(AttemptEntity(id = "recall", phraseId = "fixture-word",
            confidence = ConfidenceLevel.CLOSE, reviewedAt = 100, nextDueAt = 200))
        library.installBundledDevelopment(bytes(pack(2)))
        assertEquals(2, db.contentDao().getInstalledPack("fixture-pack")!!.currentVersion)
        assertEquals(2, db.contentDao().versionsForPack("fixture-pack").size)
        assertEquals(200L, db.attemptDao().getById("recall")!!.nextDueAt)
        assertEquals(1, db.phraseDao().countForPack("fixture-pack"))
        library.uninstall("fixture-pack")
        assertEquals(InstalledPackStatus.RETIRED, db.contentDao().getInstalledPack("fixture-pack")!!.status)
        assertEquals(0, db.phraseDao().countForPack("fixture-pack"))
        assertNotNull(db.attemptDao().getById("recall"))
        library.installBundledDevelopment(bytes(pack(2)))
        assertEquals(1, db.phraseDao().countForPack("fixture-pack"))
        assertNotNull(db.attemptDao().getById("recall"))
    }

    @Test fun collisionsFailWithoutReplacingPersonalContentOrActivatingPack() = runBlocking {
        db.languageDao().insertMissing(listOf(LanguageEntity("personal-language", "Personal", false)))
        db.packDao().insertMissing(listOf(PackEntity("personal-pack", "personal-language", "Your words", 0, true)))
        val original = PhraseEntity("fixture-word", "personal-pack", "My meaning", "My word", null)
        db.phraseDao().insertMissing(listOf(original))
        try {
            library.installBundledDevelopment(bytes(pack(1)))
            fail("A managed import must not take over a personal phrase ID")
        } catch (_: IllegalArgumentException) {
            assertEquals(original, db.phraseDao().getById("fixture-word"))
            assertNull(db.contentDao().getInstalledPack("fixture-pack"))
            assertNull(db.languageDao().getById("fixture-language"))
        }
    }

    @Test fun immutableRevisionCannotChangeAndUnchangedImportIsIdempotent() = runBlocking {
        library.installBundledDevelopment(bytes(pack(1)))
        library.installBundledDevelopment(bytes(pack(1)))
        assertEquals(1, db.contentDao().versionsForPack("fixture-pack").size)
        try {
            library.installBundledDevelopment(bytes(pack(1).copy(title = "Changed")))
            fail("Same-version content mutation must fail")
        } catch (_: ContentDownloadException) {
            assertEquals("Fixture", library.manifest("fixture-pack")!!.title)
        }

        @Test fun releaseUpgradeCannotInheritDevelopmentPractice() = runBlocking {
            library.installBundledDevelopment(bytes(pack(1)))
            ContentBuildPolicy.apply(db, allowDevelopment = false)
            assertEquals(InstalledPackStatus.RETIRED, db.contentDao().getInstalledPack("fixture-pack")!!.status)
            assertEquals(0, db.phraseDao().countForPack("fixture-pack"))
            assertNotNull(db.phraseDao().getById("fixture-word"))
        }
    }

    private fun bytes(pack: PackManifest) = ContentJson.encodeToString(pack).toByteArray()

    private fun pack(version: Int) = PackManifest(
        schemaVersion = 1, minReaderVersion = 1, id = "fixture-pack", version = version,
        language = ContentLanguage("fixture-language", "en", "English test fixture"),
        title = "Fixture", objective = "Test persistence, not language teaching",
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
}
