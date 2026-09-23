package com.root.app.data

import android.content.Context
import androidx.room.withTransaction
import com.root.app.billing.EntitlementStore
import com.root.app.practice.PracticeRateResult
import com.root.app.practice.PracticeRepository
import com.root.app.practice.PracticeSessionState
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Single point of access to Room, [RootPreferences], and [EntitlementStore]. UI code
 * and [com.root.app.RootViewModel] go through this class rather than touching the
 * database or preferences directly, so access rules ([ContentAccess]) and locked-pack
 * text are enforced in one place ([phrases], [phrase], [practice]).
 */
class RootRepository(context: Context) {
    private val context = context.applicationContext
    val db: AppDatabase = AppDatabase.get(this.context)
    private val preferences = RootPreferences(this.context)
    private val access = EntitlementStore(this.context)

    /** Durable practice-run backend — see [PracticeRepository]. Lazily created since
     *  it only needs [db] plus fresh entitlement reads, both already available here. */
    val practice: PracticeRepository by lazy {
        PracticeRepository(
            db = db,
            premium = { access.isPremium() },
            rewardUnlocked = { ReferralPrefs.hasUnlockedReward(this.context) },
        )
    }

    suspend fun beginOrResumePractice(languageId: String, packId: String? = null): PracticeSessionState? =
        practice.beginOrResume(languageId, packId)

    suspend fun continuePractice(sessionId: String): PracticeSessionState? =
        practice.continueSession(sessionId)

    suspend fun ratePractice(sessionId: String, entryId: String, outcome: ConfidenceLevel): PracticeRateResult =
        practice.rate(sessionId, entryId, outcome)

    suspend fun stopPractice(sessionId: String, reason: PracticeEndReason): Boolean =
        practice.stop(sessionId, reason)

    suspend fun revalidatePractice(sessionId: String): PracticeSessionState? =
        practice.revalidateCurrent(sessionId)

    suspend fun practiceState(sessionId: String): PracticeSessionState? =
        practice.state(sessionId)

    suspend fun nextDuePhrase(languageId: String, packId: String? = null): PhraseEntity? =
        practice.nextDuePhrase(languageId, packId)

    suspend fun practiceReviewedDetails(sessionId: String, limit: Int = 200, offset: Int = 0): List<PracticeReviewedDetail> =
        practice.reviewedDetails(sessionId, limit, offset)

    suspend fun earliestEligibleDueAt(languageId: String, packId: String? = null): Long? =
        practice.earliestEligibleDueAt(languageId, packId)

    suspend fun initialize() {
        com.root.app.content.ContentBuildPolicy.apply(db)
        SeedData.seedIfEmpty(db)
        val languages = languages()
        if (languages.none { it.id == activeLanguageId() }) {
            preferences.activeLanguageId = languages.firstOrNull { !it.isPremium }?.id
                ?: languages.firstOrNull()?.id
        }
    }

    suspend fun languages(): List<LanguageEntity> = db.languageDao().getAll()

    fun activeLanguageId(): String? = preferences.activeLanguageId

    fun setActiveLanguage(id: String) {
        preferences.activeLanguageId = id
    }

    fun getTheme(): String = preferences.theme

    fun setTheme(theme: String) {
        preferences.theme = theme
    }

    /** Whether the optional onboarding/menu overview should be offered on
     *  this launch — see [com.root.app.overview.OnboardingGate]. */
    fun shouldOfferOnboarding(): Boolean =
        com.root.app.overview.OnboardingGate.shouldOffer(preferences.onboardingCompletedVersion, RootPreferences.ONBOARDING_CURRENT_VERSION)

    /** Persists that the learner has responded to onboarding — by finishing
     *  it or by explicitly skipping; both are recorded identically, so it is
     *  never re-offered again until [RootPreferences.ONBOARDING_CURRENT_VERSION] changes. */
    fun recordOnboardingResponse() {
        preferences.onboardingCompletedVersion =
            com.root.app.overview.OnboardingGate.versionAfterResponse(RootPreferences.ONBOARDING_CURRENT_VERSION)
    }

