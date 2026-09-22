package com.root.app.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** A real Room database matching schema version 3 exactly as [AppDatabase]
 *  declared it before the teaching-content-foundation migration — see
 *  [Migration2To3Test.AppDatabaseV2] for why this is built through Room rather
 *  than hand-written SQL. */
@Database(
    entities = [
        LanguageEntity::class, PackEntity::class, PhraseEntity::class,
        AttemptEntity::class, WeeklyChallengeEntity::class,
        PracticeSessionEntity::class, PracticeQueueEntryEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabaseV3 : RoomDatabase() {
    abstract fun languageDao(): LanguageDao
    abstract fun packDao(): PackDao
    abstract fun phraseDao(): BaselinePhraseDao
    abstract fun attemptDao(): BaselineAttemptDao
    abstract fun practiceDao(): PracticeDao
}

/**
 * Instrumented test for [AppDatabase.MIGRATION_3_4]: every v3 table/row
 * survives untouched, the new content/learning tables come up usable, and
 * opening the migrated database with the real [AppDatabase] (all entities
 * included) succeeds — Room throws immediately on open if this migration's
 * SQL disagrees with what the entities declare.
 */
class Migration3To4Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration-3-4-test.db"

    @Before fun cleanupBefore() { context.deleteDatabase(dbName) }
    @After fun cleanupAfter() { context.deleteDatabase(dbName) }

    @Test fun migrationPreservesExistingDataAndAddsUsableContentAndLearningTables() {
        val v3 = Room.databaseBuilder(context, AppDatabaseV3::class.java, dbName).build()
        try {
            runBlocking {
                v3.languageDao().insertMissing(listOf(LanguageEntity("lang-1", "Test", false)))
                v3.packDao().insertMissing(listOf(PackEntity("pack-1", "lang-1", "Theme", 0, true)))
                v3.phraseDao().insertMissing(
                    listOf(PhraseEntity(id = "p1", packId = "pack-1", prompt = "P", answer = "A", audioAsset = null)),
                )
                v3.attemptDao().insert(AttemptEntity(phraseId = "p1", confidence = ConfidenceLevel.GOT_IT, reviewedAt = 1L, nextDueAt = 2L))
            }
        } finally { v3.close() }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_3_4)
            .build()
        try {
            runBlocking {
                // Existing v3 data survives byte-for-byte.
                assertEquals("Test", migrated.languageDao().getById("lang-1")!!.name)
                assertEquals(1, migrated.phraseDao().getForPack("pack-1").size)
                assertEquals(1, migrated.attemptDao().capabilityCount("lang-1"))

                // New content tables are usable.
                val version = PackVersionEntity(
                    packId = "pack-shona-greetings", version = 1, schemaVersion = 1, minReaderVersion = 1,
                    languageId = "lang-1", languageCode = "sn", languageName = "Shona", title = "Greetings",
                    publication = "development", manifestJson = "{}", manifestSha256 = "a".repeat(64),
                    phraseCount = 1, lessonCount = 1, assetCount = 1,
                )
                migrated.contentDao().insertPackVersion(version)
                assertNotNull(migrated.contentDao().getPackVersion("pack-shona-greetings", 1))

                migrated.contentDao().insertInstalledPack(
                    InstalledPackEntity(packId = "pack-shona-greetings", currentVersion = 1, status = InstalledPackStatus.READY),
                )
                assertEquals(InstalledPackStatus.READY, migrated.contentDao().getInstalledPack("pack-shona-greetings")!!.status)

                migrated.contentDao().insertManagedPhrase(
                    ManagedPhraseEntity(phraseId = "p1", packId = "pack-shona-greetings", packVersion = 1, sourcePhraseId = "phrase-1"),
                )
                assertNotNull(migrated.contentDao().getManagedPhrase("p1"))

                // New learning tables are usable.
                val run = LessonRunEntity(
                    lessonId = "lesson-1", packId = "pack-shona-greetings", packVersion = 1,
                    lessonRevision = 1, status = LessonRunStatus.ACTIVE,
                )
                migrated.learningDao().insertRun(run)
                assertNotNull(migrated.learningDao().getRun(run.id))
                assertEquals(run.id, migrated.learningDao().getOpenRun("pack-shona-greetings", "lesson-1")!!.id)

                migrated.learningDao().insertEvent(
                    LearningEventEntity(runId = run.id, activityId = "act-1", kind = LearningEventKind.EXPOSURE),
                )
                assertNotNull(migrated.learningDao().latestEventForActivity(run.id, "act-1"))
                assertNull(migrated.learningDao().latestEventForActivity(run.id, "act-does-not-exist"))
            }
        } finally { migrated.close() }
    }
}
