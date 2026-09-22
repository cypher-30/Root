package com.root.app.content

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.root.app.BuildConfig
import com.root.app.data.*
import com.root.app.widget.RootWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Room owns installed state; the filesystem holds immutable bytes, not a second active pointer. */
class ContentLibrary internal constructor(
    context: Context,
    private val db: AppDatabase = AppDatabase.get(context.applicationContext),
    contentRoot: File = File(context.applicationContext.filesDir, "root_content"),
) {
    private val context = context.applicationContext
    private val dao = db.contentDao()
    private val files = PackFiles(contentRoot)
    private val work = WorkManager.getInstance(this.context)
    private val catalogState = MutableStateFlow<Catalog?>(null)
    private val catalogIdentity = sha256(BuildConfig.CONTENT_CATALOG_URL.toByteArray())
    private val catalogFile = AtomicFile(File(this.context.filesDir, "catalog-$catalogIdentity.json"))
    private val preferences = this.context.getSharedPreferences("root_content", Context.MODE_PRIVATE)

    suspend fun initialize() = withContext(Dispatchers.IO) {
        requestMutex.withLock {
        ContentBuildPolicy.apply(db)
        if (catalogFile.baseFile.exists()) {
            require(catalogFile.baseFile.length() <= ContentTransport.MAX_CATALOG_BYTES) { "Cached catalog is too large" }
            catalogState.value = ContentJson.decodeFromString<Catalog>(
                catalogFile.openRead().use { it.readBytes().toString(Charsets.UTF_8) },
            ).also(CatalogValidation::requireValid)
        }
        if (BuildConfig.DEBUG) installDevelopmentStarter()
        // If the process died between recording a request and enqueueing WorkManager,
        // make that request explicitly retryable rather than displaying endless progress.
        dao.unfinishedInstallJobs().forEach { job ->
            val infos = work.getWorkInfosForUniqueWork(workName(job.packId)).get()
            if (infos.none { !it.state.isFinished && it.tags.contains(job.requestId) }) {
                db.withTransaction {
                    if (dao.getInstallJobByRequestId(job.requestId)?.status in unfinishedStatuses) {
                        dao.updateInstallJob(job.id, PackInstallJobStatus.FAILED,
                            "Download interrupted. Tap Retry.", System.currentTimeMillis())
                    }
                    }
                }
            }
        }
    }

    fun observePacks(): Flow<List<LibraryPack>> =
        combine(dao.observeAllInstalled(), dao.observeInstallJobs(), catalogState) { installed, jobs, catalog ->
            val local = installed.associateBy { it.packId }
            val remote = catalog?.entries?.associateBy { it.id }.orEmpty()
            val latestJobs = jobs.groupBy { it.packId }.mapValues { it.value.first() }
            (local.keys + remote.keys).sorted().mapNotNull { id ->
                val pointer = local[id]
                val revision = pointer?.let { dao.getPackVersion(id, it.currentVersion) }
                val entry = remote[id]
                if (revision == null && entry == null) return@mapNotNull null
                val manifest = revision?.let { ContentJson.decodeFromString<PackManifest>(it.manifestJson) }
                if (!BuildConfig.DEBUG && entry == null && manifest?.publication == PublicationStatus.DEVELOPMENT) {
                    return@mapNotNull null
                }
                val job = latestJobs[id]
                val ready = pointer?.status == InstalledPackStatus.READY
                val status = when {
                    entry?.retired == true -> LibraryStatus.RETIRED
                    job?.status == PackInstallJobStatus.CANCELLED -> LibraryStatus.CANCELLED
                    job?.status == PackInstallJobStatus.FAILED -> LibraryStatus.FAILED
                    job?.status in setOf(PackInstallJobStatus.PENDING, PackInstallJobStatus.DOWNLOADING) -> LibraryStatus.DOWNLOADING
                    job?.status in setOf(PackInstallJobStatus.VALIDATING, PackInstallJobStatus.INSTALLING) -> LibraryStatus.VERIFYING
                    ready && entry != null && entry.version != pointer!!.currentVersion -> LibraryStatus.UPDATE_AVAILABLE
                    ready -> LibraryStatus.INSTALLED
                    else -> LibraryStatus.AVAILABLE
                }
                LibraryPack(
                    id, entry?.title ?: revision!!.title,
                    entry?.language?.name ?: revision!!.languageName,
                    manifest?.objective.orEmpty(), entry?.version ?: revision!!.version,
                    entry?.lessonCount ?: revision!!.lessonCount,
                    entry?.phraseCount ?: revision!!.phraseCount,
                    entry?.downloadBytes ?: (revision!!.manifestJson.toByteArray().size + manifest!!.assets.sumOf { it.bytes }),
                    pointer?.currentVersion?.takeIf { ready },
                    (entry?.publication ?: manifest!!.publication) == PublicationStatus.DEVELOPMENT,
                    status, job?.errorMessage,
                )
            }
        }

    suspend fun refreshCatalog() = withContext(Dispatchers.IO) {
        val cached = catalogState.value
        val result = ContentTransport(BuildConfig.CONTENT_CATALOG_URL).catalog(
            preferences.getString("etag-$catalogIdentity", null).takeIf { cached != null },
        )
        val bytes = result.bytes ?: return@withContext
        val next = ContentJson.decodeFromString<Catalog>(bytes.toString(Charsets.UTF_8))
        CatalogValidation.requireValid(next)
        require(cached == null || next.catalogRevision >= cached.catalogRevision) { "Catalog revision went backwards" }
        if (cached != null && cached.catalogRevision == next.catalogRevision) {
            require(cached == next) { "An immutable catalog revision changed" }
        }
        val output = catalogFile.startWrite()
        try {
            output.write(bytes)
            catalogFile.finishWrite(output)
        } catch (error: IOException) {
            catalogFile.failWrite(output)
            throw error
        }
        if (!preferences.edit().putString("etag-$catalogIdentity", result.etag).commit()) {
            throw ContentDownloadException(DownloadFailure.STORAGE, "Catalog cache metadata could not be saved")
        }
        // A withdrawn version becomes unavailable after this explicit refresh.
        next.entries.filter { it.retired }.forEach { retire(it.id) }
        catalogState.value = next
        RootWidget().updateAll(context)
    }

    suspend fun download(packId: String) = withContext(Dispatchers.IO) {
        requestMutex.withLock {
        val entry = catalogState.value?.entries?.singleOrNull { it.id == packId }
        if (entry == null && BuildConfig.DEBUG &&
            context.assets.list("content")?.contains("shona-pilot.json") == true) {
            val bytes = context.assets.open("content/shona-pilot.json").use { it.readBytes() }
            val bundled = decodeManifest(bytes, allowDevelopment = true)
            if (bundled.id == packId) {
                installBundledDevelopment(bytes)
                return@withLock
            }
        }
        requireNotNull(entry) { "Refresh the catalog before downloading this pack" }
        require(!entry.retired) { "This pack has been retired" }
        val requestId = UUID.randomUUID().toString()
        val job = PackInstallJobEntity(id = requestId, requestId = requestId, packId = packId,
            targetVersion = entry.version, status = PackInstallJobStatus.PENDING)
        db.withTransaction {
            cancelRecordedJobs(packId)
            dao.insertInstallJob(job)
        }
        val request = OneTimeWorkRequestBuilder<PackDownloadWorker>()
            .addTag(requestId)
            .setInputData(workDataOf(
                "requestId" to requestId,
                "entry" to ContentJson.encodeToString(entry),
            )).build()
        try {
            work.enqueueUniqueWork(workName(packId), ExistingWorkPolicy.REPLACE, request).result.get()
        } catch (error: java.util.concurrent.ExecutionException) {
            dao.updateInstallJob(job.id, PackInstallJobStatus.FAILED,
                "Download could not be queued. Tap Retry.", System.currentTimeMillis())
            throw IOException("Download could not be queued", error)
        }
        }
    }

    suspend fun cancel(packId: String) = withContext(Dispatchers.IO) {
        db.withTransaction { cancelRecordedJobs(packId) }
        work.cancelUniqueWork(workName(packId)).result.get()
    }

    suspend fun uninstall(packId: String) = withContext(Dispatchers.IO) {
        cancel(packId)
        retire(packId)
        dao.versionsForPack(packId).forEach { revision ->
            files.deleteVersion(packId, revision.version)
            dao.assetsForPackVersion(packId, revision.version).forEach { dao.setAssetLocalUri(it.id, null) }
        }
        RootWidget().updateAll(context)
    }

    suspend fun manifest(packId: String): PackManifest? {
        val installed = dao.getInstalledPack(packId)?.takeIf { it.status == InstalledPackStatus.READY } ?: return null
        return dao.getPackVersion(packId, installed.currentVersion)?.let {
            ContentJson.decodeFromString<PackManifest>(it.manifestJson).takeIf { manifest ->
                BuildConfig.DEBUG || manifest.publication == PublicationStatus.PUBLISHED
            }
        }
    }

    fun localAudio(packId: String, version: Int, assetId: String): String? =
        files.media(files.version(packId, version), assetId).takeIf { it.isFile }?.absolutePath

    internal suspend fun installRemote(entry: CatalogEntry, requestId: String) = withContext(Dispatchers.IO) {
        checkCurrentJob(entry.id, requestId)
        val transport = ContentTransport(BuildConfig.CONTENT_CATALOG_URL)
        val stage = files.stage(entry.id, requestId)
        files.ensureSpace(entry.downloadBytes)
        files.ensureDirectory(stage)
        updateJob(requestId, PackInstallJobStatus.DOWNLOADING)
        val manifestFile = File(stage, "manifest.json")
        transport.download(entry.manifestKey, entry.manifestBytes, entry.manifestSha256,
            manifestFile, ContentTransport.MAX_MANIFEST_BYTES)
        val bytes = manifestFile.readBytes()
        val manifest = decodeManifest(bytes, allowDevelopment = false)
        require(manifest.id == entry.id && manifest.version == entry.version &&
            manifest.language == entry.language && manifest.title == entry.title &&
            manifest.phrases.size == entry.phraseCount && manifest.lessons.size == entry.lessonCount &&
            manifest.assets.size == entry.audioCount &&
            manifest.assets.sumOf { it.bytes } + bytes.size == entry.downloadBytes) {
            "Pack does not match its catalog entry"
        }
        manifest.assets.forEach { asset ->
            checkCurrentJob(entry.id, requestId)
            transport.download(asset.key, asset.bytes, asset.sha256, files.media(stage, asset.id),
                ContentTransport.MAX_ASSET_BYTES)
        }
        updateJob(requestId, PackInstallJobStatus.VALIDATING)
        verifyMedia(manifest, stage)
        checkCurrentJob(entry.id, requestId)
        updateJob(requestId, PackInstallJobStatus.INSTALLING)
        val directory = files.promote(entry.id, entry.version, requestId, entry.manifestSha256)
        // An orphan from a previous attempt may already occupy this immutable version.
        // Revalidate its files rather than assuming a matching manifest implies intact media.
        verifyMedia(manifest, directory)
        currentCoroutineContext().ensureActive()
        activate(manifest, bytes, directory, requestId)
        files.deleteStage(entry.id, requestId)
        collectOldMedia(entry.id)
        RootWidget().updateAll(context)
    }

    internal suspend fun failJob(requestId: String, message: String) {
        val job = dao.getInstallJobByRequestId(requestId) ?: return
        db.withTransaction {
            val current = dao.getInstallJobByRequestId(requestId) ?: return@withTransaction
            if (current.status in unfinishedStatuses) {
                dao.updateInstallJob(job.id, PackInstallJobStatus.FAILED, message, System.currentTimeMillis())
            }
        }
    }

    private suspend fun checkCurrentJob(packId: String, requestId: String) {
        currentCoroutineContext().ensureActive()
        val latest = dao.installJobsForPack(packId).firstOrNull()
        if (latest?.requestId != requestId || latest.status !in unfinishedStatuses) {
            throw CancellationException("Pack request was cancelled or superseded")
        }
    }

    private suspend fun updateJob(requestId: String, status: PackInstallJobStatus) = db.withTransaction {
        val job = requireNotNull(dao.getInstallJobByRequestId(requestId))
        checkCurrentJob(job.packId, requestId)
        dao.updateInstallJob(job.id, status, null, System.currentTimeMillis())
    }

    private suspend fun cancelRecordedJobs(packId: String) {
        dao.installJobsForPack(packId).filter { it.status in unfinishedStatuses }.forEach {
            dao.updateInstallJob(it.id, PackInstallJobStatus.CANCELLED, "Download cancelled.", System.currentTimeMillis())
        }
    }

    private suspend fun retire(packId: String) = db.withTransaction {
        cancelRecordedJobs(packId)
        dao.getInstalledPack(packId)?.let {
            dao.updateInstalledPack(packId, it.currentVersion, InstalledPackStatus.RETIRED, System.currentTimeMillis())
        }
        dao.setPackPhrasesRetired(packId, true, System.currentTimeMillis())
        dao.endPackLessons(packId, System.currentTimeMillis())
        dao.skipUnavailablePackEntries(packId)
    }

    private suspend fun activate(manifest: PackManifest, bytes: ByteArray, directory: File, requestId: String?) =
        db.withTransaction {
            if (requestId != null) checkCurrentJob(manifest.id, requestId)
            val existing = dao.getPackVersion(manifest.id, manifest.version)
            val hash = sha256(bytes)
            require(existing == null || existing.manifestSha256 == hash) { "Immutable pack version changed" }
            val languageId = existing?.languageId ?: resolveLanguage(manifest.language)
            val legacyPack = db.packDao().getById(manifest.id)
            require(legacyPack == null || dao.getInstalledPack(manifest.id) != null) {
                "Managed pack collides with existing personal or starter content"
            }
            db.packDao().insertMissing(listOf(PackEntity(
                id = manifest.id, languageId = languageId, theme = manifest.title, sortOrder = 100, isFree = true,
            )))
            dao.setPackPhrasesRetired(manifest.id, true, System.currentTimeMillis())
            manifest.phrases.forEach { phrase ->
                val old = db.phraseDao().getById(phrase.id)
                val managed = dao.getManagedPhrase(phrase.id)
                require(old == null || managed?.packId == manifest.id) { "Phrase ID collides with unmanaged content" }
                // Wire prompt is target-language text; meaning is the learner-language prompt.
                db.phraseDao().upsertAll(listOf(PhraseEntity(
                    id = phrase.id, packId = manifest.id, prompt = phrase.meaning, answer = phrase.prompt,
                    audioAsset = phrase.audioAssetId?.let { files.media(directory, it).absolutePath },
                    updatedAt = System.currentTimeMillis(),
                )))
                if (managed == null) dao.insertManagedPhrase(ManagedPhraseEntity(
                    phraseId = phrase.id, packId = manifest.id, packVersion = manifest.version, sourcePhraseId = phrase.id,
                )) else dao.updateManagedPhraseVersion(phrase.id, manifest.version, false, System.currentTimeMillis())
            }
            if (existing == null) {
                dao.insertPackVersion(PackVersionEntity(
                    packId = manifest.id, version = manifest.version,
                    schemaVersion = manifest.schemaVersion, minReaderVersion = manifest.minReaderVersion,
                    languageId = languageId, languageCode = manifest.language.code, languageName = manifest.language.name,
                    title = manifest.title, publication = manifest.publication.name.lowercase(),
                    manifestJson = bytes.toString(Charsets.UTF_8), manifestSha256 = hash,
                    phraseCount = manifest.phrases.size, lessonCount = manifest.lessons.size, assetCount = manifest.assets.size,
                ))
                dao.insertAssets(manifest.assets.map { asset -> ContentAssetEntity(
                    packId = manifest.id, packVersion = manifest.version, assetId = asset.id,
                    key = asset.key, sha256 = asset.sha256, bytes = asset.bytes, mimeType = asset.mimeType,
                    durationMs = asset.durationMs, localUri = files.media(directory, asset.id).absolutePath,
                ) })
            } else {
                dao.assetsForPackVersion(manifest.id, manifest.version).forEach {
                    dao.setAssetLocalUri(it.id, files.media(directory, it.assetId).absolutePath)
                }
            }
            val installed = dao.getInstalledPack(manifest.id)
            if (installed == null) dao.insertInstalledPack(InstalledPackEntity(
                packId = manifest.id, currentVersion = manifest.version, status = InstalledPackStatus.READY,
            )) else dao.updateInstalledPack(manifest.id, manifest.version, InstalledPackStatus.READY, System.currentTimeMillis())
            requestId?.let {
                dao.updateInstallJob(it, PackInstallJobStatus.SUCCEEDED, null, System.currentTimeMillis())
            }
        }

    private suspend fun resolveLanguage(language: ContentLanguage): String {
        val seededId = when (language.code.lowercase()) {
            "sn", "sn-zw" -> db.packDao().getById("pack-shona-greetings")?.languageId
            "luo", "luo-ke" -> db.packDao().getById("pack-dholuo-greetings")?.languageId
            else -> null
        }
        if (seededId != null) return seededId
        val existing = db.languageDao().getById(language.id)
        if (existing != null) {
            val known = dao.allInstalled().any {
                dao.getPackVersion(it.packId, it.currentVersion)?.let { version ->
                    version.languageId == language.id && version.languageCode == language.code
                } == true
            }
            require(known) { "Content language ID collides with a personal language" }
            return existing.id
        }
        db.languageDao().insertMissing(listOf(LanguageEntity(id = language.id, name = language.name, isPremium = false)))
        return language.id
    }

    private suspend fun installDevelopmentStarter() {
        val source = "content/shona-pilot.json"
        if (context.assets.list("content")?.contains("shona-pilot.json") != true) return
        val bytes = context.assets.open(source).use { stream ->
            val output = java.io.ByteArrayOutputStream()
            ContentTransport.copyBounded(stream, output, ContentTransport.MAX_MANIFEST_BYTES)
            output.toByteArray()
        }
        val manifest = decodeManifest(bytes, allowDevelopment = true)
        if (dao.getInstalledPack(manifest.id) != null) return
        installBundledDevelopment(bytes)
    }

    internal suspend fun installBundledDevelopment(bytes: ByteArray) = withContext(Dispatchers.IO) {
        check(BuildConfig.DEBUG) { "Development packs are not supported by release builds" }
        val manifest = decodeManifest(bytes, allowDevelopment = true)
        require(manifest.publication == PublicationStatus.DEVELOPMENT) { "This loader is for development fixtures only" }
        require(manifest.assets.isEmpty()) { "Development bootstrap requires separately verified real audio assets" }
        val operation = "bundled"
        val stage = files.stage(manifest.id, operation)
        files.ensureSpace(bytes.size.toLong())
        files.ensureDirectory(stage)
        File(stage, "manifest.json").writeBytes(bytes)
        val directory = files.promote(manifest.id, manifest.version, operation, sha256(bytes))
        activate(manifest, bytes, directory, null)
        files.deleteStage(manifest.id, operation)
    }

    private fun decodeManifest(bytes: ByteArray, allowDevelopment: Boolean): PackManifest {
        require(bytes.size <= ContentTransport.MAX_MANIFEST_BYTES)
        val manifest = ContentJson.decodeFromString<PackManifest>(bytes.toString(Charsets.UTF_8))
        when (val validation = ContentValidator.validate(manifest, bytes.size.toLong())) {
            ValidationResult.Valid -> Unit
            is ValidationResult.Invalid -> throw IllegalArgumentException(
                validation.errors.joinToString("; ") { "${it.path}: ${it.message}" },
            )
        }
        require(allowDevelopment && BuildConfig.DEBUG || manifest.publication == PublicationStatus.PUBLISHED) {
            "Development content cannot be installed from the public catalog"
        }
        require(bytes.size + manifest.assets.sumOf { it.bytes } <= ContentTransport.MAX_PACK_BYTES) { "Pack is too large" }
        return manifest
    }

    private fun verifyMedia(manifest: PackManifest, directory: File) {
        manifest.assets.forEach { asset ->
            require(asset.mimeType.startsWith("audio/")) { "Unsupported non-audio pack asset" }
            val file = files.media(directory, asset.id)
            require(file.isFile && file.length() == asset.bytes && ContentTransport.hash(file) == asset.sha256) {
                "Installed audio failed integrity verification"
            }
            ContentAudio.validate(file, requireNotNull(asset.durationMs) { "Audio duration is required" })
        }
    }

    private suspend fun collectOldMedia(packId: String) {
        val installed = dao.getInstalledPack(packId) ?: return
        // Legacy recall snapshots store paths, not media revision IDs. Conservatively
        // retain all versions while any recall run still references this pack.
        if (dao.openPracticeReferences(packId) > 0) return
        dao.versionsForPack(packId).filter { it.version != installed.currentVersion }.forEach {
            if (dao.openLessonReferences(packId, it.version) == 0) {
                files.deleteVersion(packId, it.version)
                dao.assetsForPackVersion(packId, it.version).forEach { asset -> dao.setAssetLocalUri(asset.id, null) }
            }
        }
    }

    companion object {
        private val requestMutex = Mutex()
        internal fun workName(packId: String) = "root-content-$packId"
        internal val unfinishedStatuses = setOf(PackInstallJobStatus.PENDING, PackInstallJobStatus.DOWNLOADING,
            PackInstallJobStatus.VALIDATING, PackInstallJobStatus.INSTALLING)
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
