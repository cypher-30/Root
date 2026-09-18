package com.root.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Every DAO below exposes both an `upsertAll` (used by user edits/re-seeding, which
 * should overwrite) and an `insertMissing` (used by initial seeding, which should
 * never clobber existing learner history — see [SeedData]).
 */
@Dao
interface LanguageDao {
    @Upsert
    suspend fun upsertAll(languages: List<LanguageEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(languages: List<LanguageEntity>)

    @Query("SELECT * FROM languages ORDER BY name COLLATE NOCASE, id")
    suspend fun getAll(): List<LanguageEntity>

    @Query("SELECT * FROM languages WHERE id = :id")
    suspend fun getById(id: String): LanguageEntity?

    @Query("SELECT * FROM languages ORDER BY name")
    fun observeAll(): Flow<List<LanguageEntity>>
}

@Dao
interface PackDao {
    @Upsert
    suspend fun upsertAll(packs: List<PackEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(packs: List<PackEntity>)

    @Query("SELECT * FROM packs WHERE language_id = :languageId ORDER BY sortOrder, id")
    suspend fun getForLanguage(languageId: String): List<PackEntity>

    @Query("SELECT * FROM packs WHERE id = :id")
    suspend fun getById(id: String): PackEntity?

    @Query("SELECT * FROM packs WHERE language_id = :languageId ORDER BY sortOrder")
    fun observeForLanguage(languageId: String): Flow<List<PackEntity>>
}

@Dao
interface PhraseDao {
    @Upsert
    suspend fun upsertAll(phrases: List<PhraseEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(phrases: List<PhraseEntity>)

    @Query("SELECT * FROM phrases WHERE pack_id = :packId ORDER BY id")
    suspend fun getForPack(packId: String): List<PhraseEntity>

    @Query("SELECT * FROM phrases WHERE id = :id")
    suspend fun getById(id: String): PhraseEntity?

    @Query("SELECT * FROM phrases WHERE pack_id = :packId")
    fun observeForPack(packId: String): Flow<List<PhraseEntity>>

    @Query("SELECT COUNT(*) FROM phrases WHERE pack_id = :packId")
    suspend fun countForPack(packId: String): Int
}

@Dao
interface AttemptDao {
    @Insert
    suspend fun insert(attempt: AttemptEntity)

    /** The due queue for a session: every phrase in an unlocked pack whose latest
     *  attempt is due (or has no attempt yet). See [DUE_PHRASES_QUERY]. */
    @Query(DUE_PHRASES_QUERY)
    suspend fun dueForLanguage(
        languageId: String,
        nowMillis: Long,
        unlockedPackIds: List<String>,
        packId: String? = null,
    ): List<PhraseEntity>

    @Query(DUE_PHRASES_QUERY)
    fun observeDueToday(
        languageId: String,
        nowMillis: Long,
        unlockedPackIds: List<String>,
        packId: String? = null,
    ): Flow<List<PhraseEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM phrases p
        JOIN packs pk ON pk.id = p.pack_id
        JOIN attempts a ON a.rowid = (
            SELECT latest.rowid FROM attempts latest
            WHERE latest.phrase_id = p.id
            ORDER BY latest.reviewed_at DESC, latest.rowid DESC LIMIT 1
        )
        WHERE pk.language_id = :languageId AND a.confidence = 'GOT_IT'
        """
    )
    /** Count of distinct phrases whose *latest* attempt was Got it. This is the
     *  "roots grown" number: it reflects the most recent outcome, not a lifetime
     *  tally, so a later Missed/Close on the same phrase removes it from the count. */
    suspend fun capabilityCount(languageId: String): Int

    /** Feeds the weekly conversation-challenge card: whatever pack the learner has
     *  actually been practicing lately, so the challenge stays relevant. */
    @Query(
        """
        SELECT pk.theme FROM attempts a
        JOIN phrases p ON p.id = a.phrase_id
        JOIN packs pk ON pk.id = p.pack_id
        WHERE pk.language_id = :languageId AND pk.id IN (:unlockedPackIds)
        ORDER BY a.reviewed_at DESC, a.rowid DESC LIMIT 1
        """
    )
    suspend fun mostRecentlyPracticedTheme(languageId: String, unlockedPackIds: List<String>): String?
}

// rowid breaks millisecond ties by insertion order, selecting exactly one attempt.
internal const val DUE_PHRASES_QUERY = """
    SELECT p.* FROM phrases p
    JOIN packs pk ON pk.id = p.pack_id
    LEFT JOIN attempts a ON a.rowid = (
        SELECT latest.rowid FROM attempts latest
        WHERE latest.phrase_id = p.id
        ORDER BY latest.reviewed_at DESC, latest.rowid DESC LIMIT 1
    )
    WHERE pk.language_id = :languageId
        AND pk.id IN (:unlockedPackIds)
        AND (:packId IS NULL OR pk.id = :packId)
        AND (a.next_due_at IS NULL OR a.next_due_at <= :nowMillis)
    ORDER BY COALESCE(a.next_due_at, 0), pk.sortOrder, p.id
"""

@Dao
interface ChallengeDao {
    @Upsert
    suspend fun upsert(challenge: WeeklyChallengeEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(challenge: WeeklyChallengeEntity)

    @Query("SELECT * FROM weekly_challenges WHERE id = :id")
    suspend fun getById(id: String): WeeklyChallengeEntity?

    @Query("SELECT * FROM weekly_challenges WHERE week_start = :weekStart ORDER BY updated_at DESC, id LIMIT 1")
    suspend fun getForWeek(weekStart: Long): WeeklyChallengeEntity?

    /** Pre-multi-language installs stored challenges with random, unscoped IDs.
     *  Excluding the new `challenge-<language>-<weekStart>` naming lets
     *  [com.root.app.data.RootRepository.weeklyChallenge] find and reuse (not duplicate)
     *  a legacy challenge for the current week. */
    @Query(
        """
        SELECT * FROM weekly_challenges
        WHERE week_start = :weekStart AND id NOT LIKE 'challenge-%'
        ORDER BY updated_at DESC, id LIMIT 1
        """
    )
    suspend fun getLegacyForWeek(weekStart: Long): WeeklyChallengeEntity?

    /** Returns the number of rows updated (0 or 1) so callers can detect an unknown ID. */
    @Query("UPDATE weekly_challenges SET completed = 1, updated_at = :now WHERE id = :id")
    suspend fun markCompleted(id: String, now: Long): Int
}
