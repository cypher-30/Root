package com.root.app

import android.app.Application
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.root.app.billing.EntitlementStore
import com.root.app.data.*
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
 * Session state ([current], [correct], [turn], etc.) is snapshotted into
 * [SavedStateHandle] after every rating so a process death/recreation (rotation,
 * low-memory kill) can restore the learner's exact position — see [saveSession] and
 * the restoration branch in [load]. A restored session younger than 24 hours and
 * still fully unlocked is replayed via [SessionQueue.rate]; otherwise a fresh
 * session is started.
 */
class RootViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = RootRepository(application)
    private val access = EntitlementStore(application)
    // The live session queue. Recreated on load/selectLanguage/startSession/contribute;
    // null only before the first successful load.
    private var queue: SessionQueue? = null
    private var initialPhrases = emptyList<PhraseEntity>()
    // Rating history for the current session, replayed onto a fresh SessionQueue on restore.
    private val history = arrayListOf<String>()
    // Serializes rate() calls: a rapid double-tap/double-swipe must not double-count.
    private val ratingMutex = Mutex()
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

    init {
        load()
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
            val restored = saved.get<ArrayList<Bundle>>("session.phrases")
            val sessionAge = System.currentTimeMillis() - (saved.get<Long>("session.created") ?: 0L)
            if (!restored.isNullOrEmpty() && sessionAge < 24 * 60 * 60 * 1000L &&
                saved.get<String>("session.language") == activeLanguage?.id) {
                val allowed = rows.filter { ContentAccess.canAccess(activeLanguage!!, it.pack, premium, reward) }
                    .map { it.pack.id }.toSet()
                val original = restored.map { it.toPhrase() }
                if (original.any { it.packId !in allowed }) {
                    startSessionInternal(null)
                } else {
                    initialPhrases = original
                    history.clear()
                    history.addAll(saved.get<ArrayList<String>>("session.history") ?: arrayListOf())
                    queue = SessionQueue(initialPhrases)
                    history.forEach { queue?.rate(ConfidenceLevel.valueOf(it)) }
                    updateSession()
                }
            } else startSessionInternal(null)
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
                if (!loading) refreshDetails()
                updateWidget()
            }
        }
        if (!loading) viewModelScope.launch { refreshDetails(); updateWidget() }
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

    private suspend fun startSessionInternal(packId: String?) {
        val language = activeLanguage ?: return
        initialPhrases = repository.duePhrases(language.id, packId, premium).take(8)
        saved["session.created"] = System.currentTimeMillis()
        history.clear()
        queue = SessionQueue(initialPhrases)
        updateSession()
        saveSession()
    }

    private fun updateSession() {
        current = queue?.current
        current?.let { sharePhrase = it }
        correct = queue?.correctCount ?: 0
        turn = queue?.ratedCount ?: 0
        completed = current == null && turn > 0
    }

    private fun saveSession() {
        saved["session.language"] = activeLanguage?.id
        saved["session.phrases"] = ArrayList(initialPhrases.map { it.toBundle() })
        saved["session.history"] = ArrayList(history)
    }

    suspend fun rate(phraseId: String, level: ConfidenceLevel): Boolean =
        viewModelScope.async { persistRating(phraseId, level) }.await()

    /** Persists the attempt, advances the queue, and refreshes derived state. Guarded
     *  by [ratingMutex] so a second rating call while one is still in flight (e.g. a
     *  fast double-tap racing the exit animation) is dropped instead of double-counted.
     *  Returns false on a stale/mismatched phrase, a lock conflict, or a save failure;
     *  the UI restores the card in that case rather than treating it as rated. */
    private suspend fun persistRating(phraseId: String, level: ConfidenceLevel): Boolean {
        if (!ratingMutex.tryLock()) return false
        return try {
            if (current?.id != phraseId) return false
            repository.recordAttempt(phraseId, level)
            queue?.rate(level)
            history.add(level.name)
            saveSession()
            // Save first; presentation may be disposed by rotation or navigation.
            if (RootMotion.enabled()) delay(if (level == ConfidenceLevel.MISSED) 1500 else 1000)
            updateSession()
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

    suspend fun contribute(language: String, prompt: String, answer: String, audio: String?) {
        repository.contribute(language, prompt, answer, audio)
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

    private fun PhraseEntity.toBundle() = Bundle().apply {
        putString("id", id); putString("pack", packId); putString("prompt", prompt)
        putString("answer", answer); putString("audio", audioAsset)
    }
    private fun Bundle.toPhrase() = PhraseEntity(
        id = getString("id")!!, packId = getString("pack")!!, prompt = getString("prompt")!!,
        answer = getString("answer")!!, audioAsset = getString("audio"),
    )
}
