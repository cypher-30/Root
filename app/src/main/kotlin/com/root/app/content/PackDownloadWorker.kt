package com.root.app.content

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import java.io.IOException

class PackDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val library = ContentLibrary(applicationContext)
        val requestId = inputData.getString("requestId")
        val payload = inputData.getString("entry")
        if (requestId == null || payload == null) {
            Log.e("RootContent", "Download request is missing its persisted identity or manifest reference")
            return Result.failure()
        }
        return try {
            val entry = ContentJson.decodeFromString<CatalogEntry>(payload)
            CatalogValidation.requireValid(Catalog(1, 1, listOf(entry)))
            library.installRemote(entry, requestId)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SerializationException) {
            // Terminal: the manifest itself is malformed, no retry will fix it.
            failed(library, requestId, "This content format is not supported.", error)
        } catch (error: ContentDownloadException) {
            // Typed classification takes priority over the generic IOException
            // handling below (ContentDownloadException is itself an IOException):
            // a wrong/misconfigured URL, an oversized declared payload, or audio
            // that fails local decode/duration verification will never be fixed
            // by re-running the same request, so those fail immediately rather
            // than silently retrying (and eventually showing a misleading
            // "check your connection" message) up to three times first.
            when (error.failure) {
                DownloadFailure.NOT_CONFIGURED, DownloadFailure.INVALID_URL,
                DownloadFailure.TOO_LARGE, DownloadFailure.INVALID_AUDIO ->
                    failed(library, requestId, error.message ?: "This content is not valid.", error)
                DownloadFailure.NETWORK, DownloadFailure.STORAGE, DownloadFailure.INTEGRITY ->
                    if (runAttemptCount < MAX_AUTO_RETRIES) {
                        Log.w("RootContent", "Pack install failed transiently (attempt $runAttemptCount), retrying", error)
                        Result.retry()
                    } else {
                        failed(library, requestId, error.message ?: "Download failed. Check the connection and storage, then retry.", error)
                    }
            }
        } catch (error: IllegalArgumentException) {
            // Terminal: manifest fails validation regardless of connectivity/retries.
            failed(library, requestId, error.message ?: "This content is not valid.", error)
        } catch (error: IOException) {
            // Transient: any other network/storage-availability failure (not a
            // typed ContentDownloadException, e.g. a raw SocketTimeoutException)
            // is worth WorkManager's own retry+backoff, up to MAX_AUTO_RETRIES,
            // before surfacing a manual "Tap Retry" failure — most connectivity
            // blips resolve on their own.
            if (runAttemptCount < MAX_AUTO_RETRIES) {
                Log.w("RootContent", "Pack install failed transiently (attempt $runAttemptCount), retrying", error)
                Result.retry()
            } else {
                failed(library, requestId, error.message ?: "Download failed. Check the connection and storage, then retry.", error)
            }
        } catch (error: android.database.sqlite.SQLiteException) {
            // Terminal: a Room/storage write failure is not fixed by re-running the
            // same install without a person addressing the underlying storage issue.
            failed(library, requestId, "Content could not be saved. Check available storage and retry.", error)
        }
    }

    private suspend fun failed(library: ContentLibrary, requestId: String, message: String, error: Exception): Result {
        Log.w("RootContent", "Pack install failed", error)
        library.failJob(requestId, message)
        return Result.failure()
    }

    private companion object {
        /** Cap on WorkManager-driven automatic retries for transient IO failures,
         *  after which the job is marked FAILED with a manual "Tap Retry" affordance
         *  instead of retrying forever in the background. */
        const val MAX_AUTO_RETRIES = 3
    }
}
