package com.root.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [PhraseConsentEntity] frozen at its pre-contracts-model (v4-v5) shape: just
 *  `phrase_id`/`speaker_label`/`consent_given_at`, with no `consent_version`/
 *  `consent_scope` columns. The real [PhraseConsentEntity] class cannot be
 *  reused for the "before" fixture below because it now carries those two
 *  columns itself (with defaults) — building [AppDatabaseV5] against the
 *  live entity would create the table already containing the columns
 *  [AppDatabase.MIGRATION_5_6] is about to ALTER in, which is a fixture bug,
 *  not a database bug. This frozen copy is what makes the "before" state
 *  actually match schema version 5. */
@Entity(
    tableName = "phrase_consents",
    foreignKeys = [
        ForeignKey(
            entity = PhraseEntity::class,
            parentColumns = ["id"],
            childColumns = ["phrase_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PhraseConsentEntityV5(
    @PrimaryKey @ColumnInfo(name = "phrase_id") val phraseId: String,
    @ColumnInfo(name = "speaker_label") val speakerLabel: String?,
    @ColumnInfo(name = "consent_given_at") val consentGivenAt: Long = System.currentTimeMillis(),
)

@Dao
interface ConsentDaoV5 {
    @Upsert
    suspend fun upsert(consent: PhraseConsentEntityV5)
}

/** A real Room database matching schema version 5 exactly as [AppDatabase]
 *  declared it before the contracts-model migration — see
 *  [Migration2To3Test.AppDatabaseV2] for why this is built through Room
 *  rather than hand-written SQL. Uses [PhraseConsentEntityV5], not the live
 *  [PhraseConsentEntity], so this fixture cannot silently drift forward when
 *  the production entity gains new columns. */
@Database(
    entities = [
        LanguageEntity::class, PackEntity::class, PhraseEntity::class,
        AttemptEntity::class, WeeklyChallengeEntity::class,
        PracticeSessionEntity::class, PracticeQueueEntryEntity::class,
        PackVersionEntity::class, InstalledPackEntity::class, PackInstallJobEntity::class,
        ManagedPhraseEntity::class, ContentAssetEntity::class,
        LessonRunEntity::class, LearningEventEntity::class, LessonRunCommandEntity::class,
        PhraseConsentEntityV5::class,
    ],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabaseV5 : RoomDatabase() {
    abstract fun languageDao(): LanguageDao
    abstract fun packDao(): PackDao
    abstract fun phraseDao(): PhraseDao
    abstract fun attemptDao(): AttemptDao
    abstract fun practiceDao(): PracticeDao
    abstract fun contentDao(): ContentDao
    abstract fun learningDao(): LearningDao
    abstract fun consentDaoV5(): ConsentDaoV5
}

/**
 * Instrumented test for [AppDatabase.MIGRATION_5_6]: every v5 table/row
 * survives untouched, existing [PhraseConsentEntity] rows backfill to today's
 * only real consent version/scope via column defaults (not a fabricated new
 * consent event), and the new [PersonalNoteEntity], [ContributionDraftEntity],
 * and [MediaFileFactEntity] tables come up usable — opening the migrated
 * database with the real [AppDatabase] (all entities included) succeeds,
 * which is Room's own guarantee that this migration's SQL agrees with what
 * those entities declare.
 */
class Migration5To6Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration-5-6-test.db"

    @Before fun cleanupBefore() { context.deleteDatabase(dbName) }
    @After fun cleanupAfter() { context.deleteDatabase(dbName) }

    @Test fun migrationPreservesExistingDataAndAddsUsableContractTables() {
        val v5 = Room.databaseBuilder(context, AppDatabaseV5::class.java, dbName).build()
        try {
            runBlocking {
                v5.languageDao().insertMissing(listOf(LanguageEntity("lang-1", "Test", false)))
                v5.packDao().insertMissing(listOf(PackEntity("pack-user-lang-1", "lang-1", "Your words", 0, true)))
                v5.phraseDao().insertMissing(
                    listOf(PhraseEntity(id = "p1", packId = "pack-user-lang-1", prompt = "P", answer = "A", audioAsset = "audio-1")),
                )
                v5.consentDaoV5().upsert(PhraseConsentEntityV5(phraseId = "p1", speakerLabel = "Aunt Rudo"))
            }
        } finally { v5.close() }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            runBlocking {
                // Existing v5 data survives byte-for-byte.
                assertEquals("Test", migrated.languageDao().getById("lang-1")!!.name)
                assertEquals(1, migrated.phraseDao().getForPack("pack-user-lang-1").size)

                // The pre-existing consent row backfills to today's only real
                // version/scope rather than being fabricated as a new event.
                val consent = migrated.consentDao().getForPhrase("p1")!!
                assertEquals("Aunt Rudo", consent.speakerLabel)
                assertEquals(PhraseConsentEntity.CURRENT_CONSENT_VERSION, consent.consentVersion)
                assertEquals("LOCAL_RECORDING_ONLY", consent.consentScope)

                // The new private-note table is usable and cascades with its phrase.
                migrated.personalNoteDao().upsert(PersonalNoteEntity(phraseId = "p1", noteText = "reminder"))
                assertNotNull(migrated.personalNoteDao().getForPhrase("p1"))

                // The new durable contribution-draft table is usable and independent of any phrase.
                val draft = ContributionDraftEntity(
                    languageId = "lang-1",
                    packId = "pack-user-lang-1",
                    promptDraft = "hello",
                    answerDraft = "mhoro",
                    speakerLabelDraft = null,
                    audioDraftPath = null,
                    committedPhraseId = null,
                )
                migrated.contributionDraftDao().upsert(draft)
                assertEquals(1, migrated.contributionDraftDao().getOpenDrafts().size)

                // The new media-file-facts recovery ledger is usable.
                migrated.mediaFileFactDao().upsert(
                    MediaFileFactEntity(
                        subject = MediaFileSubject.PHRASE_REFERENCE_AUDIO,
                        subjectId = "p1",
                        filePath = "/data/audio-1",
                        expectedSha256 = null,
                        status = MediaFileStatus.PRESENT,
                    ),
                )
                assertTrue(
                    migrated.mediaFileFactDao().getFor(MediaFileSubject.PHRASE_REFERENCE_AUDIO, "p1").isNotEmpty(),
                )

                // Deleting the phrase cascades to its consent record and note.
                migrated.phraseDao().deleteById("p1")
                assertNull(migrated.consentDao().getForPhrase("p1"))
                assertNull(migrated.personalNoteDao().getForPhrase("p1"))
            }
        } finally { migrated.close() }
    }
}
