package com.root.app.practice

import androidx.room.withTransaction
import com.root.app.data.AppDatabase
import com.root.app.data.AttemptEntity
import com.root.app.data.ConfidenceLevel
import com.root.app.data.ContentAccess
import com.root.app.data.PhraseEntity
import com.root.app.data.PracticeEndReason
import com.root.app.data.PracticeQueueEntryEntity
import com.root.app.data.PracticeSessionEntity
import com.root.app.data.PracticeSessionStatus
import com.root.app.data.QueueEntryState
import com.root.app.data.Scheduler

/** One page's worth of due phrases queued per run — genuinely bounded, unlike the
 *  previous unbounded in-memory `SessionQueue`. */
private const val PAGE_SIZE = 200

/** A durable snapshot of one queued occurrence, reconstructed as a [PhraseEntity] so
 *  existing UI (which renders `PhraseEntity`) needs no redesign, plus the entry id
 *  callers must pass back to [PracticeRepository.rate]. */
data class PracticeCardSnapshot(
    val entryId: String,
    val phrase: PhraseEntity,
)

/** The full observable state of one practice run, rebuilt from Room after every
 *  mutation so [com.root.app.RootViewModel] never has to reason about the queue
 *  itself — only about what to show next. */
data class PracticeSessionState(
    val sessionId: String,
    val languageId: String,
    val packId: String?,
    val status: PracticeSessionStatus,
    val current: PracticeCardSnapshot?,
    val correctCount: Int,
    val turn: Int,
    val hasMoreAfterPage: Boolean,
)

/** Outcome of [PracticeRepository.rate], letting the caller distinguish a normal
 *  commit from the edge cases a durable, resumable queue actually has to handle. */
sealed interface PracticeRateResult {
    data class Committed(val state: PracticeSessionState) : PracticeRateResult
    /** The entry was already RATED — replaying the same rate call (e.g. a retried
     *  network-free but duplicated UI event) is a no-op, not a double count. */
    data class AlreadyCommitted(val state: PracticeSessionState) : PracticeRateResult
    /** The entry's content became inaccessible (pack locked/removed) since it was
     *  queued; it is marked SKIPPED instead of RATED and never shown again. */
    data class Unavailable(val state: PracticeSessionState) : PracticeRateResult
    /** The run was already Stopped/Closed/scope-changed; nothing is recorded. */
    object SessionEnded : PracticeRateResult
}

/**
 * Room-backed replacement for the previous in-memory `SessionQueue` plus
 * Bundle-based `SavedStateHandle` restoration. A practice run ([PracticeSessionEntity])
 * now survives process death and app restarts with no 24-hour expiry, and every
 * mutation ([rate], [stop]) commits inside a single transaction so a crash mid-rating
 * can never corrupt the run or double-count an attempt.
 *
 * [premium] and [rewardUnlocked] are read fresh on every access/rate check (not
 * snapshotted) so an entitlement change mid-run takes effect immediately.
 */
