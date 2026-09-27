package com.root.app.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Schema version 8's entity set: everything except [SavedPhraseEntity]. */
@Database(
    entities = [
        LanguageEntity::class, PackEntity::class, PhraseEntity::class,
        AttemptEntity::class, WeeklyChallengeEntity::class,
        PracticeSessionEntity::class, PracticeQueueEntryEntity::class,
        PackVersionEntity::class, InstalledPackEntity::class, PackInstallJobEntity::class,
        ManagedPhraseEntity::class, ContentAssetEntity::class,
        LessonRunEntity::class, LearningEventEntity::class, LessonRunCommandEntity::class,
        PhraseConsentEntity::class,
        PersonalNoteEntity::class, ContributionDraftEntity::class, MediaFileFactEntity::class,
        PracticeMarkEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabaseV8 : RoomDatabase() {
    abstract fun languageDao(): LanguageDao
    abstract fun packDao(): PackDao
    abstract fun phraseDao(): PhraseDao
}

/**
 * [AppDatabase.MIGRATION_8_9] only adds the Explore notebook's `saved_phrases`
 * table. Existing phrases survive, opening with the real [AppDatabase] proves
 * the table matches [SavedPhraseEntity], and a saved phrase goes when its
 * phrase does.
 */
class Migration8To9Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration-8-9-test.db"

    @Before fun cleanupBefore() { context.deleteDatabase(dbName) }
    @After fun cleanupAfter() { context.deleteDatabase(dbName) }

    @Test fun migrationKeepsPhrasesAndAddsNotebook() {
        val v8 = Room.databaseBuilder(context, AppDatabaseV8::class.java, dbName).build()
        try {
            runBlocking {
                v8.languageDao().upsertAll(listOf(LanguageEntity("lang-1", "Swahili", false)))
                v8.packDao().upsertAll(listOf(PackEntity("pack-1", "lang-1", "Greetings", 0, true)))
                v8.phraseDao().upsertAll(listOf(PhraseEntity("phrase-1", "pack-1", "Welcome", "Karibu", null)))
            }
        } finally { v8.close() }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            runBlocking {
                assertEquals("Karibu", migrated.phraseDao().getById("phrase-1")!!.answer)
                val saved = migrated.savedPhraseDao()
                assertTrue(saved.observeForLanguage("lang-1").first().isEmpty())

                saved.save(SavedPhraseEntity("phrase-1"))
                saved.save(SavedPhraseEntity("phrase-1"))
                assertEquals(listOf("phrase-1"), saved.observeForLanguage("lang-1").first().map { it.id })
                assertEquals(listOf("phrase-1"), saved.observeIds().first())

                saved.remove("phrase-1")
                assertTrue(saved.observeIds().first().isEmpty())
            }
        } finally { migrated.close() }
    }
}
