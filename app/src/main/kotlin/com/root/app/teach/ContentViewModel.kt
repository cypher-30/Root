package com.root.app.teach

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.root.app.content.ContentLibrary
import com.root.app.content.Lesson
import com.root.app.content.LibraryPack
import com.root.app.data.AppDatabase
import com.root.app.learning.LessonRunner
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * One catalog row: the real, frozen [LibraryPack] plus this unit's [Lesson]s
 * once loaded on demand (empty until [ContentViewModel.loadDetail] fetches the
 * installed manifest — a unit's lesson bodies are never eagerly decoded for
 * the whole catalog).
 */
data class TeachUnitRow(val pack: LibraryPack, val lessons: List<Lesson> = emptyList())

/**
 * Backs the catalog/unit-detail screens, talking directly to the shared,
 * frozen `com.root.app.content.ContentLibrary` — this module intentionally
 * defines no parallel content/DTO layer of its own. Owned separately from
 * [com.root.app.RootViewModel]/[com.root.app.data.RootRepository] per the plan:
 * teaching units are not recall packs, and this must not become the shared
 * PacksScreen ViewModel.
 */
class ContentViewModel(
    application: Application,
    private val library: ContentLibrary = ContentLibrary(application),
) : AndroidViewModel(application) {
    private val runner = LessonRunner(AppDatabase.get(application))

    var loading by mutableStateOf(true)
        private set
    var rows by mutableStateOf(emptyList<TeachUnitRow>())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Lesson ids with a currently-open (ACTIVE/PAUSED) run, per
     *  [LessonRunner.openRunState] — read-only, never fabricates a run. Refreshed
     *  whenever [loadDetail] runs; drives the catalog's Resume-vs-Start label
     *  without this ViewModel keeping its own runId cache. */
    var resumableLessonIds by mutableStateOf(emptySet<String>())
        private set

    init {
        library.observePacks().onEach { packs ->
            val previousLessons = rows.associate { it.pack.id to it.lessons }
            rows = packs.map { pack -> TeachUnitRow(pack, previousLessons[pack.id].orEmpty()) }
            loading = false
        }.launchIn(viewModelScope)
        viewModelScope.launch {
            try {
                // initialize() installs the debug-only development starter pack
                // (see ContentLibrary.initialize) and loads any cached catalog;
                // it must run before observePacks() can show anything beyond
                // whatever is already installed from a previous session.
                library.initialize()
                error = null
            } catch (e: Exception) {
                Log.w("ContentViewModel", "Library initialize failed", e)
                error = "Couldn't load teaching units. Please try again."
            }
            refresh()
        }
    }

    /** Best-effort remote catalog refresh; initialize()'s cached/installed
     *  packs already render without it, so a network failure here is silent
     *  rather than surfaced as a hard error — but it is still logged, so a
     *  persistently failing refresh (as opposed to ordinary offline use) is
     *  visible in diagnostics instead of vanishing with no trace. */
    fun refresh() = viewModelScope.launch {
        try { library.refreshCatalog() } catch (e: Exception) {
            Log.w("ContentViewModel", "Catalog refresh failed; showing cached/installed content", e)
        }
    }

    /** Fetches this unit's installed lesson content (a manifest read), called
     *  when the learner opens a unit's detail/lesson screen — never eagerly
     *  for the whole catalog. Returns null (no-op) until the unit is actually
     *  installed and READY. */
    fun loadDetail(unitId: String) = viewModelScope.launch {
        try {
            val manifest = library.manifest(unitId) ?: return@launch
            rows = rows.map { if (it.pack.id == unitId) it.copy(lessons = manifest.lessons) else it }
            val resumableInUnit = manifest.lessons
                .filter { runner.openRunState(unitId, it.id) != null }
                .map { it.id }
                .toSet()
            // Merge in (don't drop) any other unit's already-known resumable
            // lessons — this only refreshes the unit just opened.
            resumableLessonIds = (resumableLessonIds.filterNot { id -> manifest.lessons.any { it.id == id } }.toSet()) + resumableInUnit
        } catch (e: Exception) {
            Log.w("ContentViewModel", "loadDetail($unitId) failed", e)
            error = "Couldn't load this unit's lessons. Please try again."
        }
    }

    fun download(unitId: String) = viewModelScope.launch {
        try { library.download(unitId) } catch (e: Exception) {
            Log.w("ContentViewModel", "download($unitId) failed", e)
            error = "Couldn't start the download. Please try again."
        }
    }

    fun cancel(unitId: String) = viewModelScope.launch {
        try { library.cancel(unitId) } catch (e: Exception) {
            Log.w("ContentViewModel", "cancel($unitId) failed", e)
            error = "Couldn't cancel the download. Please try again."
        }
    }

    fun uninstall(unitId: String) = viewModelScope.launch {
        try { library.uninstall(unitId) } catch (e: Exception) {
            Log.w("ContentViewModel", "uninstall($unitId) failed", e)
            error = "Couldn't remove this unit. Please try again."
        }
    }

    /** Retry/update are both just re-issuing a download against the pack's
     *  current catalog entry — the installer itself decides what that means
     *  (retry the failed job, or fetch the newer version). */
    fun retry(unitId: String) = download(unitId)
    fun update(unitId: String) = download(unitId)

    fun clearError() { error = null }

    fun row(unitId: String): TeachUnitRow? = rows.firstOrNull { it.pack.id == unitId }

    /** A resolver scoped to this unit's currently-installed revision, for
     *  lesson audio playback. Never resolves remote/undownloaded audio. */
    fun audioResolver(unitId: String): LessonAudioResolver {
        val pack = row(unitId)?.pack
        val version = pack?.installedVersion ?: pack?.version ?: 0
        return LessonAudioResolver { assetId ->
            if (assetId.isNullOrBlank()) LessonAudioSource.Unavailable
            else library.localAudio(unitId, version, assetId)
                ?.let { LessonAudioSource.DownloadedFile(it) }
                ?: LessonAudioSource.Unavailable
        }
    }

    /** Same rule the runner itself applies via `assetAvailable` — kept here so
     *  the UI can pass an identical predicate into [LessonViewModel]'s
     *  [com.root.app.learning.LessonRunner] without a second source of truth. */
    fun assetAvailable(packId: String, packVersion: Int, assetId: String): Boolean =
        library.localAudio(packId, packVersion, assetId) != null

    companion object {
        /** Explicit factory: [ContentViewModel] takes constructor parameters
         *  beyond a bare [Application], which the platform's default
         *  reflective `AndroidViewModelFactory` cannot instantiate. Must be
         *  passed to `viewModel(factory = ...)` at every call site. */
        fun factory(library: ContentLibrary? = null): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                ContentViewModel(application, library ?: ContentLibrary(application))
            }
        }
    }
}
