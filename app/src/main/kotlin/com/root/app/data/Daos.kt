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

    @Query("SELECT * FROM phrases WHERE pack_id = :packId AND NOT EXISTS (SELECT 1 FROM managed_phrases m WHERE m.phrase_id = phrases.id AND m.retired = 1) ORDER BY id")
    suspend fun getForPack(packId: String): List<PhraseEntity>

    @Query("SELECT * FROM phrases WHERE id = :id")
    suspend fun getById(id: String): PhraseEntity?

    @Query("SELECT * FROM phrases WHERE pack_id = :packId")
    fun observeForPack(packId: String): Flow<List<PhraseEntity>>

    @Query("SELECT COUNT(*) FROM phrases WHERE pack_id = :packId AND NOT EXISTS (SELECT 1 FROM managed_phrases m WHERE m.phrase_id = phrases.id AND m.retired = 1)")
    suspend fun countForPack(packId: String): Int
}

@Dao
interface AttemptDao {
    @Insert
    suspend fun insert(attempt: AttemptEntity)

    @Query("SELECT * FROM attempts WHERE id = :id")
    suspend fun getById(id: String): AttemptEntity?

    /** The due queue for a session: every phrase in an unlocked pack whose latest
     *  attempt is due (or has no attempt yet). See [DUE_PHRASES_QUERY]. */
    @Query(DUE_PHRASES_QUERY)
    suspend fun dueForLanguage(
        languageId: String,
        nowMillis: Long,
        unlockedPackIds: List<String>,
        packId: String? = null,
    ): List<PhraseEntity>

    /** Same ordering as [dueForLanguage] but genuinely bounded — used to build one
     *  page of a practice run (and by the widget's single-row preview) instead of
     *  ever loading a language's entire due list into memory. */
    /** Same as [dueForLanguagePage] but excludes phrases already queued in
     *  [sessionId] via a SQL subquery instead of the caller loading every
     *  already-queued phrase id into memory and inflating the page limit — so a
     *  session's per-page query cost stays bounded by [limit] regardless of how
     *  many phrases the run has already queued over its lifetime. */
    @Query(
        "$DUE_PHRASES_WHERE " +
            "AND p.id NOT IN (SELECT phrase_id FROM practice_queue_entries WHERE session_id = :sessionId) " +
            "$DUE_PHRASES_ORDER LIMIT :limit",
    )
    suspend fun dueForLanguagePageExcludingSession(
        sessionId: String,
        languageId: String,
        nowMillis: Long,
        unlockedPackIds: List<String>,
        packId: String? = null,
        limit: Int,
    ): List<PhraseEntity>

    @Query("$DUE_PHRASES_QUERY LIMIT :limit")
    suspend fun dueForLanguagePage(
        languageId: String,
        nowMillis: Long,
        unlockedPackIds: List<String>,
        packId: String? = null,
        limit: Int,
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
//
// Ordering deliberately puts already-scheduled reviews (a real next_due_at) ahead of
// never-attempted phrases (NULL next_due_at). Coalescing NULL to 0 would rank unseen
// phrases first every time, starving overdue reviews behind an ever-growing catalog
// of first exposures — see the plan's "do not starve due reviews behind unseen cards"
// rule. Due reviews are then ordered most-overdue-first; unseen phrases follow in a
// stable editorial order.
internal const val DUE_PHRASES_WHERE = """
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
        AND NOT EXISTS (SELECT 1 FROM managed_phrases m WHERE m.phrase_id = p.id AND m.retired = 1)
        AND (a.next_due_at IS NULL OR a.next_due_at <= :nowMillis)
"""
internal const val DUE_PHRASES_ORDER = """
    ORDER BY CASE WHEN a.next_due_at IS NULL THEN 1 ELSE 0 END, a.next_due_at, pk.sortOrder, p.id
"""
internal const val DUE_PHRASES_QUERY = "$DUE_PHRASES_WHERE $DUE_PHRASES_ORDER"

/**
 * Backs [com.root.app.practice.PracticeRepository]. All mutation happens inside
 * that repository's `withTransaction` blocks; this DAO only exposes the raw reads
 * and single-row writes needed there.
 */
@Dao
interface PracticeDao {
    @Insert
    suspend fun insertSession(session: PracticeSessionEntity)

    @Query("UPDATE practice_sessions SET status = :status, ended_at = :endedAt, end_reason = :endReason, next_position = :nextPosition, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSession(
        id: String,
        status: PracticeSessionStatus,
        endedAt: Long?,
        endReason: PracticeEndReason?,
        nextPosition: Int,
        updatedAt: Long,
    )

    @Query("SELECT * FROM practice_sessions WHERE id = :id")
    suspend fun getSession(id: String): PracticeSessionEntity?

    /** The single open (non-ENDED) run for a language/pack scope, if any — the
     *  basis for "resume, don't reset" on relaunch/widget-tap. `packId IS :packId`
     *  correctly matches NULL == NULL for the "any pack" scope, unlike `=`. */
    @Query(
        """
        SELECT * FROM practice_sessions
        WHERE language_id = :languageId AND status != 'ENDED' AND (pack_id IS :packId)
        ORDER BY started_at DESC LIMIT 1
        """
    )
    suspend fun getOpenSession(languageId: String, packId: String?): PracticeSessionEntity?

    @Insert
    suspend fun insertEntries(entries: List<PracticeQueueEntryEntity>)

    @Insert
    suspend fun insertEntry(entry: PracticeQueueEntryEntity)

    @Query("UPDATE practice_queue_entries SET state = :state WHERE id = :id")
    suspend fun updateEntryState(id: String, state: QueueEntryState)

    @Query("SELECT * FROM practice_queue_entries WHERE id = :id")
    suspend fun getEntry(id: String): PracticeQueueEntryEntity?

    @Query(
        """
        SELECT * FROM practice_queue_entries
        WHERE session_id = :sessionId AND state = 'PENDING'
        ORDER BY position LIMIT 1
        """
    )
    suspend fun nextPending(sessionId: String): PracticeQueueEntryEntity?

    @Query("SELECT COUNT(*) FROM practice_queue_entries WHERE session_id = :sessionId AND state = 'PENDING'")
    suspend fun pendingCount(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM practice_queue_entries WHERE session_id = :sessionId AND state = 'RATED'")
    suspend fun ratedCount(sessionId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM practice_queue_entries e
        JOIN attempts a ON a.id = e.id
        WHERE e.session_id = :sessionId AND e.state = 'RATED' AND a.confidence = 'GOT_IT'
        """
    )
    suspend fun correctCount(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM practice_queue_entries WHERE session_id = :sessionId AND is_retry = 0 AND phrase_id = :phraseId")
    suspend fun baseEntryCountForPhrase(sessionId: String, phraseId: String): Int

    @Query("SELECT COUNT(*) FROM practice_queue_entries WHERE session_id = :sessionId AND origin_entry_id = :originEntryId")
    suspend fun retryCountForOrigin(sessionId: String, originEntryId: String): Int

    @Query("SELECT MAX(position) FROM practice_queue_entries WHERE session_id = :sessionId")
    suspend fun maxPosition(sessionId: String): Int?
}

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
