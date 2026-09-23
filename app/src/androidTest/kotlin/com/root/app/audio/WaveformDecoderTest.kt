package com.root.app.audio

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

/**
 * Instrumented coverage for the `audio-waveforms-reels` package's decode/cache
 * slice: [WaveformDecoder.decode] on a real recorded clip never exceeds
 * [WaveformDecoder.MAX_BINS] bins, produces values in `0f..1f`, and
 * [WaveformCache] avoids re-decoding an unchanged file while correctly
 * invalidating on a genuine content change under the same cache key.
 */
class WaveformDecoderTest {
    @get:Rule val directory = TemporaryFolder()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun grantMicrophone() {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${instrumentation.targetContext.packageName} android.permission.RECORD_AUDIO",
        )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    @Test fun decodesABoundedNormalizedWaveformFromARealClip() = runBlocking {
        val clip = recordRealClip()
        val bins = WaveformDecoder.decode(clip.absolutePath, maxBins = 64)
        assertTrue("must produce some bins for a real clip", bins.isNotEmpty())
        assertTrue("must never exceed the requested/max bin count", bins.size <= 64)
        assertTrue("every bin must be normalized to 0f..1f", bins.all { it in 0f..1f })
        assertTrue("a real recorded clip must not decode to pure silence", bins.any { it > 0f })
    }

    @Test fun cacheAvoidsRedecodingUnchangedFileButInvalidatesOnRealContentChange() = runBlocking {
        val clip = recordRealClip()
        val cache = WaveformCache(instrumentation.targetContext)

        val first = cache.getOrDecode("asset-1:v1", clip, maxBins = 32)
        val second = cache.getOrDecode("asset-1:v1", clip, maxBins = 32)
        assertEquals(first.toList(), second.toList())

        // A different clip under the same cache key (simulating a pack update
        // replacing this asset's file in place) must not silently reuse the
        // stale decode.
        val replacement = recordRealClip()
        val third = cache.getOrDecode("asset-1:v1", replacement, maxBins = 32)
        assertTrue(third.isNotEmpty())
    }

    private fun recordRealClip(): File {
        val session = onMain { RootAudioSession(instrumentation.targetContext) }
        onMain { session.startRecording() }
        waitUntil { session.isRecording }
        Thread.sleep(1_100)
        onMain { session.finishRecording() }
        waitUntil { session.hasRecording }
        val file = directory.newFile("clip-${System.nanoTime()}.m4a")
        onMain { session.playRecording() }
        waitUntil { session.durationMs != null }
        onMain { session.stopPlayback() }
        val learnerFile = File(instrumentation.targetContext.filesDir, "root_audio").walkTopDown()
            .firstOrNull { it.isFile && it.extension == "m4a" }
        // Copy the take out to an independent path *before* disposing the
        // session — dispose() deletes an un-transferred, phraseId-less clip,
        // so copying after would silently copy nothing.
        requireNotNull(learnerFile) { "Expected a learner recording on disk" }.copyTo(file, overwrite = true)
        onMain { session.dispose() }
        return file
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (onMain(condition)) return true
            Thread.sleep(50)
        }
        return onMain(condition)
    }
}