    suspend fun packs(languageId: String): List<PackEntity> =
        db.packDao().getForLanguage(languageId)

    /** Locked pack metadata may be listed; its phrase text is never returned. */
    suspend fun phrases(packId: String): List<PhraseEntity> {
        val pack = requireNotNull(db.packDao().getById(packId)) { "Unknown pack: $packId" }
        return if (canAccess(pack, access.isPremium())) {
            db.phraseDao().getForPack(packId)
        } else {
            emptyList()
        }
    }

    /** A removed or currently locked phrase is unavailable for restoration/widget use. */
    suspend fun phrase(id: String): PhraseEntity? {
        require(id.isNotBlank()) { "Phrase ID cannot be blank." }
        val phrase = db.phraseDao().getById(id) ?: return null
        if (!ManagedContentAccess.isEligible(db.contentDao().getManagedPhrase(id))) return null
        val pack = requireNotNull(db.packDao().getById(phrase.packId)) { "Phrase pack is missing." }
        return phrase.takeIf { canAccess(pack, access.isPremium()) }
    }

    suspend fun capabilityCount(languageId: String): Int =
        db.attemptDao().capabilityCount(languageId)

    suspend fun weeklyChallenge(languageId: String): WeeklyChallengeEntity = db.withTransaction {
        requireNotNull(db.languageDao().getById(languageId)) { "Unknown language: $languageId" }
        val weekLength = TimeUnit.DAYS.toMillis(7)
        val now = System.currentTimeMillis()
        val weekStart = Math.floorDiv(now, weekLength) * weekLength
        // Language-scoped stable IDs avoid a schema change and preserve old challenges.
        val id = "challenge-$languageId-$weekStart"
        db.challengeDao().getById(id)?.let { return@withTransaction it }
        // The old app only had Dholuo and used unscoped random challenge IDs.
        val legacy = if (languageId == "lang-dholuo") db.challengeDao().getLegacyForWeek(weekStart) else null
        val unlockedIds = unlockedPackIds(languageId, access.isPremium())
        val theme = db.attemptDao().mostRecentlyPracticedTheme(languageId, unlockedIds)
            ?: packs(languageId).firstOrNull { it.id in unlockedIds }?.theme
            ?: "Your words"
        val challenge = legacy?.copy(id = id)
            ?: WeeklyChallengeEntity(id = id, weekStart = weekStart, theme = theme)
        db.challengeDao().insertMissing(challenge)
        checkNotNull(db.challengeDao().getById(id))
    }

    suspend fun completeChallenge(id: String) {
        require(db.challengeDao().markCompleted(id, System.currentTimeMillis()) == 1) {
            "Unknown challenge: $id"
        }
    }

