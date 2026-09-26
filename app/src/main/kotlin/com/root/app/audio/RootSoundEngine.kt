package com.root.app.audio

/** Low-latency sample playback (SoundPool in production). */
internal interface SoundBackend {
    fun isLoaded(cue: RootSoundCue): Boolean
    /** Returns a non-zero stream id, or 0 if playback could not start. */
    fun play(cue: RootSoundCue, volume: Float): Int
    fun setVolume(streamId: Int, volume: Float)
    fun stop(streamId: Int)
    fun release()
}

/** Brief transient audio focus. [request] must return true only for an
 *  immediate grant (never delayed focus); [onLoss] fires on any loss. */
internal interface SoundFocus {
    fun request(onLoss: () -> Unit): Boolean
    fun abandon()
}

/** Delayed main-thread work; the returned function cancels it. */
internal fun interface SoundScheduler {
    fun post(delayMs: Long, block: () -> Unit): () -> Unit
}

/**
 * Plays at most one interaction sound at a time, following [RootSoundPolicy].
 * Nothing is queued or played late: a request that cannot play right now is
 * dropped with a reason. SoundPool reports no completion, so cleanup (and focus
 * release) is timed from the cue's duration and guarded by a generation token,
 * so an old timer can never stop or release a newer sound.
 *
 * Main-thread only. Platform access is behind small seams for unit tests.
 */
internal class RootSoundEngine(
    private val backend: SoundBackend,
    private val focus: SoundFocus,
    private val scheduler: SoundScheduler,
    private val clock: () -> Long,
    private val settings: () -> SoundSettings,
    private val conditions: () -> SoundConditions,
    private val log: (String) -> Unit = {},
    private val onChange: (RootSoundCue?) -> Unit = {},
) {
    private var generation = 0L
    private var streamId = 0
    private var lastStartMs: Long? = null
    private var cancelCleanup: (() -> Unit)? = null
    private var released = false

    var current: RootSoundCue? = null
        private set

    fun play(cue: RootSoundCue, request: SoundRequest): SoundResult {
        if (released) return SoundResult.Failed("Sounds are unavailable.")
        val now = clock()
        val prefs = settings()
        val reason = RootSoundPolicy.suppression(
            request = request,
            settings = prefs,
            conditions = conditions(),
            loaded = backend.isLoaded(cue),
            busy = current != null,
            sinceLastStartMs = lastStartMs?.let { now - it },
        )
        if (reason != null) return SoundResult.Suppressed(reason)
        // Only an explicit preview gets here while another sound plays.
        if (current != null) stopNow()
        if (!focus.request(::onFocusLost)) return SoundResult.Suppressed(SoundSuppression.FOCUS_DENIED)
        val stream = backend.play(cue, prefs.volume.coerceIn(0f, 1f))
        if (stream == 0) {
            focus.abandon()
            log("Could not play ${cue.name}")
            return SoundResult.Failed("This sound couldn't be played.")
        }
        val token = ++generation
        streamId = stream
        lastStartMs = now
        current = cue
        cancelCleanup = scheduler.post(cue.durationMs + TAIL_MARGIN_MS) {
            if (token == generation) finish()
        }
        onChange(cue)
        return SoundResult.Played
    }

    /** Stops whatever is playing. [fade] is for a user-requested preview stop;
     *  speech, privacy, and lifecycle stops are immediate. */
    fun stop(fade: Boolean = false) {
        if (current == null) return
        if (!fade) {
            stopNow()
            return
        }
        val token = generation
        val stream = streamId
        val volume = settings().volume.coerceIn(0f, 1f)
        cancelCleanup?.invoke()
        fun step(index: Int) {
            if (token != generation) return
            if (index >= FADE_STEPS) {
                stopNow()
                return
            }
            backend.setVolume(stream, volume * (FADE_STEPS - 1 - index) / FADE_STEPS)
            cancelCleanup = scheduler.post(FADE_STEP_MS) { step(index + 1) }
        }
        step(0)
    }

    /** Stops only if [cue] is the one playing (for example the launch motif
     *  when the introduction is skipped). */
    fun stopIf(cue: RootSoundCue, fade: Boolean = false) {
        if (current == cue) stop(fade)
    }

    fun release() {
        stopNow()
        released = true
        backend.release()
    }

    private fun onFocusLost() = stopNow()

    private fun stopNow() {
        if (current == null) return
        generation++
        cancelCleanup?.invoke()
        cancelCleanup = null
        backend.stop(streamId)
        finishState()
    }

    private fun finish() {
        cancelCleanup = null
        finishState()
    }

    private fun finishState() {
        streamId = 0
        current = null
        focus.abandon()
        onChange(null)
    }

    companion object {
        const val TAIL_MARGIN_MS = 60L
        private const val FADE_STEPS = 4
        private const val FADE_STEP_MS = 20L
    }
}
