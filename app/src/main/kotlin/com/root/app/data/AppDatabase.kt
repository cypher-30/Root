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
}

/**
 * Room database for languages, packs, phrases, attempts, and weekly challenges.
 * A single process-wide singleton via [get] avoids opening the SQLite file twice.
 */
@Database(
    entities = [
        LanguageEntity::class, PackEntity::class, PhraseEntity::class,
        AttemptEntity::class, WeeklyChallengeEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun languageDao(): LanguageDao
    abstract fun packDao(): PackDao
    abstract fun phraseDao(): PhraseDao
    abstract fun attemptDao(): AttemptDao
    abstract fun challengeDao(): ChallengeDao

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

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "root.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
