package com.root.app.reels

import com.root.app.content.Credits

/** One playable entry in a reel — built from a pack manifest's own phrase
 *  order (see [ReelsPlayer]'s doc), never re-sorted or shuffled. [source] is
 *  null when this phrase genuinely has no resolvable local recording yet;
 *  such a clip is still shown (its [credits]/[label] still display) but is
 *  skipped rather than played, and never crashes the pass. */
data class ReelClip(
    val id: String,
    val label: String,
    val credits: Credits?,
    val source: ReelAudioSource?,
)

/** Where a resolved reel clip's audio actually lives — mirrors
 *  [com.root.app.teach.LessonAudioSource] but kept reels-local so this player
 *  has no compile-time dependency on the teach feature. */
sealed interface ReelAudioSource {
    data class Bundled(val assetPath: String) : ReelAudioSource
    data class DownloadedFile(val absolutePath: String) : ReelAudioSource
}

/** Abstraction over whatever actually plays one clip's audio (backed by
 *  [com.root.app.audio.RootAudioSession] in production, a fake in tests) —
 *  this keeps [ReelsPlayer]'s ordering/single-pass/missing-clip state machine
 *  unit-testable on the JVM without any Android dependency. */
interface ReelPlaybackPort {
    /** Starts playing [source]; must call [onFinished] exactly once, when
     *  playback completes or fails — never synchronously before returning,
     *  to match real async `MediaPlayer` semantics. */
    fun play(source: ReelAudioSource, onFinished: () -> Unit)
    fun stop()
    fun setSpeed(value: Float)
}

enum class ReelsPlaybackSpeed(val multiplier: Float) { SLOW(0.75f), NORMAL(1f), FAST(1.25f) }

/**
 * Plays an ordered list of [ReelClip]s exactly once, start to finish, and
 * never on its own: nothing plays until [start] is explicitly called by a
 * user action, and reaching the end of the list leaves the player
 * [State.Finished] rather than looping back to clip zero (no background
 * autoplay, no accidental repeat). A clip with no resolvable [ReelClip.source]
 * is still visited (so its credit/label is seen) but produces no audio and
 * advances immediately, so a run of missing clips at the end of a reel still
 * reaches [State.Finished] rather than hanging.
 */
class ReelsPlayer(private val clips: List<ReelClip>, private val port: ReelPlaybackPort) {
    sealed interface State {
        object NotStarted : State
        data class Playing(val index: Int) : State
        /** [index] has no playable source — shown, not played. */
        data class Missing(val index: Int) : State
        object Finished : State
        object Stopped : State
    }

    var state: State = State.NotStarted
        private set
    var speed: ReelsPlaybackSpeed = ReelsPlaybackSpeed.NORMAL
        private set

    val currentClip: ReelClip? get() = (state as? State.Playing)?.index?.let(clips::getOrNull)
        ?: (state as? State.Missing)?.index?.let(clips::getOrNull)

    /** User-initiated only. A no-op if already started or the reel is empty
     *  (an empty reel goes straight to [State.Finished] — no clips, nothing
     *  to play, but also nothing left unattempted). */
    fun start() {
        if (state != State.NotStarted) return
        if (clips.isEmpty()) {
            state = State.Finished
            return
        }
        advanceTo(0)
    }

    fun stop() {
        if (state is State.Playing) port.stop()
        state = State.Stopped
    }

    fun setSpeed(value: ReelsPlaybackSpeed) {
        speed = value
        port.setSpeed(value.multiplier)
    }

    private fun advanceTo(index: Int) {
        if (index >= clips.size) {
            state = State.Finished
            return
        }
        val clip = clips[index]
        val source = clip.source
        if (source == null) {
            state = State.Missing(index)
            advanceTo(index + 1)
            return
        }
        state = State.Playing(index)
        port.setSpeed(speed.multiplier)
        port.play(source) {
            // A clip that finishes after the player was stopped or moved past
            // (a slow real-world completion callback racing a user's Stop tap)
            // must never resurrect playback or advance state.
            if (state == State.Playing(index)) advanceTo(index + 1)
        }
    }
}
