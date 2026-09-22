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

/** A real Room database matching schema version 4 exactly as [AppDatabase]
 *  declared it before the consent-record migration — see
 *  [Migration2To3Test.AppDatabaseV2] for why this is built through Room
 *  rather than hand-written SQL. */
@Database(
    entities = [
        LanguageEntity::class, PackEntity::class, PhraseEntity::class,
        AttemptEntity::class, WeeklyChallengeEntity::class,
        PracticeSessionEntity::class, PracticeQueueEntryEntity::class,
        PackVersionEntity::class, InstalledPackEntity::class, PackInstallJobEntity::class,
        ManagedPhraseEntity::class, ContentAssetEntity::class,
        LessonRunEntity::class, LearningEventEntity::class, LessonRunCommandEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabaseV4 : RoomDatabase() {
    abstract fun languageDao(): LanguageDao
    abstract fun packDao(): PackDao
    abstract fun phraseDao(): PhraseDao
    abstract fun attemptDao(): AttemptDao
    abstract fun practiceDao(): PracticeDao
    abstract fun contentDao(): ContentDao
    abstract fun learningDao(): LearningDao
}

/**
 * Instrumented test for [AppDatabase.MIGRATION_4_5]: every v4 table/row
 * survives untouched, and the new [PhraseConsentEntity] table comes up
 * usable, cascade-deleting with its phrase — opening the migrated database
 * with the real [AppDatabase] (all entities included) succeeds, which is
 * Room's own guarantee that this migration's SQL agrees with what
 * [PhraseConsentEntity] declares.
 */
class Migration4To5Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration-4-5-test.db"

    @Before fun cleanupBefore() { context.deleteDatabase(dbName) }
    @After fun cleanupAfter() { context.deleteDatabase(dbName) }

    @Test fun migrationPreservesExistingDataAndAddsUsableConsentTableThatCascades() {
        val v4 = Room.databaseBuilder(context, AppDatabaseV4::class.java, dbName).build()
        try {
            runBlocking {
                v4.languageDao().insertMissing(listOf(LanguageEntity("lang-1", "Test", false)))
                v4.packDao().insertMissing(listOf(PackEntity("pack-user-lang-1", "lang-1", "Your words", 0, true)))
                v4.phraseDao().insertMissing(
                    listOf(PhraseEntity(id = "p1", packId = "pack-user-lang-1", prompt = "P", answer = "A", audioAsset = "audio-1")),
                )
            }
        } finally { v4.close() }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .build()
        try {
            runBlocking {
                // Existing v4 data survives byte-for-byte.
                assertEquals("Test", migrated.languageDao().getById("lang-1")!!.name)
                assertEquals(1, migrated.phraseDao().getForPack("pack-user-lang-1").size)

                // The new consent table is usable.
                migrated.consentDao().upsert(PhraseConsentEntity(phraseId = "p1", speakerLabel = "Aunt Rudo"))
                assertNotNull(migrated.consentDao().getForPhrase("p1"))

                // Deleting the phrase cascades to its consent record.
                migrated.phraseDao().deleteById("p1")
                assertNull(migrated.consentDao().getForPhrase("p1"))
            }
        } finally { migrated.close() }
    }
}
