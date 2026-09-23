package com.root.app.data

import androidx.room.Database
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * A real Room database matching schema version 2 exactly as [AppDatabase] declared
 * it before the practice-foundation migration. Building the "before" database
 * through Room itself (rather than hand-written CREATE TABLE SQL) guarantees the
 * baseline schema this test migrates from is byte-for-byte what version 2 actually
 * was, so [Migration2To3Test] only has to get [AppDatabase.MIGRATION_2_3] right.
 */
/** Minimal DAOs scoped to exactly what the v2 baseline needs to seed data —
 *  deliberately NOT the full production [PhraseDao]/[AttemptDao], whose
 *  queries now join `managed_phrases` (a table that did not exist until
 *  version 4 and must never be assumed present by a v2 schema snapshot). */
@Dao
interface BaselinePhraseDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(phrases: List<PhraseEntity>)
}

@Dao
interface BaselineAttemptDao {
    @Insert
    suspend fun insert(attempt: AttemptEntity)
}

@Database(
    entities = [
        LanguageEntity::class, PackEntity::class, PhraseEntity::class,
        AttemptEntity::class, WeeklyChallengeEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabaseV2 : RoomDatabase() {
    abstract fun languageDao(): LanguageDao
    abstract fun packDao(): PackDao
    abstract fun phraseDao(): BaselinePhraseDao
    abstract fun attemptDao(): BaselineAttemptDao
}

/**
 * Instrumented test for [AppDatabase.MIGRATION_2_3]: existing tables/rows survive
 * untouched, the new practice tables come up usable, and — most importantly —
 * opening the migrated database with the real [AppDatabase] (entities included)
 * succeeds at all, since Room throws immediately on open if a migration's SQL
 * doesn't exactly match what the entities declare.
 */
class Migration2To3Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration-2-3-test.db"

    @Before fun cleanupBefore() { context.deleteDatabase(dbName) }
    @After fun cleanupAfter() { context.deleteDatabase(dbName) }

    @Test fun migrationPreservesExistingDataAndAddsUsablePracticeTables() {
        val v2 = Room.databaseBuilder(context, AppDatabaseV2::class.java, dbName).build()
        try {
            runBlocking {
                v2.languageDao().insertMissing(listOf(LanguageEntity("lang-1", "Test", false)))
                v2.packDao().insertMissing(listOf(PackEntity("pack-1", "lang-1", "Theme", 0, true)))
                v2.phraseDao().insertMissing(listOf(PhraseEntity(id = "p1", packId = "pack-1", prompt = "P", answer = "A", audioAsset = null)))
                v2.attemptDao().insert(AttemptEntity(phraseId = "p1", confidence = ConfidenceLevel.GOT_IT, reviewedAt = 1L, nextDueAt = 2L))
            }
        } finally { v2.close() }

        // Opening with the real entity set is itself the strongest assertion here:
        // Room validates the post-migration schema against what AppDatabase's
        // entities expect and throws if any migration's SQL disagrees with them.
        // Use the production upgrade chain through the current schema.
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            runBlocking {
                assertEquals("Test", migrated.languageDao().getById("lang-1")!!.name)
                assertEquals(1, migrated.phraseDao().getForPack("pack-1").size)
                assertEquals(1, migrated.attemptDao().capabilityCount("lang-1"))

                val session = PracticeSessionEntity(
                    languageId = "lang-1", packId = null,
                    status = PracticeSessionStatus.ACTIVE, startedAt = System.currentTimeMillis(),
                )
                migrated.practiceDao().insertSession(session)
                assertNotNull(migrated.practiceDao().getSession(session.id))

                val entry = PracticeQueueEntryEntity(
                    sessionId = session.id, phraseId = "p1", packIdSnapshot = "pack-1",
                    promptSnapshot = "P", answerSnapshot = "A", audioSnapshot = null,
                    phraseRevision = 0L, position = 0, isRetry = false, originEntryId = null,
                    state = QueueEntryState.PENDING,
                )
                migrated.practiceDao().insertEntry(entry)
                assertEquals(1, migrated.practiceDao().pendingCount(session.id))
            }
        } finally { migrated.close() }
    }
}
