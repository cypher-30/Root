package com.root.app.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Decodes a real (already-verified) local audio file into a bounded waveform
 * for display — never more than [MAX_BINS] amplitude bins regardless of the
 * clip's length, and always off the calling thread (`Dispatchers.Default`),
 * so a long reel clip cannot jank the UI while it decodes. Each bin is the
 * peak absolute PCM amplitude within that slice of the clip, normalized to
 * `0f..1f`. This decodes true media (via [MediaExtractor]/[MediaCodec]), not
 * a synthetic approximation — an empty, silent, or corrupt track produces an
 * all-zero (or empty) result rather than a fabricated waveform shape.
 */
internal object WaveformDecoder {
    const val MAX_BINS = 512

    suspend fun decode(path: String, maxBins: Int = MAX_BINS): FloatArray = withContext(Dispatchers.Default) {
        require(maxBins in 1..MAX_BINS) { "maxBins must be in 1..$MAX_BINS" }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext FloatArray(0)
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val bins = FloatArray(maxBins)
            val binDurationUs = (durationUs / maxBins).coerceAtLeast(1L)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inputId = decoder.dequeueInputBuffer(10_000)
                    if (inputId >= 0) {
                        val buffer: ByteBuffer = decoder.getInputBuffer(inputId) ?: continue
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputId, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputId = decoder.dequeueOutputBuffer(info, 10_000)
                if (outputId >= 0) {
                    if (info.size > 0) {
                        val output = requireNotNull(decoder.getOutputBuffer(outputId))
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        accumulatePeaks(output, info.presentationTimeUs, binDurationUs, bins)
                        decoder.releaseOutputBuffer(outputId, false)
                    } else {
                        decoder.releaseOutputBuffer(outputId, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outputId == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    // No more input and decoder has nothing ready — avoid spinning forever
                    // on a stream that never signals end-of-stream on its output side.
                    outputDone = true
                }
            }
            normalize(bins)
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            extractor.release()
        }
    }

    /** Reads 16-bit little-endian PCM samples from [output] and folds their peak
     *  absolute value into whichever bin(s) [presentationTimeUs] onward covers,
     *  since one decoded chunk can span more than one bin at high bitrates. */
    private fun accumulatePeaks(output: ByteBuffer, presentationTimeUs: Long, binDurationUs: Long, bins: FloatArray) {
        val shortsRemaining = output.remaining() / 2
        if (shortsRemaining <= 0) return
        output.order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0
        for (i in 0 until shortsRemaining) {
            val sample = output.short.toInt()
            peak = maxOf(peak, abs(sample))
        }
        val binIndex = (presentationTimeUs / binDurationUs).toInt().coerceIn(0, bins.size - 1)
        bins[binIndex] = maxOf(bins[binIndex], peak.toFloat())
    }

    private fun normalize(bins: FloatArray): FloatArray {
        val max = bins.maxOrNull()?.takeIf { it > 0f } ?: return bins
        val scale = 1f / min(max, 32_768f)
        for (i in bins.indices) bins[i] = (bins[i] * scale).coerceIn(0f, 1f)
        return bins
    }
}