class PracticeRepository(
    private val db: AppDatabase,
    private val premium: () -> Boolean,
    private val rewardUnlocked: () -> Boolean,
) {
    private val dao = db.practiceDao()

    /** Resumes the single open run for this language/pack scope if one exists,
     *  otherwise starts a fresh one. Switching language or pack is a different
     *  scope, so it never resumes a stale run from another scope. */
    suspend fun beginOrResume(languageId: String, packId: String?): PracticeSessionState? = db.withTransaction {
        val open = dao.getOpenSession(languageId, packId)
        if (open != null) return@withTransaction buildState(open)
        val session = PracticeSessionEntity(
            languageId = languageId,
            packId = packId,
            status = PracticeSessionStatus.ACTIVE,
            startedAt = System.currentTimeMillis(),
        )
        dao.insertSession(session)
        val added = fillPage(session)
        if (!added) return@withTransaction null
        buildState(dao.getSession(session.id)!!)
    }

    /** Fetches another page of due phrases into an already-open, page-paused run. */
    suspend fun continueSession(sessionId: String): PracticeSessionState? = db.withTransaction {
        val session = dao.getSession(sessionId) ?: return@withTransaction null
        if (session.status == PracticeSessionStatus.ENDED) return@withTransaction buildState(session)
        fillPage(session)
        buildState(dao.getSession(sessionId)!!)
    }

    suspend fun state(sessionId: String): PracticeSessionState? = db.withTransaction {
        dao.getSession(sessionId)?.let { buildState(it) }
    }

    suspend fun hasMoreDue(sessionId: String): Boolean {
        val session = dao.getSession(sessionId) ?: return false
        val unlocked = ContentAccess.unlockedPackIds(db, session.languageId, premium(), rewardUnlocked())
        if (unlocked.isEmpty()) return false
        val existing = dao.phraseIdsInSession(sessionId).toSet()
        val due = db.attemptDao().dueForLanguagePage(
            languageId = session.languageId,
            nowMillis = System.currentTimeMillis(),
            unlockedPackIds = unlocked,
            packId = session.packId,
            limit = PAGE_SIZE + existing.size,
        )
        return due.any { it.id !in existing }
    }

    /** A single bounded lookup for the widget's one-row preview — never loads a
     *  language's full due list just to show one card. */
    suspend fun nextDuePhrase(languageId: String, packId: String?): PhraseEntity? {
        val unlocked = ContentAccess.unlockedPackIds(db, languageId, premium(), rewardUnlocked())
        if (unlocked.isEmpty()) return null
        return db.attemptDao().dueForLanguagePage(
            languageId = languageId,
            nowMillis = System.currentTimeMillis(),
            unlockedPackIds = unlocked,
            packId = packId,
            limit = 1,
        ).firstOrNull()
    }

    /**
     * Records an outcome for [entryId] and advances the run. A Missed outcome
     * appends exactly one tail retry for that phrase (never more than one, and
     * never for a phrase already retried in this run). Rechecks access and Stop/
     * Close status at commit time rather than trusting the original snapshot.
     */
    suspend fun rate(sessionId: String, entryId: String, outcome: ConfidenceLevel): PracticeRateResult = db.withTransaction {
        val session = dao.getSession(sessionId) ?: return@withTransaction PracticeRateResult.SessionEnded
        // Checked before the entry's own state: Stop/Close can end a run while its
        // current entry is still PENDING, and an ended run must never be resumed.
        if (session.status == PracticeSessionStatus.ENDED) return@withTransaction PracticeRateResult.SessionEnded
        val entry = dao.getEntry(entryId) ?: return@withTransaction PracticeRateResult.SessionEnded
        if (entry.sessionId != sessionId) return@withTransaction PracticeRateResult.SessionEnded
        if (entry.state != QueueEntryState.PENDING) {
            return@withTransaction PracticeRateResult.AlreadyCommitted(buildState(session))
        }

        val phrase = db.phraseDao().getById(entry.phraseId)
        val pack = phrase?.let { db.packDao().getById(it.packId) }
        val language = pack?.let { db.languageDao().getById(it.languageId) }
        val accessible = phrase != null && pack != null && language != null &&
            ContentAccess.canAccess(language, pack, premium(), rewardUnlocked())
        if (!accessible) {
            dao.updateEntryState(entry.id, QueueEntryState.SKIPPED)
            pausePageIfComplete(sessionId)
            return@withTransaction PracticeRateResult.Unavailable(buildState(dao.getSession(sessionId)!!))
        }

        val now = System.currentTimeMillis()
        // Reusing the queue entry's own id as the attempt id is what makes this
        // idempotent: a replayed rate() for an already-RATED entry short-circuits
        // above instead of reaching this insert.
        db.attemptDao().insert(
            AttemptEntity(
                id = entry.id,
                phraseId = entry.phraseId,
                confidence = outcome,
                reviewedAt = now,
                nextDueAt = Scheduler.nextDueAt(outcome, now),
            )
        )
        dao.updateEntryState(entry.id, QueueEntryState.RATED)

        if (outcome == ConfidenceLevel.MISSED && !entry.isRetry && dao.retryCountForOrigin(sessionId, entry.id) == 0) {
            val position = (dao.maxPosition(sessionId) ?: entry.position) + 1
            dao.insertEntry(
                PracticeQueueEntryEntity(
                    sessionId = sessionId,
                    phraseId = entry.phraseId,
                    packIdSnapshot = entry.packIdSnapshot,
                    promptSnapshot = entry.promptSnapshot,
                    answerSnapshot = entry.answerSnapshot,
                    audioSnapshot = entry.audioSnapshot,
                    phraseRevision = entry.phraseRevision,
                    position = position,
                    isRetry = true,
                    originEntryId = entry.id,
                    state = QueueEntryState.PENDING,
                )
            )
            dao.updateSession(sessionId, session.status, session.endedAt, session.endReason, position, System.currentTimeMillis())
        }

        pausePageIfComplete(sessionId)
        PracticeRateResult.Committed(buildState(dao.getSession(sessionId)!!))
    }

    /** Durably ends a run. Idempotent: ending an already-ended run is a no-op that
     *  still reports success, since the end state the caller wanted already holds. */
    suspend fun stop(sessionId: String, reason: PracticeEndReason): Boolean = db.withTransaction {
        val session = dao.getSession(sessionId) ?: return@withTransaction false
        if (session.status != PracticeSessionStatus.ENDED) {
            dao.updateSession(
                sessionId,
                PracticeSessionStatus.ENDED,
                System.currentTimeMillis(),
                reason,
                session.nextPosition,
                System.currentTimeMillis(),
            )
        }
        true
    }

    /** Re-checks the current pending entry's access and skips past any that have
     *  become inaccessible since being queued, without waiting for a rate() call. */
    suspend fun revalidateCurrent(sessionId: String): PracticeSessionState? = db.withTransaction {
        val session = dao.getSession(sessionId) ?: return@withTransaction null
        var entry = dao.nextPending(sessionId)
        while (entry != null) {
            val phrase = db.phraseDao().getById(entry.phraseId)
            val pack = phrase?.let { db.packDao().getById(it.packId) }
            val language = pack?.let { db.languageDao().getById(it.languageId) }
            val accessible = phrase != null && pack != null && language != null &&
                ContentAccess.canAccess(language, pack, premium(), rewardUnlocked())
            if (accessible) break
            dao.updateEntryState(entry.id, QueueEntryState.SKIPPED)
            entry = dao.nextPending(sessionId)
        }
        pausePageIfComplete(sessionId)
        buildState(dao.getSession(sessionId)!!)
    }

    private suspend fun pausePageIfComplete(sessionId: String) {
        val session = dao.getSession(sessionId) ?: return
        if (session.status == PracticeSessionStatus.ACTIVE && dao.pendingCount(sessionId) == 0) {
            dao.updateSession(
                sessionId,
                PracticeSessionStatus.PAGE_PAUSED,
                session.endedAt,
                session.endReason,
                session.nextPosition,
                System.currentTimeMillis(),
            )
        }
    }

    /** Loads one page of due phrases (skipping any phrase already queued this run,
     *  so continuing a page never re-queues a phrase mid-run) and appends them as
     *  base entries. Returns whether any entries were added. */
    private suspend fun fillPage(session: PracticeSessionEntity): Boolean {
        val unlocked = ContentAccess.unlockedPackIds(db, session.languageId, premium(), rewardUnlocked())
        if (unlocked.isEmpty()) return false
        val existing = dao.phraseIdsInSession(session.id).toSet()
        val due = db.attemptDao().dueForLanguagePage(
            languageId = session.languageId,
            nowMillis = System.currentTimeMillis(),
            unlockedPackIds = unlocked,
            packId = session.packId,
            limit = PAGE_SIZE + existing.size,
        ).filter { it.id !in existing }.take(PAGE_SIZE)
        if (due.isEmpty()) return false
        var position = session.nextPosition
        val entries = due.map { phrase ->
            PracticeQueueEntryEntity(
                sessionId = session.id,
                phraseId = phrase.id,
                packIdSnapshot = phrase.packId,
                promptSnapshot = phrase.prompt,
                answerSnapshot = phrase.answer,
                audioSnapshot = phrase.audioAsset,
                phraseRevision = phrase.updatedAt,
                position = position++,
                isRetry = false,
                originEntryId = null,
                state = QueueEntryState.PENDING,
            )
        }
        dao.insertEntries(entries)
        dao.updateSession(session.id, PracticeSessionStatus.ACTIVE, null, null, position, System.currentTimeMillis())
        return true
    }

    private suspend fun buildState(session: PracticeSessionEntity): PracticeSessionState {
        // An ended run never shows a card again, even if it still has PENDING
        // entries (Stop/Close can end a run mid-page) — see PracticeSessionStatus.
        val entry = if (session.status == PracticeSessionStatus.ENDED) null else dao.nextPending(session.id)
        val current = entry?.let {
            PracticeCardSnapshot(
                entryId = it.id,
                phrase = PhraseEntity(
                    id = it.phraseId,
                    packId = it.packIdSnapshot,
                    prompt = it.promptSnapshot,
                    answer = it.answerSnapshot,
                    audioAsset = it.audioSnapshot,
                    updatedAt = it.phraseRevision,
                ),
            )
        }
        val turn = dao.ratedCount(session.id)
        val correct = dao.correctCount(session.id)
        val hasMoreAfterPage = session.status != PracticeSessionStatus.ENDED && hasMoreDue(session.id)
        return PracticeSessionState(
            sessionId = session.id,
            languageId = session.languageId,
            packId = session.packId,
            status = session.status,
            current = current,
            correctCount = correct,
            turn = turn,
            hasMoreAfterPage = hasMoreAfterPage,
        )
    }
}
