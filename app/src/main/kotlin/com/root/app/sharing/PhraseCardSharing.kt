package com.root.app.sharing

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.root.app.data.PhraseEntity
import com.root.app.data.ReferralPrefs
import com.root.app.data.AppDatabase
import com.root.app.data.InstalledPackStatus
import com.root.app.content.ContentJson
import com.root.app.content.PackManifest
import com.root.app.content.PublicationStatus
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PreparedPhraseCard(val uri: Uri, val preview: Bitmap)

object PhraseCardSharing {
    private const val RETENTION_MILLIS = 7 * 24 * 60 * 60 * 1000L

    suspend fun prepare(
        context: Context,
        phrase: PhraseEntity,
        languageName: String,
        palette: PhraseCardPalette,
    ): PreparedPhraseCard = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val directory = File(appContext.cacheDir, "shared")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("The image folder could not be created.")
        }
        cleanupOldCards(directory)
        ensureActive()
        val bitmap = PhraseCardRenderer.render(appContext, phrase, languageName, palette,
            attribution = attribution(appContext, phrase))
        var handedOff = false
        try {
            val file = File.createTempFile("root-word-", ".png", directory)
            try {
                file.outputStream().use { stream ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw IOException("The phrase image could not be saved.")
                    }
                }
                ensureActive()
                val uri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.sharedfiles",
                    file,
                )
                PreparedPhraseCard(uri, bitmap).also { handedOff = true }
            } catch (error: IOException) {
                if (!file.delete()) Log.w("RootSharing", "Could not remove an incomplete phrase image.")
                throw error
            } catch (error: IllegalArgumentException) {
                if (!file.delete()) Log.w("RootSharing", "Could not remove an unshareable phrase image.")
                throw error
            }
        } finally {
            if (!handedOff) bitmap.recycle()
        }
    }

    private suspend fun attribution(context: Context, phrase: PhraseEntity): String {
        val db = AppDatabase.get(context)
        val dao = db.contentDao()
        val managed = dao.getManagedPhrase(phrase.id) ?: return ""
        if (managed.retired || dao.getInstalledPack(managed.packId)?.status != InstalledPackStatus.READY) {
            throw PhraseCardException("This phrase is no longer available for sharing.")
        }
        val current = db.phraseDao().getById(phrase.id)
        if (current?.prompt != phrase.prompt || current.answer != phrase.answer) {
            throw PhraseCardException("This phrase has changed. Reopen it before sharing.")
        }
        val revision = dao.getPackVersion(managed.packId, managed.packVersion)
            ?: throw PhraseCardException("The source credits for this phrase are unavailable.")
        val manifest = ContentJson.decodeFromString<PackManifest>(revision.manifestJson)
        if (manifest.publication != PublicationStatus.PUBLISHED || manifest.credits.isEmpty()) {
            throw PhraseCardException("This development content is not approved for public sharing.")
        }
        return manifest.credits.joinToString("\n") {
            listOfNotNull(it.text, it.license, it.sourceUrl).joinToString(" / ")
        }
    }

    /** Launch on main; a successful chooser launch, not delivery, is the promised reward trigger. */
    fun openShareSheet(context: Context, card: PreparedPhraseCard) {
        val clip = ClipData.newUri(context.contentResolver, "A word from Root", card.uri)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, card.uri)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Teach someone one word").apply {
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!context.hasActivity()) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        ReferralPrefs.recordShareSheetOpened(context)
    }

    private tailrec fun Context.hasActivity(): Boolean = when (this) {
        is Activity -> true
        is ContextWrapper -> baseContext !== this && baseContext.hasActivity()
        else -> false
    }

    private fun cleanupOldCards(directory: File) {
        val files = directory.listFiles() ?: throw IOException("The image folder could not be read.")
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        files.filter {
            it.isFile && it.name.startsWith("root-word-") && it.extension == "png" && it.lastModified() < cutoff
        }.forEach {
            if (!it.delete()) Log.w("RootSharing", "Could not remove an expired phrase image.")
        }
    }
}
