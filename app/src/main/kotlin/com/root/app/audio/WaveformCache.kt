package com.root.app.audio

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Durable, on-device cache for [WaveformDecoder] output, keyed by both a
 * caller-supplied [cacheKey] (e.g. `"$packId:$version:$assetId"` — the
 * "revision" half of "revision/hash cache") and the actual file's own SHA-256
 * ("hash"). Keying on both means: (a) a new pack version naturally gets a
 * fresh cache entry instead of reusing a stale decode, and (b) if the same
 * revision's on-disk bytes ever silently changed underneath it (corruption,
 * partial write), the hash mismatch is detected and it is safely re-decoded
 * rather than serving a waveform that no longer matches the actual audio.
 */
internal class WaveformCache(context: Context) {
    private val root = File(context.filesDir, "waveform_cache")

    suspend fun getOrDecode(cacheKey: String, sourceFile: File, maxBins: Int = WaveformDecoder.MAX_BINS): FloatArray {
        if (!sourceFile.isFile) return FloatArray(0)
        if (!root.isDirectory) root.mkdirs()
        val safeKey = sanitize(cacheKey)
        val hash = sha256(sourceFile)
        val entry = File(root, "$safeKey-$hash.bin")
        readBins(entry)?.let { return it }
        // Any other cached file for this same cacheKey is now stale (either a
        // different revision or a hash mismatch) — remove it so the cache
        // directory does not grow unboundedly across pack updates.
        root.listFiles { f -> f.name.startsWith("$safeKey-") }?.forEach { it.delete() }
        val bins = WaveformDecoder.decode(sourceFile.absolutePath, maxBins)
        writeBins(entry, bins)
        return bins
    }

    private fun readBins(file: File): FloatArray? {
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(bytes.size / 4) { buffer.float }
        } catch (error: Exception) {
            null
        }
    }

    private fun writeBins(file: File, bins: FloatArray) {
        val buffer = ByteBuffer.allocate(bins.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bins.forEach { buffer.putFloat(it) }
        file.writeBytes(buffer.array())
    }

    private fun sanitize(key: String) = key.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
