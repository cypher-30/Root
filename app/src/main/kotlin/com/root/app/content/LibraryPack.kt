package com.root.app.content

enum class LibraryStatus {
    AVAILABLE, DOWNLOADING, VERIFYING, INSTALLED, UPDATE_AVAILABLE, FAILED, CANCELLED, RETIRED,
}

/** Catalog metadata remains visible when a download fails; installedVersion is independent. */
data class LibraryPack(
    val id: String,
    val title: String,
    val languageName: String,
    val objective: String,
    val version: Int,
    val lessonCount: Int,
    val phraseCount: Int,
    val downloadBytes: Long,
    val installedVersion: Int?,
    val development: Boolean,
    val status: LibraryStatus,
    val error: String?,
    /** Room language this unit installs into, once known (installed at least once). */
    val languageId: String? = null,
    /** Genuine recordings shipped with this unit; zero means nothing to listen to yet. */
    val audioCount: Int = 0,
)

/** Best-effort on-disk usage summary — see [ContentLibrary.storageUsage]. */
data class ContentStorageReport(
    val installedBytes: Long,
    val pendingCleanupPackCount: Int,
)