    /**
     * [draftId] optionally names the durable [ContributionDraftEntity] this
     * contribution came from — when provided, that draft is atomically marked
     * [ContributionAudioState.COMMITTED] with [ContributionDraftEntity.committedPhraseId]
     * set to the new phrase's id, in the *same* transaction as the phrase/consent
     * insert below. A process death between promoting the draft's temp audio
     * file and this commit leaves the draft still open (never silently
     * half-committed), matching [MediaFileFactEntity]'s promotion-vs-activation
     * split — see docs/TEACHING_CONTRACTS.md. An unknown or already-terminal
     * [draftId] is a caller error and rejected rather than silently ignored.
     */
    suspend fun contribute(
        languageName: String,
        prompt: String,
        answer: String,
        audioPath: String?,
        speakerLabel: String? = null,
        consentConfirmed: Boolean = false,
        draftId: String? = null,
    ): PhraseEntity {
        val name = languageName.trim()
        val cleanPrompt = prompt.trim()
        val cleanAnswer = answer.trim()
        require(name.isNotEmpty()) { "Language name is required." }
        require(cleanPrompt.isNotEmpty()) { "Prompt is required." }
        require(cleanAnswer.isNotEmpty()) { "Answer is required." }
        val audio = audioPath?.let {
            require(it.isNotBlank()) { "Recording path cannot be blank." }
            val file = File(it).canonicalFile
            require(file.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && file.isFile) {
                "Reference recording must be an existing app-local file."
            }
            // A reference recording is always of a real person; it can never be
            // attached without an explicit, per-save confirmation that this
            // learner has that person's permission to record and keep it on
            // this device. Consent covers this local recording only.
            require(consentConfirmed) {
                "Recording someone else requires confirming you have their permission first."
            }
            file.path
        }
        return db.withTransaction {
            val draft = draftId?.let {
                val existing = requireNotNull(db.contributionDraftDao().getById(it)) { "Unknown draft: $it" }
                require(existing.audioState != ContributionAudioState.COMMITTED && existing.audioState != ContributionAudioState.DISCARDED) {
                    "This draft is no longer open."
                }
                existing
            }
            val language = languages().firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: LanguageEntity(name = name, isPremium = false).also {
                    db.languageDao().insertMissing(listOf(it))
                }
            val pack = PackEntity(
                id = ContentAccess.userPackId(language.id),
                languageId = language.id,
                theme = "Your words",
                sortOrder = Int.MAX_VALUE,
                isFree = true,
            )
            db.packDao().insertMissing(listOf(pack))
            val phrase = PhraseEntity(
                packId = pack.id,
                prompt = cleanPrompt,
                answer = cleanAnswer,
                audioAsset = audio,
            )
            db.phraseDao().upsertAll(listOf(phrase))
            if (audio != null) {
                db.consentDao().upsert(
                    PhraseConsentEntity(
                        phraseId = phrase.id,
                        speakerLabel = speakerLabel?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                )
            }
            draft?.let {
                db.contributionDraftDao().upsert(
                    it.copy(audioState = ContributionAudioState.COMMITTED, committedPhraseId = phrase.id, updatedAt = System.currentTimeMillis()),
                )
            }
            phrase
        }
    }

    /** The learner's own "Your words" archive for [languageId] — never a
     *  catalog/pack-managed phrase — optionally filtered by [query] (matched
     *  against either side of the card, case-insensitive). Backs the archive
     *  screen's list + search. */
    fun personalPhrases(languageId: String, query: String = ""): kotlinx.coroutines.flow.Flow<List<PhraseEntity>> =
        db.phraseDao().observePersonal(ContentAccess.userPackId(languageId), query.trim())

    suspend fun consentFor(phraseId: String): PhraseConsentEntity? = db.consentDao().getForPhrase(phraseId)

    /** Edits a personal phrase's text in place. Never touches practice/attempt
     *  history or the recording — only [deletePersonalPhrase] does that. Rejects
     *  any phrase that is not actually in this language's "Your words" pack, so
     *  a catalog-managed phrase can never be silently rewritten through this path. */
    suspend fun updatePersonalPhrase(languageId: String, phraseId: String, prompt: String, answer: String) {
        val cleanPrompt = prompt.trim()
        val cleanAnswer = answer.trim()
        require(cleanPrompt.isNotEmpty()) { "Prompt is required." }
        require(cleanAnswer.isNotEmpty()) { "Answer is required." }
        db.withTransaction {
            val existing = requireNotNull(db.phraseDao().getById(phraseId)) { "Unknown phrase: $phraseId" }
            require(existing.packId == ContentAccess.userPackId(languageId)) {
                "Only a personally-contributed phrase can be edited here."
            }
            db.phraseDao().upsertAll(listOf(existing.copy(prompt = cleanPrompt, answer = cleanAnswer, updatedAt = System.currentTimeMillis())))
        }
    }

    /** The learner's private note for a personal phrase, if any — never shown
     *  as a public credit and never copied into a shared card (see
     *  [com.root.app.sharing.PhraseCardSharing]). */
    suspend fun noteFor(phraseId: String): PersonalNoteEntity? = db.personalNoteDao().getForPhrase(phraseId)

    /** Sets or replaces the private note for [phraseId]. Blank text deletes the
     *  note entirely rather than storing an empty row — matching "delete a
     *  person label without deleting their words" for the notes case. */
    suspend fun setNote(phraseId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            db.personalNoteDao().deleteForPhrase(phraseId)
        } else {
            db.personalNoteDao().upsert(PersonalNoteEntity(phraseId = phraseId, noteText = clean))
        }
    }

