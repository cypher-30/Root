package com.root.app.content

import androidx.room.withTransaction
import com.root.app.BuildConfig
import com.root.app.data.AppDatabase
import com.root.app.data.InstalledPackStatus

/** A release APK installed over a debug APK must not inherit development teaching eligibility. */
object ContentBuildPolicy {
    suspend fun apply(db: AppDatabase, allowDevelopment: Boolean = BuildConfig.DEBUG) {
        if (allowDevelopment) return
        db.withTransaction {
            val dao = db.contentDao()
            dao.allInstalled().filter { it.status == InstalledPackStatus.READY }.forEach { pack ->
                val version = dao.getPackVersion(pack.packId, pack.currentVersion)
                if (version?.publication == "development") {
                    val now = System.currentTimeMillis()
                    dao.updateInstalledPack(pack.packId, pack.currentVersion, InstalledPackStatus.RETIRED, now)
                    dao.setPackPhrasesRetired(pack.packId, true, now)
                    dao.endPackLessons(pack.packId, now)
                    dao.skipUnavailablePackEntries(pack.packId)
                }
            }
        }
    }
}
