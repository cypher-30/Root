package com.root.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootSoundPolicyTest {
    private val on = SoundSettings(effectsEnabled = true, startupEnabled = true, volume = 0.35f)
    private val clear = SoundConditions(foreground = true, speechActive = false)

    private fun decide(
        request: SoundRequest = SoundRequest.AUTOMATIC,
        settings: SoundSettings = on,
        conditions: SoundConditions = clear,
        loaded: Boolean = true,
        busy: Boolean = false,
        sinceLast: Long? = null,
    ) = RootSoundPolicy.suppression(request, settings, conditions, loaded, busy, sinceLast)

    private val off = SoundSettings(effectsEnabled = false, startupEnabled = false)

    @Test fun `defaults turn sound effects and the startup motif on at full volume`() {
        val defaults = SoundSettings()
        assertTrue(defaults.effectsEnabled)
        assertTrue(defaults.startupEnabled)
        assertEquals(1f, defaults.volume, 0f)
        assertNull(decide(settings = defaults))
        assertNull(decide(SoundRequest.STARTUP, settings = defaults))
    }

    @Test fun `turning effects off silences every automatic sound`() {
        assertEquals(SoundSuppression.DISABLED, decide(settings = off))
        assertEquals(SoundSuppression.DISABLED, decide(SoundRequest.STARTUP, settings = off))
    }

    @Test fun `startup needs both switches`() {
        assertEquals(SoundSuppression.STARTUP_DISABLED, decide(SoundRequest.STARTUP, settings = on.copy(startupEnabled = false)))
        assertEquals(SoundSuppression.DISABLED, decide(SoundRequest.STARTUP, settings = on.copy(effectsEnabled = false)))
        assertNull(decide(SoundRequest.STARTUP))
    }

    @Test fun `preview ignores the switches but not the volume`() {
        assertNull(decide(SoundRequest.PREVIEW, settings = off))
        assertEquals(SoundSuppression.MUTED_VOLUME, decide(SoundRequest.PREVIEW, settings = on.copy(volume = 0f)))
    }

    @Test fun `every quiet condition suppresses previews too`() {
        val cases = mapOf(
            clear.copy(foreground = false) to SoundSuppression.BACKGROUND,
            clear.copy(speechActive = true) to SoundSuppression.SPEECH_ACTIVE,
            clear.copy(known = false) to SoundSuppression.STATE_UNKNOWN,
            clear.copy(inCall = true) to SoundSuppression.IN_CALL,
            clear.copy(ringerSilent = true) to SoundSuppression.RINGER_SILENT,
            clear.copy(doNotDisturb = true) to SoundSuppression.DO_NOT_DISTURB,
            clear.copy(deviceVolumeZero = true) to SoundSuppression.DEVICE_VOLUME_ZERO,
            clear.copy(otherMediaActive = true) to SoundSuppression.OTHER_MEDIA,
        )
        cases.forEach { (conditions, expected) ->
            assertEquals(expected, decide(SoundRequest.AUTOMATIC, conditions = conditions))
            assertEquals(expected, decide(SoundRequest.PREVIEW, settings = off, conditions = conditions))
        }
    }

    @Test fun `speech wins over device state`() {
        assertEquals(SoundSuppression.SPEECH_ACTIVE, decide(conditions = clear.copy(speechActive = true, known = false)))
    }

    @Test fun `unloaded sounds are dropped not delayed`() {
        assertEquals(SoundSuppression.NOT_READY, decide(loaded = false))
        assertEquals(SoundSuppression.NOT_READY, decide(SoundRequest.PREVIEW, loaded = false))
    }

    @Test fun `automatic sounds never overlap or crowd`() {
        assertEquals(SoundSuppression.ALREADY_PLAYING, decide(busy = true))
        assertEquals(SoundSuppression.COOLDOWN, decide(sinceLast = RootSoundPolicy.MIN_GAP_MS - 1))
        assertNull(decide(sinceLast = RootSoundPolicy.MIN_GAP_MS))
        assertNull(decide(SoundRequest.PREVIEW, busy = true, sinceLast = 0))
    }

    @Test fun `event gate lets each moment sound once and stays bounded`() {
        val gate = SoundEventGate(capacity = 2)
        assertTrue(gate.firstTime("recall:s:e1"))
        assertFalse(gate.firstTime("recall:s:e1"))
        assertTrue(gate.firstTime("recall:s:e2"))
        assertTrue(gate.firstTime("recall:s:e3"))
        assertTrue("the oldest key is evicted past capacity", gate.firstTime("recall:s:e1"))
    }

    @Test fun `late events are stale`() {
        val event = RootSoundEvent(RootSoundCue.REVEAL, createdAtMs = 1_000)
        assertFalse(event.isStale(1_000 + RootSoundEvent.MAX_AGE_MS))
        assertTrue(event.isStale(1_001 + RootSoundEvent.MAX_AGE_MS))
    }

    @Test fun `every cue stays short and only intended cues are pilots`() {
        RootSoundCue.entries.forEach {
            val limit = if (it == RootSoundCue.STARTUP_MOTIF) 3_000L else 2_500L
            assertTrue(it.name, it.durationMs in 60..limit)
        }
        assertEquals(
            setOf(RootSoundCue.ROOT_GROWTH, RootSoundCue.REVEAL, RootSoundCue.RECALL_ACKNOWLEDGED,
                RootSoundCue.ANSWER_CORRECT, RootSoundCue.LESSON_SETTLED),
            RootSoundCue.entries.filter { it.pilot }.toSet(),
        )
        assertEquals("the startup sound is the one scored to the launch", RootSoundCue.ROOT_GROWTH, RootSoundCue.STARTUP_MOTIF)
        assertTrue("earlier motifs stay available for comparison",
            RootSoundCue.entries.filter { it.isMotif }.none { it.pilot })
    }
}
