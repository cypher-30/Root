package com.root.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContentDao {
    @Insert
    suspend fun insertPackVersion(version: PackVersionEntity)

    @Query("SELECT * FROM pack_versions WHERE pack_id = :packId AND version = :version")
    suspend fun getPackVersion(packId: String, version: Int): PackVersionEntity?

    @Query("SELECT * FROM pack_versions WHERE id = :id")
    suspend fun getPackVersionById(id: String): PackVersionEntity?

    @Query("SELECT * FROM pack_versions WHERE pack_id = :packId ORDER BY version DESC")
    suspend fun versionsForPack(packId: String): List<PackVersionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInstalledPack(pack: InstalledPackEntity)

    @Query(
        "UPDATE installed_packs SET current_version = :currentVersion, status = :status, updated_at = :updatedAt WHERE pack_id = :packId",
    )
    suspend fun updateInstalledPack(packId: String, currentVersion: Int, status: InstalledPackStatus, updatedAt: Long)

    @Query("SELECT * FROM installed_packs WHERE pack_id = :packId")
    suspend fun getInstalledPack(packId: String): InstalledPackEntity?

    @Query("SELECT * FROM installed_packs WHERE status = 'READY'")
    fun observeReadyPacks(): Flow<List<InstalledPackEntity>>

    @Query("SELECT * FROM installed_packs ORDER BY pack_id")
    fun observeAllInstalled(): Flow<List<InstalledPackEntity>>

    @Query("SELECT * FROM installed_packs ORDER BY pack_id")
    suspend fun allInstalled(): List<InstalledPackEntity>

    /** Alias of [allInstalled] under the installer's expected name — same
     *  query, kept as a distinct method rather than a rename so existing
     *  callers of [allInstalled] are unaffected. */
    @Query("SELECT * FROM installed_packs ORDER BY pack_id")
    suspend fun listAllInstalled(): List<InstalledPackEntity>

    @Insert
    suspend fun insertInstallJob(job: PackInstallJobEntity)

    @Query(
        "UPDATE pack_install_jobs SET status = :status, error_message = :errorMessage, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun updateInstallJob(id: String, status: PackInstallJobStatus, errorMessage: String?, updatedAt: Long)

    @Query("SELECT * FROM pack_install_jobs WHERE request_id = :requestId")
    suspend fun getInstallJobByRequestId(requestId: String): PackInstallJobEntity?

    @Query("SELECT * FROM pack_install_jobs WHERE pack_id = :packId ORDER BY started_at DESC, rowid DESC")
    suspend fun installJobsForPack(packId: String): List<PackInstallJobEntity>

    @Query("SELECT * FROM pack_install_jobs ORDER BY started_at DESC, rowid DESC")
    fun observeInstallJobs(): Flow<List<PackInstallJobEntity>>

    @Query("SELECT * FROM pack_install_jobs WHERE status IN ('PENDING', 'DOWNLOADING', 'VALIDATING', 'INSTALLING')")
    suspend fun unfinishedInstallJobs(): List<PackInstallJobEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertManagedPhrase(managed: ManagedPhraseEntity)

    @Query("SELECT * FROM managed_phrases WHERE phrase_id = :phraseId")
    suspend fun getManagedPhrase(phraseId: String): ManagedPhraseEntity?

    @Query("SELECT * FROM managed_phrases WHERE pack_id = :packId")
    suspend fun managedPhrasesForPack(packId: String): List<ManagedPhraseEntity>

    @Query("UPDATE managed_phrases SET retired = :retired, updated_at = :updatedAt WHERE phrase_id = :phraseId")
    suspend fun setManagedPhraseRetired(phraseId: String, retired: Boolean, updatedAt: Long)

    @Query("UPDATE managed_phrases SET pack_version = :packVersion, retired = :retired, updated_at = :updatedAt WHERE phrase_id = :phraseId")
    suspend fun updateManagedPhraseVersion(phraseId: String, packVersion: Int, retired: Boolean, updatedAt: Long)

    @Query("UPDATE managed_phrases SET retired = :retired, updated_at = :updatedAt WHERE pack_id = :packId")
    suspend fun setPackPhrasesRetired(packId: String, retired: Boolean, updatedAt: Long)

    @Insert
    suspend fun insertAssets(assets: List<ContentAssetEntity>)

    @Query("SELECT * FROM content_assets WHERE pack_id = :packId AND pack_version = :packVersion")
    suspend fun assetsForPackVersion(packId: String, packVersion: Int): List<ContentAssetEntity>

    @Query("SELECT * FROM content_assets WHERE pack_id = :packId AND pack_version = :packVersion AND asset_id = :assetId")
    suspend fun getAsset(packId: String, packVersion: Int, assetId: String): ContentAssetEntity?

    @Query("UPDATE content_assets SET local_uri = :localUri WHERE id = :id")
    suspend fun setAssetLocalUri(id: String, localUri: String?)

    /** Durably ends every still-open (ACTIVE/PAUSED) lesson run belonging to a
     *  pack being retired/uninstalled, per "end affected unfinished runs with
     *  an unavailable reason, preserve evidence." Events/history are untouched. */
    @Query(
        "UPDATE lesson_runs SET status = 'UNAVAILABLE', updated_at = :now WHERE pack_id = :packId AND status IN ('ACTIVE', 'PAUSED')",
    )
    suspend fun endPackLessons(packId: String, now: Long)

    /** Marks any still-PENDING practice queue entry for one of this pack's
     *  managed phrases as SKIPPED, mirroring the lazy revalidation
     *  [com.root.app.practice.PracticeRepository] already does per-entry, but
     *  applied eagerly at retirement time. */
    @Query(
        """
        UPDATE practice_queue_entries SET state = 'SKIPPED'
        WHERE state = 'PENDING' AND phrase_id IN (SELECT phrase_id FROM managed_phrases WHERE pack_id = :packId)
        """,
    )
    suspend fun skipUnavailablePackEntries(packId: String)

    /** Whether any not-yet-ended practice session still snapshots a phrase
     *  from this pack — while true, this pack's older media must be retained
     *  even if a newer version is installed, since a legacy snapshot stores an
     *  audio path, not a revision ID. */
    @Query(
        """
        SELECT COUNT(*) FROM practice_queue_entries e
        JOIN practice_sessions s ON s.id = e.session_id
        WHERE e.pack_id_snapshot = :packId AND s.status != 'ENDED'
        """,
    )
    suspend fun openPracticeReferences(packId: String): Int

    /** Whether any still-open lesson run is pinned to this exact pack version —
     *  while true, that version's media must not be deleted out from under it. */
    @Query("SELECT COUNT(*) FROM lesson_runs WHERE pack_id = :packId AND pack_version = :version AND status IN ('ACTIVE', 'PAUSED')")
    suspend fun openLessonReferences(packId: String, version: Int): Int
}
