package com.root.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LanguageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(languages: List<LanguageEntity>)

    @Query("SELECT * FROM languages ORDER BY name")
    fun observeAll(): Flow<List<LanguageEntity>>
}

@Dao
interface PackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(packs: List<PackEntity>)

    @Query("SELECT * FROM packs WHERE language_id = :languageId ORDER BY sortOrder")
    fun observeForLanguage(languageId: String): Flow<List<PackEntity>>
}

@Dao
interface PhraseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(phrases: List<PhraseEntity>)

    @Query("SELECT * FROM phrases WHERE pack_id = :packId")
    fun observeForPack(packId: String): Flow<List<PhraseEntity>>

    @Query("SELECT COUNT(*) FROM phrases WHERE pack_id = :packId")
    suspend fun countForPack(packId: String): Int
}

@Dao
interface AttemptDao {
    @Insert
    suspend fun insert(attempt: AttemptEntity)

    /** Today's review queue: every phrase whose most recent attempt is due, plus every
     *  phrase with no attempt at all (first-time / cold-start, per DESIGN.md §8). */
    @Query(
        """
        SELECT p.* FROM phrases p
        LEFT JOIN (
            SELECT phrase_id, MAX(reviewed_at) AS latest
            FROM attempts GROUP BY phrase_id
        ) last ON last.phrase_id = p.id
        LEFT JOIN attempts a ON a.phrase_id = p.id AND a.reviewed_at = last.latest
        WHERE a.next_due_at IS NULL OR a.next_due_at <= :nowMillis
        """
    )
    fun observeDueToday(nowMillis: Long): Flow<List<PhraseEntity>>

    /** Feeds the weekly conversation-challenge card: whatever pack the learner has
     *  actually been practicing lately, so the challenge stays relevant. */
    @Query(
        """
        SELECT pk.theme FROM attempts a
        JOIN phrases p ON p.id = a.phrase_id
        JOIN packs pk ON pk.id = p.pack_id
        ORDER BY a.reviewed_at DESC LIMIT 1
        """
    )
    suspend fun mostRecentlyPracticedTheme(): String?
}

@Dao
interface ChallengeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(challenge: WeeklyChallengeEntity)

    @Query("SELECT * FROM weekly_challenges WHERE week_start = :weekStart LIMIT 1")
    suspend fun getForWeek(weekStart: Long): WeeklyChallengeEntity?

    @Query("UPDATE weekly_challenges SET completed = 1, updated_at = :now WHERE id = :id")
    suspend fun markCompleted(id: String, now: Long)
}