    /** The learner's most recent self-reported "practiced this out loud"
     *  timestamp for [phraseId], if they have ever tapped Mark Practiced —
     *  null if never marked. Purely informational; never affects eligibility
     *  or scheduling. */
    suspend fun lastPracticedMarkAt(phraseId: String): Long? = db.practiceMarkDao().getForPhrase(phraseId)?.markedAt

    /** Idempotently records that the learner says they practiced [phraseId]
     *  out loud just now. Tapping this any number of times leaves exactly one
     *  row (see [PracticeMarkEntity]) and — critically — never creates an
     *  [AttemptEntity] and never touches the phrase's recall schedule. This is
     *  a self-reported acknowledgement channel, not a graded attempt. */
    suspend fun markPracticed(phraseId: String) {
        db.practiceMarkDao().upsert(PracticeMarkEntity(phraseId = phraseId, markedAt = System.currentTimeMillis()))
    }


    /** Every durable contribution draft not yet committed or discarded, most
     *  recently updated first — offers a resumed Contribute screen a
     *  "continue where you left off" list instead of silently discarding a
     *  draft that survived process death. */
    suspend fun openDrafts(): List<ContributionDraftEntity> = db.contributionDraftDao().getOpenDrafts()

    suspend fun draft(id: String): ContributionDraftEntity? = db.contributionDraftDao().getById(id)

    /** Creates a brand-new empty draft for [languageId]/[packId] (personal
     *  words have no fixed pack until commit, so [packId] is typically null)
     *  and returns its durable id. The caller persists this id (not the text
     *  itself) so subsequent [saveDraftText]/[saveDraftAudio] calls always
     *  target the same durable row regardless of process recreation. */
    suspend fun createDraft(languageId: String): ContributionDraftEntity {
        val draft = ContributionDraftEntity(languageId = languageId, packId = null, promptDraft = "", answerDraft = "", speakerLabelDraft = null, audioDraftPath = null, committedPhraseId = null)
        db.contributionDraftDao().upsert(draft)
        return draft
    }

