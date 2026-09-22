package com.root.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    // Room needs an explicit converter because ConfidenceLevel is a Kotlin enum,
    // not one of Room's directly supported column types.
    @TypeConverter
    fun fromConfidence(value: ConfidenceLevel): String = value.name

    @TypeConverter
    fun toConfidence(value: String): ConfidenceLevel = ConfidenceLevel.valueOf(value)

    @TypeConverter
    fun fromSessionStatus(value: PracticeSessionStatus): String = value.name

    @TypeConverter
    fun toSessionStatus(value: String): PracticeSessionStatus = PracticeSessionStatus.valueOf(value)

    @TypeConverter
    fun fromEndReason(value: PracticeEndReason?): String? = value?.name

    @TypeConverter
    fun toEndReason(value: String?): PracticeEndReason? = value?.let { PracticeEndReason.valueOf(it) }

    @TypeConverter
    fun fromEntryState(value: QueueEntryState): String = value.name

    @TypeConverter
    fun toEntryState(value: String): QueueEntryState = QueueEntryState.valueOf(value)
}

/**
 * Room database for languages, packs, phrases, attempts, weekly challenges, and
 * durable practice runs. A single process-wide singleton via [get] avoids opening
 * the SQLite file twice.
 */
@Database(
    entities = [
        LanguageEntity::class, PackEntity::class, PhraseEntity::class,
        AttemptEntity::class, WeeklyChallengeEntity::class,
        PracticeSessionEntity::class, PracticeQueueEntryEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun languageDao(): LanguageDao
    abstract fun packDao(): PackDao
    abstract fun phraseDao(): PhraseDao
    abstract fun attemptDao(): AttemptDao
    abstract fun challengeDao(): ChallengeDao
    abstract fun practiceDao(): PracticeDao

    companion object {
        /**
         * Version 1 -> 2: adds the weekly_challenges table and renames the original
         * four-level felt-confidence scale (Blank/Shaky/OK/Solid) to the three-level
         * outcome scale (Missed/Close/Got it). Existing attempt rows are remapped in
         * place so a learner's history survives the app update instead of being wiped.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS weekly_challenges (
                        id TEXT NOT NULL PRIMARY KEY,
                        week_start INTEGER NOT NULL,
                        theme TEXT NOT NULL,
                        completed INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                // Version 1 also existed before the outcome-scale rename.
                db.execSQL(
                    """
                    UPDATE attempts SET confidence = CASE confidence
                        WHEN 'BLANK' THEN 'MISSED'
                        WHEN 'SHAKY' THEN 'CLOSE'
                        WHEN 'OK' THEN 'CLOSE'
                        WHEN 'SOLID' THEN 'GOT_IT'
                        ELSE confidence END
                    WHERE confidence IN ('BLANK', 'SHAKY', 'OK', 'SOLID')
                    """.trimIndent()
                )
            }
        }

        /**
         * Version 2 -> 3: adds durable local practice runs (replacing the previous
         * in-memory SessionQueue + SavedStateHandle Bundle restoration). Purely
         * additive — no existing table is touched, and no legacy in-memory/Bundle
         * history is replayed into the new tables, per the confirmed plan.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS practice_sessions (
                        id TEXT NOT NULL PRIMARY KEY,
                        language_id TEXT NOT NULL,
                        pack_id TEXT,
                        status TEXT NOT NULL,
                        started_at INTEGER NOT NULL,
                        ended_at INTEGER,
                        end_reason TEXT,
                        next_position INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS practice_queue_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        phrase_id TEXT NOT NULL,
                        pack_id_snapshot TEXT NOT NULL,
                        prompt_snapshot TEXT NOT NULL,
                        answer_snapshot TEXT NOT NULL,
                        audio_snapshot TEXT,
                        phrase_revision INTEGER NOT NULL,
                        position INTEGER NOT NULL,
                        is_retry INTEGER NOT NULL,
                        origin_entry_id TEXT,
                        state TEXT NOT NULL,
                        FOREIGN KEY(session_id) REFERENCES practice_sessions(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_practice_queue_entries_session_id ON practice_queue_entries(session_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_practice_queue_entries_phrase_id ON practice_queue_entries(phrase_id)"
                )
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "root.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { instance = it }
            }
    }
}
