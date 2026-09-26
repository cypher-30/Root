package com.root.app.audio

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.compose.animation.core.CubicBezierEasing
import com.root.app.ui.motion.RootMotion
import com.root.app.ui.root.RootGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The catalogue's timed cleanup relies on [RootSoundCue.durationMs] matching
 *  the shipped WAVs; this reads each raw resource's header to prove it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootSoundCatalogueTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `every cue resource is short mono 48k PCM matching its catalogue duration`() {
        var total = 0L
        RootSoundCue.entries.forEach { cue ->
            val bytes = context.resources.openRawResource(cue.resId).use { it.readBytes() }
            total += bytes.size
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertEquals("${cue.name} PCM", 1, header.getShort(20).toInt())
            assertEquals("${cue.name} mono", 1, header.getShort(22).toInt())
            assertEquals("${cue.name} rate", 48_000, header.getInt(24))
            assertEquals("${cue.name} bits", 16, header.getShort(34).toInt())
            val dataBytes = header.getInt(40)
            val durationMs = dataBytes / 2 * 1000L / 48_000
            assertEquals("${cue.name} duration", cue.durationMs, durationMs)
        }
        assertTrue("all sounds together stay under 1 MiB", total <= 1L shl 20)
    }

    /** ROOT_GROWTH is scored to the launch animation (see tools/audio/generate_root_sounds.py,
     *  LAUNCH_*). If any of these change, re-time the recipe, regenerate, then update this test. */
    @Test fun `startup sound stays scored to the launch animation`() {
        assertEquals(2_600, RootMotion.launchMillis)
        assertEquals(350L, RootMotion.launchHoldMillis)
        assertEquals(CubicBezierEasing(0.3f, 0.05f, 0.25f, 1f), RootMotion.launchEase)
        assertEquals(5, RootGeometry.branches().size)
        assertEquals("the sound rings out exactly as the launch screen leaves",
            RootMotion.launchMillis + RootMotion.launchHoldMillis, RootSoundCue.STARTUP_MOTIF.durationMs)
    }
}
