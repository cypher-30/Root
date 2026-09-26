package com.root.app.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Schema version 7's entity set. Built from live entity classes, so its
 *  `contribution_drafts` table is rebuilt below with v7's exact columns. */
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
    version = 7,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabaseV7 : RoomDatabase()

/**
 * [AppDatabase.MIGRATION_7_8] adds the nullable `language_name_draft` column
 * so a draft for a not-yet-created language keeps its typed name. Existing
 * drafts survive unchanged, and opening with the real [AppDatabase] proves the
 * migrated table matches [ContributionDraftEntity].
 */
class Migration7To8Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration-7-8-test.db"

    @Before fun cleanupBefore() { context.deleteDatabase(dbName) }
    @After fun cleanupAfter() { context.deleteDatabase(dbName) }

    @Test fun migrationKeepsDraftsAndAddsLanguageName() {
        createV7WithDraft()

        val migrated = open()
        try {
            runBlocking {
                val draft = migrated.contributionDraftDao().getById("draft-1")!!
                assertEquals("How are you?", draft.promptDraft)
                assertEquals("Nang'o", draft.answerDraft)
                assertEquals(ContributionAudioState.NONE, draft.audioState)
                assertNull(draft.languageNameDraft)

                migrated.contributionDraftDao().upsert(draft.copy(languageNameDraft = "New language"))
                assertEquals("New language", migrated.contributionDraftDao().getById("draft-1")!!.languageNameDraft)
            }
        } finally { migrated.close() }
    }

    @Test fun migrationIsSafeWhenColumnAlreadyExists() {
        // A fixture built from live entities already has the column; the
        // migration must not fail with a duplicate-column error.
        val v7 = Room.databaseBuilder(context, AppDatabaseV7::class.java, dbName).build()
        try { v7.openHelper.writableDatabase } finally { v7.close() }

        val migrated = open()
        try {
            runBlocking { assertFalse(migrated.contributionDraftDao().getOpenDrafts().any()) }
        } finally { migrated.close() }
    }

    private fun createV7WithDraft() {
        val v7 = Room.databaseBuilder(context, AppDatabaseV7::class.java, dbName).build()
        try {
            val db = v7.openHelper.writableDatabase
            db.execSQL("DROP TABLE contribution_drafts")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `contribution_drafts` (`id` TEXT NOT NULL, `language_id` TEXT NOT NULL, " +
                    "`pack_id` TEXT, `prompt_draft` TEXT NOT NULL, `answer_draft` TEXT NOT NULL, `speaker_label_draft` TEXT, " +
                    "`audio_draft_path` TEXT, `audio_state` TEXT NOT NULL, `committed_phrase_id` TEXT, " +
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "INSERT INTO contribution_drafts (id, language_id, pack_id, prompt_draft, answer_draft, speaker_label_draft, " +
                    "audio_draft_path, audio_state, committed_phrase_id, created_at, updated_at) " +
                    "VALUES ('draft-1', 'lang-1', NULL, 'How are you?', 'Nang''o', NULL, NULL, 'NONE', NULL, 1, 2)",
            )
        } finally { v7.close() }
    }

    private fun open(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
}
