package com.root.app.reels

import com.root.app.content.Credits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One playable entry in a reel — built from a pack manifest's own phrase
 *  order (see [ReelsPlayer]'s doc), never re-sorted or shuffled. [source] is
 *  null when this phrase genuinely has no resolvable local recording yet;
 *  such a clip is still listed (its [credits]/[label] still display) but is
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

/** How one clip's playback ended, as reported by a [ReelPlaybackPort]. */
enum class ReelPlaybackResult {
    /** The clip played to its end; the reel advances. */
    COMPLETED,
    /** The clip could not be played; it is recorded as failed and the reel advances. */
    FAILED,
    /** Playback was cut off (focus loss, backgrounding, explicit stop); the reel stops and never resumes on its own. */
    INTERRUPTED,
}

/** Abstraction over whatever actually plays one clip's audio (backed by
 *  [com.root.app.audio.RootAudioSession] in production, a fake in tests) —
 *  this keeps [ReelsPlayer]'s ordering/single-pass/missing-clip state machine
 *  unit-testable on the JVM without any Android dependency. */
interface ReelPlaybackPort {
    /** Starts playing [source] at [speed]; must call [onFinished] exactly once,
     *  when playback completes, fails, or is interrupted. */
    fun play(source: ReelAudioSource, speed: Float, onFinished: (ReelPlaybackResult) -> Unit)
    fun stop()
    fun setSpeed(value: Float)
}

enum class ReelsPlaybackSpeed(val multiplier: Float) { SLOW(0.75f), NORMAL(1f), FAST(1.25f) }

/**
 * Plays an ordered list of [ReelClip]s exactly once, start to finish, and
 * never on its own: nothing plays until [start] or [replay] is explicitly
 * called by a user action, and reaching the end of the list leaves the player
 * [State.Finished] rather than looping back to clip zero. A clip with no
 * resolvable [ReelClip.source] is listed in [missingClips] and skipped, so a
 * run of missing clips at the end of a reel still reaches [State.Finished].
 * An interruption leaves the reel [State.Stopped]; it never auto-resumes.
 *
 * All state is exposed through [snapshot] so a UI can observe every
 * transition instead of reading plain fields that never trigger recomposition.
 */
class ReelsPlayer(private val clips: List<ReelClip>, private val port: ReelPlaybackPort) {
    sealed interface State {
        object NotStarted : State
        data class Playing(val index: Int) : State
        object Finished : State
        object Stopped : State
    }

    data class Snapshot(
        val state: State,
        val speed: ReelsPlaybackSpeed,
        val currentClip: ReelClip?,
        val failedClipIds: Set<String>,
    )

    /** Clips with no playable local recording, in manifest order. */
    val missingClips: List<ReelClip> = clips.filter { it.source == null }
    val playableCount: Int = clips.size - missingClips.size
    val clipCount: Int = clips.size

    private val mutableSnapshot = MutableStateFlow(
        Snapshot(State.NotStarted, ReelsPlaybackSpeed.NORMAL, null, emptySet()),
    )
    val snapshot: StateFlow<Snapshot> = mutableSnapshot.asStateFlow()

    val state: State get() = mutableSnapshot.value.state
    val speed: ReelsPlaybackSpeed get() = mutableSnapshot.value.speed
    val currentClip: ReelClip? get() = mutableSnapshot.value.currentClip

    // Identifies the in-flight play request so a late callback from an earlier
    // clip (or an earlier pass, after Replay) can never advance the current one.
    private var playToken = 0

    /** User-initiated only. A no-op unless the reel has never started. */
    fun start() {
        if (state != State.NotStarted) return
        beginPass()
    }

    /** User-initiated fresh single pass from the first clip, after the reel
     *  finished or was stopped. A no-op while playing or before first start. */
    fun replay() {
        if (state != State.Finished && state != State.Stopped) return
        beginPass()
    }

    fun stop() {
        if (state !is State.Playing) return
        playToken++
        update(State.Stopped)
        port.stop()
    }

    fun setSpeed(value: ReelsPlaybackSpeed) {
        mutableSnapshot.value = mutableSnapshot.value.copy(speed = value)
        port.setSpeed(value.multiplier)
    }

    private fun beginPass() {
        mutableSnapshot.value = mutableSnapshot.value.copy(failedClipIds = emptySet())
        advanceFrom(0)
    }

    private fun advanceFrom(start: Int) {
        var index = start
        while (index < clips.size && clips[index].source == null) index++
        if (index >= clips.size) {
            update(State.Finished)
            return
        }
        val clip = clips[index]
        val token = ++playToken
        update(State.Playing(index), clip)
        port.play(requireNotNull(clip.source), speed.multiplier) { result ->
            if (token != playToken || state != State.Playing(index)) return@play
            when (result) {
                ReelPlaybackResult.COMPLETED -> advanceFrom(index + 1)
                ReelPlaybackResult.FAILED -> {
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        failedClipIds = mutableSnapshot.value.failedClipIds + clip.id,
                    )
                    advanceFrom(index + 1)
                }
                ReelPlaybackResult.INTERRUPTED -> {
                    playToken++
                    update(State.Stopped)
                }
            }
        }
    }

    private fun update(state: State, clip: ReelClip? = null) {
        mutableSnapshot.value = mutableSnapshot.value.copy(state = state, currentClip = clip)
    }
}
