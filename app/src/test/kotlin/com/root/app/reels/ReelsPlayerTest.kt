package com.root.app.reels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fake [ReelPlaybackPort] whose [play] only calls back when [finish] is
 *  invoked from the test — models a real async player without any Android
 *  dependency, so [ReelsPlayerTest] can assert exact ordering/timing. */
private class FakePort : ReelPlaybackPort {
    var playing: ReelAudioSource? = null
    var stopCount = 0
    var lastSpeed = 1f
    private var pendingFinish: (() -> Unit)? = null

    override fun play(source: ReelAudioSource, onFinished: () -> Unit) {
        playing = source
        pendingFinish = onFinished
    }

    override fun stop() {
        stopCount++
        playing = null
        pendingFinish = null
    }

    override fun setSpeed(value: Float) {
        lastSpeed = value
    }

    fun finish() {
        val callback = pendingFinish
        pendingFinish = null
        playing = null
        callback?.invoke()
    }
}

class ReelsPlayerTest {
    private fun clip(id: String, hasSource: Boolean = true) = ReelClip(
        id = id, label = id, credits = null,
        source = if (hasSource) ReelAudioSource.Bundled("$id.mp3") else null,
    )

    @Test fun playsClipsInManifestOrderAndFinishesWithoutLooping() {
        val port = FakePort()
        val player = ReelsPlayer(listOf(clip("a"), clip("b"), clip("c")), port)

        player.start()
        assertEquals(ReelsPlayer.State.Playing(0), player.state)
        port.finish()
        assertEquals(ReelsPlayer.State.Playing(1), player.state)
        port.finish()
        assertEquals(ReelsPlayer.State.Playing(2), player.state)
        port.finish()
        assertEquals(ReelsPlayer.State.Finished, player.state)

        // A finished reel never restarts or loops on its own.
        assertNull(port.playing)
    }

    @Test fun neverPlaysBeforeStartIsExplicitlyCalled() {
        val port = FakePort()
        val player = ReelsPlayer(listOf(clip("a")), port)
        assertEquals(ReelsPlayer.State.NotStarted, player.state)
        assertNull(port.playing)
    }

    @Test fun missingClipIsShownButSkippedWithoutCrashing() {
        val port = FakePort()
        val player = ReelsPlayer(listOf(clip("a", hasSource = false), clip("b")), port)
        player.start()
        // The missing clip is visited (so its credit is seen) but nothing plays for it.
        assertEquals(ReelsPlayer.State.Playing(1), player.state)
        assertEquals(ReelAudioSource.Bundled("b.mp3"), port.playing)
    }

    @Test fun trailingMissingClipsStillReachFinished() {
        val port = FakePort()
        val player = ReelsPlayer(listOf(clip("a"), clip("b", hasSource = false), clip("c", hasSource = false)), port)
        player.start()
        assertEquals(ReelsPlayer.State.Playing(0), player.state)
        port.finish()
        assertEquals(ReelsPlayer.State.Finished, player.state)
    }

    @Test fun emptyReelFinishesImmediatelyWithoutPlayingAnything() {
        val port = FakePort()
        val player = ReelsPlayer(emptyList(), port)
        player.start()
        assertEquals(ReelsPlayer.State.Finished, player.state)
        assertNull(port.playing)
    }

    @Test fun stopHaltsPlaybackAndIgnoresALateFinishCallback() {
        val port = FakePort()
        val player = ReelsPlayer(listOf(clip("a"), clip("b")), port)
        player.start()
        assertEquals(ReelsPlayer.State.Playing(0), player.state)
        player.stop()
        assertEquals(ReelsPlayer.State.Stopped, player.state)
        assertEquals(1, port.stopCount)

        // A late completion callback racing the Stop tap must not resurrect playback.
        port.finish()
        assertEquals(ReelsPlayer.State.Stopped, player.state)
    }

    @Test fun setSpeedAppliesImmediatelyAndPersistsAcrossClips() {
        val port = FakePort()
        val player = ReelsPlayer(listOf(clip("a"), clip("b")), port)
        player.setSpeed(ReelsPlaybackSpeed.FAST)
        assertEquals(1.25f, port.lastSpeed)
        player.start()
        assertEquals(1.25f, port.lastSpeed)
        port.finish()
        // The chosen speed carries over to the next clip without re-selecting it.
        assertEquals(1.25f, port.lastSpeed)
    }
}
