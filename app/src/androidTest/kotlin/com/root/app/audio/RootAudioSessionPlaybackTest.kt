package com.root.app.audio

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Instrumented coverage for the `audio-core` package's typed playback state:
 * a real recorded clip's [RootAudioSession.durationMs] becomes known once the
 * player prepares, [RootAudioSession.positionMs] advances and is seekable via
 * [RootAudioSession.seekTo], and [RootAudioSession.setSpeed] both takes effect
 * and is rejected for unsupported values, all without ever touching the
 * recall scheduler (this suite records/plays only — no attempts are made).
 */
class RootAudioSessionPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun grantMicrophone() {
        // executeShellCommand's pipe must be drained to EOF, not merely
        // closed — the grant is not guaranteed applied until the command's
        // own output has been fully read, so an unread pipe is a real race
        // against the very first startRecording() call below.
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${instrumentation.targetContext.packageName} android.permission.RECORD_AUDIO",
        )
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    @Test fun recordedClipReportsDurationAndSupportsSeekAndSpeed() {
        val session = onMain { RootAudioSession(instrumentation.targetContext) }

        onMain { session.startRecording() }
        assertTrue("recording must actually start (mic permission/hardware)", waitUntil { session.isRecording })
        Thread.sleep(1_200)
        onMain { session.finishRecording() }
        assertTrue("a long-enough take must be kept", waitUntil { session.hasRecording })

        onMain { session.playRecording() }
        assertTrue("playing state must be set immediately", waitUntil { session.playing == AudioClipKind.RECORDING })
        assertTrue("player must prepare and report a real duration", waitUntil { (session.durationMs ?: 0) > 0 })
        val duration = session.durationMs!!

        assertTrue("position must advance while playing", waitUntil { session.positionMs > 0 })

        onMain { session.seekTo(duration / 2) }
        waitUntil { session.positionMs in (duration / 2 - 300)..(duration / 2 + 300) }
        assertTrue(
            "seekTo must land near the requested position",
            session.positionMs in 0..duration,
        )

        onMain { session.setSpeed(1.25f) }
        assertEquals(1.25f, session.playbackSpeed, 0.0f)

        var rejected = false
        onMain {
            try {
                session.setSpeed(0.9f)
            } catch (error: IllegalArgumentException) {
                rejected = true
            }
        }
        assertTrue("an unlisted speed must be rejected, not silently clamped", rejected)
        assertEquals("speed must be unchanged after the rejected call", 1.25f, session.playbackSpeed, 0.0f)

        onMain { session.stopPlayback() }
        assertNull(session.playing)
        assertNull(session.durationMs)

        onMain { session.dispose() }
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
