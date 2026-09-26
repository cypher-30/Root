package com.root.app.reels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake [ReelPlaybackPort] whose [play] only calls back when [finish] is
 *  invoked from the test — models a real async player without any Android
 *  dependency, so [ReelsPlayerTest] can assert exact ordering/timing. */
private class FakePort : ReelPlaybackPort {
    var playing: ReelAudioSource? = null
    var playCount = 0
    var stopCount = 0
    var lastSpeed = 1f
    var lastPlaySpeed: Float? = null
    private var pendingFinish: ((ReelPlaybackResult) -> Unit)? = null
    var staleFinish: ((ReelPlaybackResult) -> Unit)? = null

    override fun play(source: ReelAudioSource, speed: Float, onFinished: (ReelPlaybackResult) -> Unit) {
        playing = source
        playCount++
        lastPlaySpeed = speed
        pendingFinish = onFinished
    }

    override fun stop() {
        stopCount++
        playing = null
        staleFinish = pendingFinish
        pendingFinish = null
    }

    override fun setSpeed(value: Float) {
        lastSpeed = value
    }

    fun finish(result: ReelPlaybackResult = ReelPlaybackResult.COMPLETED) {
        val callback = pendingFinish
        pendingFinish = null
        playing = null
        callback?.invoke(result)
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
        assertEquals(3, port.playCount)
    }

    @Test fun neverPlaysBeforeStartIsExplicitlyCalled() {
        val port = FakePort()
        val player = ReelsPlayer(listOf(clip("a")), port)
        assertEquals(ReelsPlayer.State.NotStarted, player.state)
        assertNull(port.playing)
    }

    @Test fun missingClipIsListedAndSkippedWithoutCrashing() {
        val port = FakePort()
        val player = ReelsPlayer(listOf(clip("a", hasSource = false), clip("b")), port)
        assertEquals(listOf("a"), player.missingClips.map { it.id })
        assertEquals(1, player.playableCount)
        player.start()
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
        player.stop()
        assertEquals(ReelsPlayer.State.Stopped, player.state)
        assertEquals(1, port.stopCount)

        // A late completion callback racing the Stop tap must not resurrect playback.
        port.staleFinish?.invoke(ReelPlaybackResult.COMPLETED)
        assertEquals(ReelsPlayer.State.Stopped, player.state)
        assertEquals(1, port.playCount)
    }

    @Test fun setSpeedAppliesImmediatelyAndPersistsAcrossClips() {
        val port = FakePort()
        val player = ReelsPlayer(listOf(clip("a"), clip("b")), port)
        player.setSpeed(ReelsPlaybackSpeed.FAST)
        assertEquals(1.25f, port.lastSpeed)
        player.start()
        assertEquals(1.25f, port.lastPlaySpeed)
        port.finish()
        // The chosen speed is requested again for the next clip without re-selecting it.
        assertEquals(1.25f, port.lastPlaySpeed)
        assertEquals(ReelsPlaybackSpeed.FAST, player.snapshot.value.speed)
    }

    @Test fun replayStartsAFreshSinglePassAfterFinishing() {
        val port = FakePort()
        val player = ReelsPlayer(listOf(clip("a"), clip("b")), port)
        player.start()
        port.finish()
        port.finish()
        assertEquals(ReelsPlayer.State.Finished, player.state)

        player.replay()
        assertEquals(ReelsPlayer.State.Playing(0), player.state)
        port.finish()
        port.finish()
        assertEquals(ReelsPlayer.State.Finished, player.state)
        assertEquals(4, port.playCount)
    }

    @Test fun replayAfterStopIgnoresTheStoppedPassLateCallback() {
        val port = FakePort()
        val player = ReelsPlayer(listOf(clip("a"), clip("b")), port)
        player.start()
        player.stop()
        val stale = port.staleFinish
        player.replay()
        assertEquals(ReelsPlayer.State.Playing(0), player.state)
        // The earlier pass's callback for the same index must not advance the new pass.
        stale?.invoke(ReelPlaybackResult.COMPLETED)
        assertEquals(ReelsPlayer.State.Playing(0), player.state)
    }

    @Test fun replayIsIgnoredBeforeFirstStartAndWhilePlaying() {
        val port = FakePort()
        val player = ReelsPlayer(listOf(clip("a")), port)
        player.replay()
        assertEquals(ReelsPlayer.State.NotStarted, player.state)
        player.start()
        player.replay()
        assertEquals(1, port.playCount)
    }

    @Test fun interruptionStopsTheReelWithoutAdvancingOrResuming() {
        val port = FakePort()
        val player = ReelsPlayer(listOf(clip("a"), clip("b")), port)
        player.start()
        port.finish(ReelPlaybackResult.INTERRUPTED)
        assertEquals(ReelsPlayer.State.Stopped, player.state)
        assertEquals(1, port.playCount)
    }

    @Test fun failedClipIsRecordedAndTheReelAdvances() {
        val port = FakePort()
        val player = ReelsPlayer(listOf(clip("a"), clip("b")), port)
        player.start()
        port.finish(ReelPlaybackResult.FAILED)
        assertEquals(ReelsPlayer.State.Playing(1), player.state)
        assertTrue("a" in player.snapshot.value.failedClipIds)
        port.finish()
        assertEquals(ReelsPlayer.State.Finished, player.state)
        // A replay clears the previous pass's failures.
        player.replay()
        assertTrue(player.snapshot.value.failedClipIds.isEmpty())
    }

    @Test fun snapshotExposesTheCurrentClip() {
        val port = FakePort()
        val player = ReelsPlayer(listOf(clip("a"), clip("b")), port)
        assertNull(player.snapshot.value.currentClip)
        player.start()
        assertEquals("a", player.snapshot.value.currentClip?.id)
        port.finish()
        assertEquals("b", player.snapshot.value.currentClip?.id)
        port.finish()
        assertNull(player.snapshot.value.currentClip)
    }
}
