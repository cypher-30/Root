package com.root.app.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootSoundEngineTest {
    private class FakeBackend : SoundBackend {
        val loaded = RootSoundCue.entries.toMutableSet()
        val played = mutableListOf<Pair<RootSoundCue, Float>>()
        val stopped = mutableListOf<Int>()
        val volumes = mutableListOf<Float>()
        var nextStream = 1
        var failPlay = false
        var released = false
        override fun isLoaded(cue: RootSoundCue) = cue in loaded
        override fun play(cue: RootSoundCue, volume: Float): Int {
            if (failPlay) return 0
            played += cue to volume
            return nextStream++
        }
        override fun setVolume(streamId: Int, volume: Float) { volumes += volume }
        override fun stop(streamId: Int) { stopped += streamId }
        override fun release() { released = true }
    }

    private class FakeFocus : SoundFocus {
        var grant = true
        var held = false
        var requests = 0
        var onLoss: (() -> Unit)? = null
        override fun request(onLoss: () -> Unit): Boolean {
            requests++
            if (!grant) return false
            held = true
            this.onLoss = onLoss
            return true
        }
        override fun abandon() { held = false }
    }

    private class FakeScheduler : SoundScheduler {
        var now = 0L
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        override fun post(delayMs: Long, block: () -> Unit): () -> Unit {
            val task = (now + delayMs) to block
            tasks += task
            return { tasks.remove(task) }
        }
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = tasks.filter { it.first <= target }.minByOrNull { it.first } ?: break
                tasks.remove(next)
                now = next.first
                next.second()
            }
            now = target
        }
    }

    private val backend = FakeBackend()
    private val focus = FakeFocus()
    private val scheduler = FakeScheduler()
    private var settings = SoundSettings(effectsEnabled = true, startupEnabled = true, volume = 0.5f)
    private var conditions = SoundConditions(foreground = true, speechActive = false)
    private val changes = mutableListOf<RootSoundCue?>()
    private val engine = RootSoundEngine(
        backend, focus, scheduler, clock = { scheduler.now },
        settings = { settings }, conditions = { conditions }, onChange = { changes += it },
    )

    @After fun reset() = RootAudioCoordinator.resetForTest()

    @Test fun `plays once, holds focus, then cleans up after its duration`() {
        assertEquals(SoundResult.Played, engine.play(RootSoundCue.REVEAL, SoundRequest.AUTOMATIC))
        assertEquals(listOf(RootSoundCue.REVEAL to 0.5f), backend.played)
        assertTrue(focus.held)
        assertEquals(RootSoundCue.REVEAL, engine.current)
        scheduler.advance(RootSoundCue.REVEAL.durationMs + RootSoundEngine.TAIL_MARGIN_MS)
        assertNull(engine.current)
        assertFalse("focus is abandoned when the sound ends", focus.held)
        assertEquals(listOf(RootSoundCue.REVEAL, null), changes)
    }

    @Test fun `disabled automatic sound never touches the backend or focus`() {
        settings = SoundSettings(effectsEnabled = false)
        assertEquals(SoundResult.Suppressed(SoundSuppression.DISABLED), engine.play(RootSoundCue.REVEAL, SoundRequest.AUTOMATIC))
        assertTrue(backend.played.isEmpty())
        assertEquals(0, focus.requests)
    }

    @Test fun `automatic sounds are dropped while one plays and never queued`() {
        engine.play(RootSoundCue.RECALL_ACKNOWLEDGED, SoundRequest.AUTOMATIC)
        assertEquals(SoundResult.Suppressed(SoundSuppression.ALREADY_PLAYING), engine.play(RootSoundCue.REVEAL, SoundRequest.AUTOMATIC))
        scheduler.advance(RootSoundCue.RECALL_ACKNOWLEDGED.durationMs + RootSoundEngine.TAIL_MARGIN_MS)
        assertEquals(SoundResult.Played, engine.play(RootSoundCue.REVEAL, SoundRequest.AUTOMATIC))
        scheduler.advance(10_000)
        assertEquals(listOf(RootSoundCue.RECALL_ACKNOWLEDGED, RootSoundCue.REVEAL), backend.played.map { it.first })
    }

    @Test fun `cooldown applies between very short automatic sounds`() {
        engine.play(RootSoundCue.PAGE_TOUCH, SoundRequest.AUTOMATIC)
        scheduler.advance(RootSoundCue.PAGE_TOUCH.durationMs + RootSoundEngine.TAIL_MARGIN_MS)
        assertEquals(SoundResult.Suppressed(SoundSuppression.COOLDOWN), engine.play(RootSoundCue.REVEAL, SoundRequest.AUTOMATIC))
    }

    @Test fun `a preview replaces the playing sound and a stale timer cannot stop the next`() {
        engine.play(RootSoundCue.BRAND_A, SoundRequest.PREVIEW)
        scheduler.advance(100)
        engine.play(RootSoundCue.LESSON_SETTLED, SoundRequest.PREVIEW)
        assertEquals(listOf(1), backend.stopped)
        // BRAND_A's cleanup (due at 1,660 ms) was cancelled and must not end LESSON_SETTLED early.
        scheduler.advance(1_000)
        assertEquals(RootSoundCue.LESSON_SETTLED, engine.current)
        scheduler.advance(RootSoundEngine.TAIL_MARGIN_MS)
        assertNull(engine.current)
        assertFalse(focus.held)
    }

    @Test fun `denied focus and failed playback leave nothing held`() {
        focus.grant = false
        assertEquals(SoundResult.Suppressed(SoundSuppression.FOCUS_DENIED), engine.play(RootSoundCue.REVEAL, SoundRequest.AUTOMATIC))
        assertTrue(backend.played.isEmpty())
        focus.grant = true
        backend.failPlay = true
        assertTrue(engine.play(RootSoundCue.REVEAL, SoundRequest.AUTOMATIC) is SoundResult.Failed)
        assertFalse(focus.held)
        assertNull(engine.current)
    }

    @Test fun `focus loss stops immediately`() {
        engine.play(RootSoundCue.BRAND_A, SoundRequest.STARTUP)
        focus.onLoss!!.invoke()
        assertNull(engine.current)
        assertEquals(listOf(1), backend.stopped)
        assertFalse(focus.held)
    }

    @Test fun `fade steps the volume down then stops`() {
        engine.play(RootSoundCue.BRAND_B, SoundRequest.PREVIEW)
        engine.stop(fade = true)
        scheduler.advance(200)
        assertTrue(backend.volumes.isNotEmpty())
        assertTrue(backend.volumes.zipWithNext().all { (a, b) -> b < a })
        assertEquals(0f, backend.volumes.last())
        assertNull(engine.current)
        assertFalse(focus.held)
    }

    @Test fun `stopIf only stops the named sound`() {
        engine.play(RootSoundCue.REVEAL, SoundRequest.AUTOMATIC)
        engine.stopIf(RootSoundCue.BRAND_A)
        assertEquals(RootSoundCue.REVEAL, engine.current)
        engine.stopIf(RootSoundCue.REVEAL)
        assertNull(engine.current)
    }

    @Test fun `release stops and refuses later sounds`() {
        engine.play(RootSoundCue.REVEAL, SoundRequest.PREVIEW)
        engine.release()
        assertTrue(backend.released)
        assertNull(engine.current)
        assertTrue(engine.play(RootSoundCue.REVEAL, SoundRequest.PREVIEW) is SoundResult.Failed)
    }

    @Test fun `claiming speech stops effects and a stale release cannot clear another owner`() {
        val stopper: () -> Unit = { engine.stop() }
        RootAudioCoordinator.registerEffects(stopper)
        engine.play(RootSoundCue.BRAND_A, SoundRequest.PREVIEW)
        val speech = Any()
        RootAudioCoordinator.claimSpeech(speech)
        assertNull("speech stops the effect before preparing", engine.current)
        assertTrue(RootAudioCoordinator.speechActive)
        RootAudioCoordinator.releaseSpeech(Any())
        assertTrue(RootAudioCoordinator.speechActive)
        RootAudioCoordinator.releaseSpeech(speech)
        assertFalse(RootAudioCoordinator.speechActive)
        RootAudioCoordinator.unregisterEffects(stopper)
    }
}
