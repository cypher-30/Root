package com.root.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.root.app.data.ContributionAudioState
import com.root.app.data.RootRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Every typed field of an "add a word" draft. */
data class DraftFields(
    val languageName: String = "",
    val prompt: String = "",
    val answer: String = "",
    val speakerLabel: String = "",
) {
    fun isBlank(): Boolean = prompt.isBlank() && answer.isBlank() && speakerLabel.isBlank()
}

/** What the form may honestly claim about persistence. */
sealed interface DraftSaveStatus {
    data object Idle : DraftSaveStatus
    data object Pending : DraftSaveStatus
    data object Saved : DraftSaveStatus
    data class Failed(val message: String) : DraftSaveStatus
}

/** Whether the form is ready to edit. A requested draft must be read before
 *  showing editable fields, so stale defaults never overwrite saved text. */
sealed interface DraftOpenState {
    data object Loading : DraftOpenState
    data object Ready : DraftOpenState
    data object Missing : DraftOpenState
    data class Failed(val message: String) : DraftOpenState
}

/**
 * Owns one contribution form's durable draft. Writes are ordered through a
 * mutex and debounced, but always drained on [flush] (called before any
 * deliberate navigation), and each database write runs to completion even if
 * the screen leaves mid-write. [status] only reports Saved after the database
 * actually holds the latest fields.
 */
class ContributeViewModel(
    application: Application,
    private val saved: SavedStateHandle,
) : AndroidViewModel(application) {
    private val repository = RootRepository(application)
    private val writeLock = Mutex()
    private var debounce: Job? = null
    private var lastSaved: DraftFields? = null
    private var terminal = false

    var draftId: String?
        get() = saved[KEY_DRAFT_ID]
        private set(value) { saved[KEY_DRAFT_ID] = value }

    var openState by mutableStateOf<DraftOpenState>(DraftOpenState.Loading)
        private set
    var fields by mutableStateOf(DraftFields())
        private set
    var status by mutableStateOf<DraftSaveStatus>(DraftSaveStatus.Idle)
        private set
    /** Completed draft recording the database currently references, if any. */
    var persistedAudioPath by mutableStateOf<String?>(null)
        private set
    /** Incremented whenever the durable set of open drafts may have changed. */
    var draftsVersion by mutableIntStateOf(0)
        private set

    private var fallbackLanguageId: String? = null
    private var opened = false

    /** Opens [requestedDraftId] (or a fresh form) exactly once per ViewModel. */
    fun open(requestedDraftId: String?, defaultLanguageName: String, activeLanguageId: String?) {
        if (opened) return
        opened = true
        fallbackLanguageId = activeLanguageId
        val id = draftId ?: requestedDraftId
        if (id == null) {
            fields = DraftFields(languageName = defaultLanguageName)
            openState = DraftOpenState.Ready
            return
        }
        viewModelScope.launch { load(id, defaultLanguageName) }
    }

    fun retryOpen(defaultLanguageName: String) {
        val id = draftId ?: return
        viewModelScope.launch { load(id, defaultLanguageName) }
    }

    private suspend fun load(id: String, defaultLanguageName: String) {
        openState = DraftOpenState.Loading
        try {
            val draft = repository.draft(id)
            if (draft == null || draft.audioState == ContributionAudioState.COMMITTED ||
                draft.audioState == ContributionAudioState.DISCARDED
            ) {
                openState = DraftOpenState.Missing
                return
            }
            draftId = draft.id
            val languageName = draft.languageNameDraft
                ?: repository.languages().firstOrNull { it.id == draft.languageId }?.name
                ?: defaultLanguageName
            val restored = DraftFields(
                languageName = languageName,
                prompt = draft.promptDraft,
                answer = draft.answerDraft,
                speakerLabel = draft.speakerLabelDraft.orEmpty(),
            )
            fields = restored
            lastSaved = restored
            persistedAudioPath = draft.audioDraftPath
            status = DraftSaveStatus.Saved
            openState = DraftOpenState.Ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            openState = DraftOpenState.Failed("This draft couldn't be opened. Try again.")
        }
    }

    fun update(next: DraftFields) {
        if (terminal || openState != DraftOpenState.Ready) return
        fields = next
        if (draftId == null && next.isBlank()) {
            status = DraftSaveStatus.Idle
            return
        }
        status = DraftSaveStatus.Pending
        debounce?.cancel()
        debounce = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            writeLatest()
        }
    }

    /** Persists the current completed recording path (or its removal). */
    fun updateAudio(path: String?, onPersisted: (String) -> Unit) {
        if (terminal || openState != DraftOpenState.Ready) return
        if (path == persistedAudioPath) {
            path?.let(onPersisted)
            return
        }
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    writeLock.withLock {
                        if (terminal) return@withLock
                        val id = ensureDraftLocked() ?: return@withLock
                        repository.saveDraftAudio(id, path)
                        persistedAudioPath = path
                        draftsVersion++
                    }
                }
                if (path != null && persistedAudioPath == path) onPersisted(path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = DraftSaveStatus.Failed("Your recording isn't saved in the draft yet. It stays while this screen is open.")
            }
        }
    }

    /** Drains any pending edit. Returns true when the database holds the latest fields. */
    suspend fun flush(): Boolean {
        debounce?.cancel()
        debounce = null
        return writeLatest()
    }

    /** Stops autosave just before the phrase is committed; returns the draft id to commit with. */
    suspend fun prepareCommit(): String? {
        flush()
        return writeLock.withLock {
            terminal = true
            debounce?.cancel()
            ensureDraftLocked()
        }
    }

    /** Re-enables autosave when a commit failed and the learner can retry. */
    fun commitFailed() {
        terminal = false
    }

    fun committed() {
        terminal = true
        draftsVersion++
    }

    suspend fun discard() {
        debounce?.cancel()
        withContext(NonCancellable) {
            writeLock.withLock {
                draftId?.let { repository.discardDraft(it) }
                terminal = true
            }
        }
        draftsVersion++
    }

    private suspend fun writeLatest(): Boolean = try {
        withContext(NonCancellable) {
            writeLock.withLock {
                if (terminal) return@withLock true
                val latest = fields
                if (latest == lastSaved) return@withLock true
                if (draftId == null && latest.isBlank()) return@withLock true
                val id = ensureDraftLocked() ?: return@withLock false
                repository.saveDraft(
                    id,
                    latest.languageName,
                    latest.prompt,
                    latest.answer,
                    latest.speakerLabel.trim().takeIf { it.isNotEmpty() },
                )
                lastSaved = latest
                draftsVersion++
                true
            }
        }.also { ok ->
            if (ok && fields == lastSaved) status = DraftSaveStatus.Saved
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        status = DraftSaveStatus.Failed("Draft not saved. Your words are still here; keep this screen open and try again.")
        false
    }

    private suspend fun ensureDraftLocked(): String? {
        draftId?.let { return it }
        val languageId = fallbackLanguageId ?: repository.activeLanguageId() ?: repository.languages().firstOrNull()?.id ?: ""
        val created = repository.createDraft(languageId, fields.languageName)
        draftId = created.id
        draftsVersion++
        return created.id
    }

    private companion object {
        const val KEY_DRAFT_ID = "contribute.draftId"
        const val AUTOSAVE_DEBOUNCE_MS = 500L
    }
}
