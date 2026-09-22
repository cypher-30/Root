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
            failed(library, requestId, "This content format is not supported.", error)
        } catch (error: IllegalArgumentException) {
            failed(library, requestId, error.message ?: "This content is not valid.", error)
        } catch (error: IOException) {
            failed(library, requestId, error.message ?: "Download failed. Check the connection and storage, then retry.", error)
        } catch (error: android.database.sqlite.SQLiteException) {
            failed(library, requestId, "Content could not be saved. Check available storage and retry.", error)
        }
    }

    private suspend fun failed(library: ContentLibrary, requestId: String, message: String, error: Exception): Result {
        Log.w("RootContent", "Pack install failed", error)
        library.failJob(requestId, message)
        return Result.failure()
    }
}