    /** Updates a still-open draft's typed text in place. Rejects a draft that
     *  has already been committed or discarded — those are terminal. */
    suspend fun saveDraftText(draftId: String, prompt: String, answer: String, speakerLabel: String?) {
        val existing = requireNotNull(db.contributionDraftDao().getById(draftId)) { "Unknown draft: $draftId" }
        require(existing.audioState != ContributionAudioState.COMMITTED && existing.audioState != ContributionAudioState.DISCARDED) {
            "This draft is no longer open."
        }
        db.contributionDraftDao().upsert(
            existing.copy(
                promptDraft = prompt,
                answerDraft = answer,
                speakerLabelDraft = speakerLabel,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Explicitly discards a draft — its typed text/audio state is gone, but
     *  this never touches an already-committed phrase (a committed draft
     *  cannot be discarded; discard only a draft that never became a phrase). */
    suspend fun discardDraft(draftId: String) {
        val existing = db.contributionDraftDao().getById(draftId) ?: return
        require(existing.audioState != ContributionAudioState.COMMITTED) { "A committed draft cannot be discarded." }
        db.contributionDraftDao().upsert(existing.copy(audioState = ContributionAudioState.DISCARDED, updatedAt = System.currentTimeMillis()))
    }



    /**
     * Permanently deletes a personally-contributed phrase: its text, its
     * [PhraseConsentEntity], its [PersonalNoteEntity], and its full
     * [AttemptEntity] practice history (all `ON DELETE CASCADE`). Its on-disk
     * recording is removed too, but a failed removal is durably recorded as
     * [MediaFileStatus.PENDING_CLEANUP] (see [recordDeletionOutcome]/
     * [retryPendingMediaCleanup]) rather than silently reported as done. This
     * is irreversible by design — the caller (archive UI) must have already
     * confirmed with the learner before calling this. Rejects any phrase that
     * is not actually in this language's "Your words" pack, so a
     * catalog-managed phrase (whose retirement/lifecycle is entirely
     * different — see [ManagedContentAccess]) can never be deleted through
     * this path.
     */
    suspend fun deletePersonalPhrase(languageId: String, phraseId: String) {
        val recordingPath = db.withTransaction {
            val existing = requireNotNull(db.phraseDao().getById(phraseId)) { "Unknown phrase: $phraseId" }
            require(existing.packId == ContentAccess.userPackId(languageId)) {
                "Only a personally-contributed phrase can be deleted here."
            }
            // Row delete cascades to attempts + consent record + personal note
            // inside this same transaction; the on-disk file is removed after
            // commit below, tracked separately (see below) so a failed delete
            // is reported as cleanup pending rather than claimed as erased.
            db.phraseDao().deleteById(phraseId)
            existing.audioAsset
        }
        recordingPath?.let { path -> recordDeletionOutcome(MediaFileSubject.PHRASE_REFERENCE_AUDIO, phraseId, path) }
    }

    /** Deletes one on-disk media file and durably records the outcome in
     *  [MediaFileFactEntity] rather than assuming success: a failed delete
     *  (permissions, another process holding the file, a crash between the
     *  attempt and confirming it) becomes a `PENDING_CLEANUP` fact instead of
     *  silently vanishing, so [retryPendingMediaCleanup] can finish the job
     *  later instead of leaking the file forever. Rejects any path outside
     *  this app's own storage. */
    private suspend fun recordDeletionOutcome(subject: MediaFileSubject, subjectId: String, path: String) {
        val file = File(path).canonicalFile
        if (!file.toPath().startsWith(context.filesDir.canonicalFile.toPath())) return
        val deleted = !file.exists() || file.delete()
        if (deleted) {
            // Clear any earlier pending-cleanup fact for this same file now that
            // the bytes are actually gone.
            db.mediaFileFactDao().getFor(subject, subjectId).forEach { db.mediaFileFactDao().deleteById(it.id) }
        } else {
            db.mediaFileFactDao().upsert(
                MediaFileFactEntity(
                    subject = subject,
                    subjectId = subjectId,
                    filePath = path,
                    expectedSha256 = null,
                    status = MediaFileStatus.PENDING_CLEANUP,
                ),
            )
        }
    }

    /** Retries every [MediaFileStatus.PENDING_CLEANUP] fact — files a prior
     *  delete could not remove (see [recordDeletionOutcome]) — and clears the
     *  fact once its file is actually gone. Safe to call repeatedly (e.g. on
     *  app start); never touches a file outside this app's storage. Returns
     *  how many facts were resolved this call. */
    suspend fun retryPendingMediaCleanup(): Int {
        var resolved = 0
        db.mediaFileFactDao().getPendingCleanup().forEach { fact ->
            val file = File(fact.filePath).canonicalFile
            val inAppStorage = file.toPath().startsWith(context.filesDir.canonicalFile.toPath())
            val deleted = inAppStorage && (!file.exists() || file.delete())
            if (deleted) {
                db.mediaFileFactDao().deleteById(fact.id)
                resolved++
            }
        }
        return resolved
    }

    private suspend fun unlockedPackIds(languageId: String, premium: Boolean): List<String> =
        ContentAccess.unlockedPackIds(db, languageId, premium, ReferralPrefs.hasUnlockedReward(context))

    private suspend fun canAccess(pack: PackEntity, premium: Boolean): Boolean {
        val language = requireNotNull(db.languageDao().getById(pack.languageId)) { "Pack language is missing." }
        return db.contentDao().getInstalledPack(pack.id)?.status != InstalledPackStatus.RETIRED &&
            ContentAccess.canAccess(language, pack, premium, ReferralPrefs.hasUnlockedReward(context))
    }
}
