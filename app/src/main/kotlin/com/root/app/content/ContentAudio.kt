package com.root.app.content

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** Inspect downloaded bytes locally; a MIME header or filename is not proof of playable audio. */
object ContentAudio {
    fun validate(file: File, expectedDurationMs: Long) {
        if (!file.isFile || file.length() == 0L || expectedDurationMs <= 0) {
            throw ContentDownloadException(DownloadFailure.INVALID_AUDIO, "Reference audio is missing")
        }
        val metadata = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            metadata.setDataSource(file.absolutePath)
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw ContentDownloadException(DownloadFailure.INVALID_AUDIO, "Audio duration cannot be read")
            if (duration <= 0 || abs(duration - expectedDurationMs) > max(500L, expectedDurationMs / 50)) {
                throw ContentDownloadException(DownloadFailure.INVALID_AUDIO, "Audio duration does not match its manifest")
            }
            extractor.setDataSource(file.absolutePath)
            val audioTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw ContentDownloadException(DownloadFailure.INVALID_AUDIO, "File contains no audio track")
            extractor.selectTrack(audioTrack)
            if (extractor.readSampleData(java.nio.ByteBuffer.allocate(256 * 1024), 0) <= 0) {
                throw ContentDownloadException(DownloadFailure.INVALID_AUDIO, "Audio track contains no readable samples")
            }
        } catch (error: IllegalArgumentException) {
            throw ContentDownloadException(DownloadFailure.INVALID_AUDIO, "Reference audio is invalid", error)
        } catch (error: IllegalStateException) {
            throw ContentDownloadException(DownloadFailure.INVALID_AUDIO, "Reference audio cannot be read", error)
        } finally {
            extractor.release()
            metadata.release()
        }
    }
}
