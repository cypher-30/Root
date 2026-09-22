package com.root.app.archive

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.root.app.data.PhraseConsentEntity
import com.root.app.data.PhraseEntity
import com.root.app.data.RootRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Backs the "Your words" archive screen: search/edit/permanently-delete for
 * personally-contributed phrases only (see [RootRepository.personalPhrases]/
 * [RootRepository.updatePersonalPhrase]/[RootRepository.deletePersonalPhrase]).
 * Owned separately from [com.root.app.RootViewModel], matching this codebase's
 * existing pattern of small feature-scoped ViewModels (see `ContentViewModel`,
 * `PaywallViewModel`) rather than growing the shared home ViewModel further.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArchiveViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RootRepository(application)

    private val languageId = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")

    var error by mutableStateOf<String?>(null)
        private set

    private val _phrases = MutableStateFlow<List<PhraseEntity>>(emptyList())
    val phrases: StateFlow<List<PhraseEntity>> = _phrases.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    init {
        viewModelScope.launch {
            languageId.flatMapLatest { id ->
                if (id == null) flowOf(emptyList())
                else query.flatMapLatest { q -> repository.personalPhrases(id, q) }
            }.collectLatest { _phrases.value = it }
        }
    }

    /** Must be called once the active language is known (e.g. from the
     *  composable's `LaunchedEffect(languageId)`) before the list populates. */
    fun setLanguage(id: String) {
        languageId.value = id
    }

    fun setQuery(q: String) {
        _searchQuery.value = q
        query.value = q
    }

    suspend fun consentFor(phraseId: String): PhraseConsentEntity? = repository.consentFor(phraseId)

    fun update(phraseId: String, prompt: String, answer: String, onDone: (Throwable?) -> Unit = {}) {
        val langId = languageId.value ?: return
        viewModelScope.launch {
            try {
                repository.updatePersonalPhrase(langId, phraseId, prompt, answer)
                error = null
                onDone(null)
            } catch (e: Exception) {
                Log.w("ArchiveViewModel", "update($phraseId) failed", e)
                error = e.message ?: "This phrase could not be updated."
                onDone(e)
            }
        }
    }

    fun delete(phraseId: String, onDone: (Throwable?) -> Unit = {}) {
        val langId = languageId.value ?: return
        viewModelScope.launch {
            try {
                repository.deletePersonalPhrase(langId, phraseId)
                error = null
                onDone(null)
            } catch (e: Exception) {
                Log.w("ArchiveViewModel", "delete($phraseId) failed", e)
                error = e.message ?: "This phrase could not be deleted."
                onDone(e)
            }
        }
    }

    fun clearError() { error = null }
}
