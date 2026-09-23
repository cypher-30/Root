package com.root.app.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** A real Room database matching schema version 6 exactly as [AppDatabase]
 *  declared it before the `audio-core` "Mark Practiced" migration — see
 *  [Migration5To6Test.AppDatabaseV5] for why this frozen copy (rather than
 *  the live, now-v7 [AppDatabase]) is what makes the "before" state actually
 *  match schema version 6. */
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
    ],
    version = 6,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabaseV6 : RoomDatabase() {
    abstract fun languageDao(): LanguageDao
    abstract fun packDao(): PackDao
    abstract fun phraseDao(): PhraseDao
    abstract fun attemptDao(): AttemptDao
}

/**
 * Instrumented test for [AppDatabase.MIGRATION_6_7]: every v6 table/row
 * survives untouched and the new `practice_marks` table comes up usable —
 * opening the migrated database with the real [AppDatabase] succeeds, which
 * is Room's own guarantee that this migration's SQL agrees with what
 * [PracticeMarkEntity] declares. Also covers the idempotency and
 * scheduler-isolation contract itself: marking practiced repeatedly leaves
 * exactly one row, and never creates an [AttemptEntity].
 */
class Migration6To7Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration-6-7-test.db"

    @Before fun cleanupBefore() { context.deleteDatabase(dbName) }
    @After fun cleanupAfter() { context.deleteDatabase(dbName) }

    @Test fun migrationPreservesExistingDataAndAddsUsablePracticeMarksTable() {
        val v6 = Room.databaseBuilder(context, AppDatabaseV6::class.java, dbName).build()
        try {
            runBlocking {
                v6.languageDao().insertMissing(listOf(LanguageEntity("lang-1", "Test", false)))
                v6.packDao().insertMissing(listOf(PackEntity("pack-user-lang-1", "lang-1", "Your words", 0, true)))
                v6.phraseDao().insertMissing(
                    listOf(PhraseEntity(id = "p1", packId = "pack-user-lang-1", prompt = "P", answer = "A", audioAsset = "audio-1")),
                )
            }
        } finally { v6.close() }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            runBlocking {
                // Existing v6 data survives byte-for-byte.
                assertEquals("Test", migrated.languageDao().getById("lang-1")!!.name)
                assertEquals(1, migrated.phraseDao().getForPack("pack-user-lang-1").size)
                assertEquals(0L, attemptCount(migrated))

                // The new practice_marks table is usable and idempotent: repeated
                // upserts on the same phrase leave exactly one row.
                assertNull(migrated.practiceMarkDao().getForPhrase("p1"))
                migrated.practiceMarkDao().upsert(PracticeMarkEntity(phraseId = "p1", markedAt = 1_000L))
                migrated.practiceMarkDao().upsert(PracticeMarkEntity(phraseId = "p1", markedAt = 2_000L))
                val mark = migrated.practiceMarkDao().getForPhrase("p1")!!
                assertEquals("p1", mark.phraseId)
                assertEquals(2_000L, mark.markedAt)

                // Marking practiced must never create an attempt or touch the
                // recall scheduler — it is a wholly separate acknowledgement.
                assertEquals(0L, attemptCount(migrated))

                // Deleting the phrase cascades to its practice mark.
                migrated.phraseDao().deleteById("p1")
                assertNull(migrated.practiceMarkDao().getForPhrase("p1"))
            }
        } finally { migrated.close() }
    }

    private fun attemptCount(db: AppDatabase): Long =
        db.query("SELECT COUNT(*) FROM attempts", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
}
