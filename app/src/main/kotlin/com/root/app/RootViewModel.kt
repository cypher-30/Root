package com.root.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.root.app.billing.EntitlementStore
import com.root.app.data.*
import com.root.app.practice.PracticeRateResult
import com.root.app.practice.PracticeSessionState
import com.root.app.ui.PackRow
import com.root.app.widget.RootWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import com.root.app.ui.motion.RootMotion

/**
 * Backs [com.root.app.MainActivity]'s navigation host. Owns everything the UI needs
 * to render without touching Room/preferences directly: loading/error state, the
 * active language and its packs, the current practice session, entitlement/reward
 * flags, and appearance.
 *
 * Practice state is durably owned by Room via [RootRepository.practice] (see
 * [com.root.app.practice.PracticeRepository]) — this replaces the previous in-memory
 * `SessionQueue` plus Bundle-based [SavedStateHandle] restoration. Only the small,
 * bounded [sessionId] is kept in [SavedStateHandle]; a process death/recreation
 * simply reloads that session's current state from the database, which is always the
 * source of truth, so there is no 24-hour expiry and no risk of restoring stale or
 * partially-saved in-memory history.
 */
class RootViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = RootRepository(application)
    private val access = EntitlementStore(application)
    // Serializes rate()/stop()/continue() calls: a rapid double-tap/double-swipe must
    // not double-count or race against a concurrent stop.
    private val ratingMutex = Mutex()
    private var sessionId: String?
        get() = saved["session.id"]
        set(value) { saved["session.id"] = value }
    private var sessionPackId: String?
        get() = saved["session.packId"]
        set(value) { saved["session.packId"] = value }
    var loading by mutableStateOf(true)
        private set
    var loadFailed by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var languages by mutableStateOf(emptyList<LanguageEntity>())
        private set
    var personalLanguageIds by mutableStateOf(emptySet<String>())
        private set
    var activeLanguage by mutableStateOf<LanguageEntity?>(null)
        private set
    var rows by mutableStateOf(emptyList<PackRow>())
        private set
    var current by mutableStateOf<PhraseEntity?>(null)
        private set
    private var currentEntryId: String? = null
    var sharePhrase by mutableStateOf<PhraseEntity?>(null)
        private set
    var correct by mutableIntStateOf(0)
        private set
    var turn by mutableIntStateOf(0)
        private set
    var capability by mutableIntStateOf(0)
        private set
    var completed by mutableStateOf(false)
        private set
    // True once a session page completes and re-querying due phrases (excluding those
    // already queued this run) finds more available. Distinct from "completed": there
    // is no fixed session size to reach before offering more.
    var canPracticeMore by mutableStateOf(false)
        private set
    var challenge by mutableStateOf<WeeklyChallengeEntity?>(null)
        private set
    var premium by mutableStateOf(access.isPremium())
        private set
    var reward by mutableStateOf(ReferralPrefs.hasUnlockedReward(application))
        private set
    var theme by mutableStateOf(repository.getTheme())
        private set
    var launched by mutableStateOf(saved["launched"] ?: false)
        private set
    var showOnboarding by mutableStateOf(false)
        private set

    init {
        load()
        showOnboarding = repository.shouldOfferOnboarding()
        viewModelScope.launch {
            access.premium.collect { active -> premium = active }
        }
        viewModelScope.launch {
            ReferralPrefs.observeReward(application).collect { unlocked ->
                reward = unlocked
                updateWidget()
            }
        }
    }

    /** Persists that the learner has finished or explicitly skipped
     *  onboarding (see [RootRepository.recordOnboardingResponse] — both are
     *  recorded identically) and dismisses it for this and future launches
     *  until the version is bumped. */
    fun respondToOnboarding() {
        repository.recordOnboardingResponse()
        showOnboarding = false
    }

    /** A deterministic snapshot of current state for
     *  [com.root.app.overview.OverviewRecommendations.recommend] — see that
     *  object for what each field actually gates. [dueCount] is a coarse
     *  0-or-1 proxy (whether *any* phrase is currently due), not an exact
     *  count: no repository query yet returns an exact due tally without
     *  loading the full due list, which recommend() does not need. */
    suspend fun overviewSnapshot(): com.root.app.overview.OverviewRecommendations.OverviewSnapshot =
        com.root.app.overview.OverviewRecommendations.OverviewSnapshot(
            hasActiveLanguage = activeLanguage != null,
            dueCount = if (current != null) 1 else 0,
            canPracticeMore = canPracticeMore,
            hasWeeklyChallenge = challenge != null,
            weeklyChallengeCompleted = challenge?.completed ?: false,
            hasOpenContributionDraft = repository.openDrafts().isNotEmpty(),
            hasInstalledPacks = rows.isNotEmpty(),
        )

    fun finishLaunch() { launched = true; saved["launched"] = true }
    fun clearError() { error = null }
    fun changeTheme(value: String) {
        theme = value
        repository.setTheme(value)
        viewModelScope.launch { updateWidget() }
    }

    fun load() = viewModelScope.launch {
        loading = true
        loadFailed = false
        try {
            repository.initialize()
            languages = repository.languages()
            refreshPersonalLanguages()
            activeLanguage = languages.firstOrNull { it.id == repository.activeLanguageId() } ?: languages.firstOrNull()
            activeLanguage?.let { repository.setActiveLanguage(it.id) }
            refreshDetails()
            val language = activeLanguage
            val existingSession = sessionId
            if (language != null && existingSession != null) {
                // Resume this device's durable run if it is still for the active
                // language/pack scope; a scope mismatch (e.g. active language changed
                // through another path) starts a fresh one instead of misapplying it.
                val state = repository.practiceState(existingSession)
                if (state != null && state.languageId == language.id && state.packId == sessionPackId) {
                    applyState(state)
                } else {
                    startSessionInternal(sessionPackId)
                }
            } else if (language != null) {
                startSessionInternal(null)
            }
            error = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e("Root", "Unable to load local practice", e)
            loadFailed = true
            error = "Your words couldn’t be loaded. Please try again."
        } finally { loading = false }
    }

    private suspend fun refreshDetails() {
        val language = activeLanguage ?: return
        rows = repository.packs(language.id).map { PackRow(it, repository.db.phraseDao().countForPack(it.id)) }
        if (sharePhrase == null) {
            rows.firstOrNull { it.pack.isFree && it.phraseCount > 0 }?.let {
                sharePhrase = repository.phrases(it.pack.id).firstOrNull()
            }
        }
        capability = repository.capabilityCount(language.id)
        challenge = repository.weeklyChallenge(language.id)
    }

    private suspend fun refreshPersonalLanguages() {
        personalLanguageIds = languages.filter { language ->
            repository.packs(language.id).any {
                it.id == ContentAccess.userPackId(language.id) && repository.db.phraseDao().countForPack(it.id) > 0
            }
        }.map { it.id }.toSet()
    }

    fun refreshAccess() {
        premium = access.isPremium()
        reward = ReferralPrefs.hasUnlockedReward(getApplication())
        access.refresh { active ->
            viewModelScope.launch {
                premium = active
                if (!loading) { refreshDetails(); revalidateCurrent() }
                updateWidget()
            }
        }
        if (!loading) viewModelScope.launch { refreshDetails(); revalidateCurrent(); updateWidget() }
    }

    /** Drops the current card if its content became inaccessible (pack locked/
     *  removed) since it was queued, instead of leaving it visible until the next
     *  rating attempt discovers this. Best-effort: [rate] rechecks access again at
     *  commit time regardless, so a missed revalidation here is never unsafe. */
    private suspend fun revalidateCurrent() {
        val sid = sessionId ?: return
        try { applyState(repository.revalidatePractice(sid)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* next rate() call still rechecks access */ }
    }

    fun selectLanguage(language: LanguageEntity) = viewModelScope.launch {
        if (language.id == activeLanguage?.id) return@launch
        loading = true
        try {
            repository.setActiveLanguage(language.id)
            activeLanguage = language
            sharePhrase = null
            refreshDetails()
            startSessionInternal(null)
            updateWidget()
        } catch (_: Exception) { error = "Couldn’t open this language. Try again." }
        finally { loading = false }
    }

    fun startSession(packId: String? = null) = viewModelScope.launch {
        loading = true
        try { startSessionInternal(packId) }
        catch (_: Exception) { error = "Couldn’t open these words. Please try again." }
        finally { loading = false }
    }

    fun startPackPractice(packId: String) = viewModelScope.launch {
        loading = true
        try {
            val pack = requireNotNull(repository.db.packDao().getById(packId)) { "Pack is unavailable" }
            val language = requireNotNull(repository.db.languageDao().getById(pack.languageId)) { "Language is unavailable" }
            repository.setActiveLanguage(language.id)
            activeLanguage = language
            languages = repository.languages()
            sharePhrase = null
            refreshDetails()
            startSessionInternal(packId)
            updateWidget()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e("Root", "Unable to start downloaded phrase practice", failure)
            error = "These words couldn't be opened. Please try again."
        } finally {
            loading = false
        }
    }

    /** Ends any current run for this scope's *previous* language/pack — switching
     *  scope always durably ends the old run, it is never resumed later — then
     *  resumes or starts the durable run for the new scope. */
    private suspend fun startSessionInternal(packId: String?) {
        val language = activeLanguage ?: return
        sessionId?.let { previous ->
            val previousState = repository.practiceState(previous)
            if (previousState != null && (previousState.languageId != language.id || previousState.packId != packId)) {
                repository.stopPractice(previous, PracticeEndReason.SCOPE_CHANGED)
            }
        }
        sessionPackId = packId
        val state = repository.beginOrResumePractice(language.id, packId)
        sessionId = state?.sessionId
        applyState(state)
    }

    /** Called after a session page completes (see [canPracticeMore]) to fetch and
     *  append the next page of due phrases rather than starting an unrelated new
     *  session. Preserves this session's correct/turn counts and identity. */
    fun continuePractice() = viewModelScope.launch {
        val sid = sessionId ?: return@launch
        try {
            applyState(repository.continuePractice(sid))
        } catch (_: Exception) { error = "Couldn’t fetch more words right now. Please try again." }
    }

    /** Durably ends the run (it is never later resumed) without closing the screen:
     *  the learner sees the same page-complete/session-complete summary as running
     *  out of due phrases, just triggered early. */
    fun stopSession() = viewModelScope.launch {
        val sid = sessionId ?: return@launch
        try {
            if (repository.stopPractice(sid, PracticeEndReason.STOPPED)) {
                applyState(repository.practiceState(sid))
            } else error = "Couldn’t stop just yet. Please try again."
        } catch (_: Exception) { error = "Couldn’t stop just yet. Please try again." }
    }

    /** Durably ends the run before invoking [onClosed] (typically finishing the
     *  Activity), so closing a session can never leave a run open that looks
     *  finished on screen but silently resumes later. */
    fun closeSession(onClosed: () -> Unit) = viewModelScope.launch {
        val sid = sessionId
        try {
            val ok = sid == null || repository.stopPractice(sid, PracticeEndReason.CLOSED)
            if (ok) {
                if (sid != null) applyState(repository.practiceState(sid))
                onClosed()
            } else error = "Couldn’t close just yet. Please try again."
        } catch (_: Exception) { error = "Couldn’t close just yet. Please try again." }
    }

    private fun applyState(state: PracticeSessionState?) {
        current = state?.current?.phrase
        currentEntryId = state?.current?.entryId
        current?.let { sharePhrase = it }
        correct = state?.correctCount ?: 0
        turn = state?.turn ?: 0
        completed = current == null && turn > 0
        canPracticeMore = state != null && current == null && state.hasMoreAfterPage
    }

    suspend fun rate(phraseId: String, level: ConfidenceLevel): Boolean =
        viewModelScope.async { persistRating(phraseId, level) }.await()

    /** Self-reported, idempotent "I practiced this out loud" acknowledgement —
     *  see [RootRepository.markPracticed]. Deliberately does not touch `turn`,
     *  `correct`, or any recall/scheduling state: this is not a rating. */
    fun markPracticed(phraseId: String) = viewModelScope.launch {
        repository.markPracticed(phraseId)
    }

    /** Persists the attempt, advances the queue, and refreshes derived state. Guarded
     *  by [ratingMutex] so a second rating call while one is still in flight (e.g. a
     *  fast double-tap racing the exit animation) is dropped instead of double-counted.
     *  Returns false on a stale/mismatched phrase, a lock conflict, an ended session,
     *  or a save failure; the UI restores the card in that case rather than treating
     *  it as rated. */
    private suspend fun persistRating(phraseId: String, level: ConfidenceLevel): Boolean {
        if (!ratingMutex.tryLock()) return false
        return try {
            val sid = sessionId ?: return false
            val entryId = currentEntryId ?: return false
            if (current?.id != phraseId) return false
            val result = repository.ratePractice(sid, entryId, level)
            // Save first; presentation may be disposed by rotation or navigation.
            if (RootMotion.enabled()) delay(if (level == ConfidenceLevel.MISSED) 1500 else 1000)
            when (result) {
                is PracticeRateResult.Committed -> applyState(result.state)
                is PracticeRateResult.AlreadyCommitted -> applyState(result.state)
                is PracticeRateResult.Conflicting -> { applyState(result.state); return false }
                is PracticeRateResult.Unavailable -> { applyState(result.state); return false }
                is PracticeRateResult.StaleSkipped -> { applyState(result.state); return false }
                PracticeRateResult.SessionEnded -> return false
            }
            try { capability = repository.capabilityCount(activeLanguage!!.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("Root", "Recall saved, but summary could not refresh", e) }
            updateWidget()
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e("Root", "Unable to save recall", e)
            error = "That recall wasn’t saved. Please try again."
            false
        } finally { ratingMutex.unlock() }
    }

    fun completeChallenge() = viewModelScope.launch {
        try {
            challenge?.let { repository.completeChallenge(it.id); challenge = it.copy(completed = true) }
        } catch (_: Exception) { error = "Couldn’t save that just yet. Please try again." }
    }

    suspend fun contribute(
        language: String,
        prompt: String,
        answer: String,
        audio: String?,
        speakerLabel: String? = null,
        consentConfirmed: Boolean = false,
    ) {
        repository.contribute(language, prompt, answer, audio, speakerLabel, consentConfirmed)
        // The reference now belongs to the saved phrase, even if refreshing UI fails.
        try {
            languages = repository.languages()
            refreshPersonalLanguages()
            activeLanguage = languages.firstOrNull { it.name.equals(language.trim(), true) } ?: activeLanguage
            activeLanguage?.let { repository.setActiveLanguage(it.id) }
            sharePhrase = null
            refreshDetails()
            startSessionInternal(null)
            updateWidget()
        } catch (e: Exception) {
            Log.w("Root", "Phrase saved, but presentation could not refresh", e)
            error = "Your phrase is saved. Reopen Root to see it."
        }
    }

    private suspend fun updateWidget() {
        try { RootWidget().updateAll(getApplication()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w("Root", "Practice saved; widget update failed", e) }
    }
}
