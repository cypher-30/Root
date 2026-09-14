package com.root.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun fromConfidence(value: ConfidenceLevel): String = value.name

    @TypeConverter
    fun toConfidence(value: String): ConfidenceLevel = ConfidenceLevel.valueOf(value)
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
abstract class AppDatabase : RoomDatabase() {
    abstract fun languageDao(): LanguageDao
    abstract fun packDao(): PackDao
    abstract fun phraseDao(): PhraseDao
    abstract fun attemptDao(): AttemptDao
    abstract fun challengeDao(): ChallengeDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "root.db",
                )
                    // No real installs exist yet (pre-launch); destructive migration is
                    // the right call now so schema tweaks during the build don't crash
                    // on a device that already ran an earlier debug build. Revisit once
                    // this ships and real learner data exists on-device.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
