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

    @TypeConverter
    fun fromInstalledPackStatus(value: InstalledPackStatus): String = value.name

    @TypeConverter
    fun toInstalledPackStatus(value: String): InstalledPackStatus = InstalledPackStatus.valueOf(value)

    @TypeConverter
    fun fromPackInstallJobStatus(value: PackInstallJobStatus): String = value.name

    @TypeConverter
    fun toPackInstallJobStatus(value: String): PackInstallJobStatus = PackInstallJobStatus.valueOf(value)

    @TypeConverter
    fun fromLessonRunStatus(value: LessonRunStatus): String = value.name

    @TypeConverter
    fun toLessonRunStatus(value: String): LessonRunStatus = LessonRunStatus.valueOf(value)

    @TypeConverter
    fun fromLearningEventKind(value: LearningEventKind): String = value.name

    @TypeConverter
    fun toLearningEventKind(value: String): LearningEventKind = LearningEventKind.valueOf(value)

    @TypeConverter
    fun fromContributionAudioState(value: ContributionAudioState): String = value.name

    @TypeConverter
    fun toContributionAudioState(value: String): ContributionAudioState = ContributionAudioState.valueOf(value)

    @TypeConverter
    fun fromMediaFileStatus(value: MediaFileStatus): String = value.name

    @TypeConverter
    fun toMediaFileStatus(value: String): MediaFileStatus = MediaFileStatus.valueOf(value)

    @TypeConverter
    fun fromMediaFileSubject(value: MediaFileSubject): String = value.name

    @TypeConverter
    fun toMediaFileSubject(value: String): MediaFileSubject = MediaFileSubject.valueOf(value)
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
        PackVersionEntity::class, InstalledPackEntity::class, PackInstallJobEntity::class,
        ManagedPhraseEntity::class, ContentAssetEntity::class,
        LessonRunEntity::class, LearningEventEntity::class, LessonRunCommandEntity::class,
        PhraseConsentEntity::class,
        PersonalNoteEntity::class, ContributionDraftEntity::class, MediaFileFactEntity::class,
        PracticeMarkEntity::class,
    ],
    version = 7,
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
    abstract fun contentDao(): ContentDao
    abstract fun learningDao(): LearningDao
    abstract fun consentDao(): ConsentDao
    abstract fun personalNoteDao(): PersonalNoteDao
    abstract fun contributionDraftDao(): ContributionDraftDao
    abstract fun mediaFileFactDao(): MediaFileFactDao
    abstract fun practiceMarkDao(): PracticeMarkDao

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

        /**
         * Version 3 -> 4: adds the downloadable-teaching-content foundation —
         * immutable pack version manifests, install jobs, installed-pack
         * pointers, managed-phrase mapping, per-asset records, and the durable
         * lesson-run/event/command tables. Purely additive: every v3 table and
         * row is untouched, so an existing learner's languages/packs/phrases/
         * attempts/practice history survive unchanged. See
         * docs/TEACHING_CONTRACTS.md for the Room API this unlocks.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pack_versions (
                        id TEXT NOT NULL PRIMARY KEY,
                        pack_id TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        schema_version INTEGER NOT NULL,
                        min_reader_version INTEGER NOT NULL,
                        language_id TEXT NOT NULL,
                        language_code TEXT NOT NULL,
                        language_name TEXT NOT NULL,
                        title TEXT NOT NULL,
                        publication TEXT NOT NULL,
                        manifest_json TEXT NOT NULL,
                        manifest_sha256 TEXT NOT NULL,
                        phrase_count INTEGER NOT NULL,
                        lesson_count INTEGER NOT NULL,
                        asset_count INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_pack_versions_pack_id_version ON pack_versions(pack_id, version)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS installed_packs (
                        pack_id TEXT NOT NULL PRIMARY KEY,
                        current_version INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        installed_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pack_install_jobs (
                        id TEXT NOT NULL PRIMARY KEY,
                        request_id TEXT NOT NULL,
                        pack_id TEXT NOT NULL,
                        target_version INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        error_message TEXT,
                        started_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_pack_install_jobs_request_id ON pack_install_jobs(request_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pack_install_jobs_pack_id ON pack_install_jobs(pack_id)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS managed_phrases (
                        phrase_id TEXT NOT NULL PRIMARY KEY,
                        pack_id TEXT NOT NULL,
                        pack_version INTEGER NOT NULL,
                        source_phrase_id TEXT NOT NULL,
                        retired INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        FOREIGN KEY(phrase_id) REFERENCES phrases(id) ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_managed_phrases_phrase_id ON managed_phrases(phrase_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_managed_phrases_pack_id ON managed_phrases(pack_id)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS content_assets (
                        id TEXT NOT NULL PRIMARY KEY,
                        pack_id TEXT NOT NULL,
                        pack_version INTEGER NOT NULL,
                        asset_id TEXT NOT NULL,
                        key TEXT NOT NULL,
                        sha256 TEXT NOT NULL,
                        bytes INTEGER NOT NULL,
                        mime_type TEXT NOT NULL,
                        duration_ms INTEGER,
                        local_uri TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_content_assets_pack_id_pack_version_asset_id ON content_assets(pack_id, pack_version, asset_id)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lesson_runs (
                        id TEXT NOT NULL PRIMARY KEY,
                        lesson_id TEXT NOT NULL,
                        pack_id TEXT NOT NULL,
                        pack_version INTEGER NOT NULL,
                        lesson_revision INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        current_step_index INTEGER NOT NULL,
                        revealed_activity_ids TEXT NOT NULL,
                        superseded_by_run_id TEXT,
                        started_at INTEGER NOT NULL,
                        completed_at INTEGER,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_lesson_runs_lesson_id ON lesson_runs(lesson_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_lesson_runs_lesson_id_status ON lesson_runs(lesson_id, status)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_lesson_runs_pack_id_lesson_id_status ON lesson_runs(pack_id, lesson_id, status)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS learning_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        run_id TEXT NOT NULL,
                        activity_id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        response_json TEXT,
                        correct INTEGER,
                        occurred_at INTEGER NOT NULL,
                        FOREIGN KEY(run_id) REFERENCES lesson_runs(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_learning_events_run_id ON learning_events(run_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_learning_events_run_id_activity_id ON learning_events(run_id, activity_id)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lesson_run_commands (
                        command_id TEXT NOT NULL PRIMARY KEY,
                        run_id TEXT NOT NULL,
                        command_type TEXT NOT NULL,
                        payload_hash TEXT NOT NULL,
                        result_json TEXT NOT NULL,
                        applied_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_lesson_run_commands_run_id ON lesson_run_commands(run_id)"
                )
            }
        }

        /**
         * Version 4 -> 5: adds [PhraseConsentEntity], the local-only consent
         * record for a personally-contributed phrase's optional reference
         * recording (see docs/TEACHING_CONTRACTS.md and [RootRepository.contribute]/
         * [RootRepository.deletePersonalPhrase]). Purely additive — no existing
         * table or row is touched, so an existing learner's languages/packs/
         * phrases/attempts/practice/content/lesson history survives unchanged.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS phrase_consents (
                        phrase_id TEXT NOT NULL PRIMARY KEY,
                        speaker_label TEXT,
                        consent_given_at INTEGER NOT NULL,
                        FOREIGN KEY(phrase_id) REFERENCES phrases(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Version 5 -> 6: adds versioned/scoped consent columns to
         * [PhraseConsentEntity], durable [PersonalNoteEntity] private notes,
         * durable [ContributionDraftEntity] "add a word" drafts (replacing
         * screen-level saved state), and the [MediaFileFactEntity] recovery
         * ledger that separates byte promotion, DB pointer activation, and
         * deferred cleanup for learner-owned media. Purely additive: every
         * v5 table, row, and column is untouched, so an existing learner's
         * languages/packs/phrases/attempts/practice/content/lesson/consent
         * history survives unchanged. Existing [PhraseConsentEntity] rows get
         * `consent_version = 1` and `consent_scope = 'LOCAL_RECORDING_ONLY'`
         * (today's only real values) via column defaults, not a fabricated
         * new consent event.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE phrase_consents ADD COLUMN consent_version INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "ALTER TABLE phrase_consents ADD COLUMN consent_scope TEXT NOT NULL DEFAULT 'LOCAL_RECORDING_ONLY'"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS personal_notes (
                        phrase_id TEXT NOT NULL PRIMARY KEY,
                        note_text TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        FOREIGN KEY(phrase_id) REFERENCES phrases(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS contribution_drafts (
                        id TEXT NOT NULL PRIMARY KEY,
                        language_id TEXT NOT NULL,
                        pack_id TEXT,
                        prompt_draft TEXT NOT NULL,
                        answer_draft TEXT NOT NULL,
                        speaker_label_draft TEXT,
                        audio_draft_path TEXT,
                        audio_state TEXT NOT NULL,
                        committed_phrase_id TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_file_facts (
                        id TEXT NOT NULL PRIMARY KEY,
                        subject TEXT NOT NULL,
                        subject_id TEXT NOT NULL,
                        file_path TEXT NOT NULL,
                        expected_sha256 TEXT,
                        status TEXT NOT NULL,
                        checked_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_file_facts_subject_subject_id ON media_file_facts(subject, subject_id)"
                )
            }
        }

        /**
         * Version 6 -> 7: adds the practice_marks table backing the
         * idempotent, self-reported "Mark Practiced" acknowledgement. This is
         * wholly separate from the recall scheduler — it never touches
         * attempts, phrases, or `next_due_at`.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS practice_marks (
                        phrase_id TEXT NOT NULL PRIMARY KEY,
                        marked_at INTEGER NOT NULL,
                        FOREIGN KEY(phrase_id) REFERENCES phrases(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "root.db",
                )
                    .addMigrations(*MIGRATIONS)
                    .build().also { instance = it }
            }
    }
}
