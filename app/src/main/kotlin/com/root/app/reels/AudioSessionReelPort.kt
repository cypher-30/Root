package com.root.app.reels

import com.root.app.audio.PlaybackEnd
import com.root.app.audio.RootAudioSession

/** Production [ReelPlaybackPort]: plays each clip through a lifecycle-owned
 *  [RootAudioSession] and maps its explicit end-of-playback outcome, so an
 *  interruption (focus loss, backgrounding) is never mistaken for completion. */
internal class AudioSessionReelPort(private val session: RootAudioSession) : ReelPlaybackPort {
    override fun play(source: ReelAudioSource, speed: Float, onFinished: (ReelPlaybackResult) -> Unit) {
        val path = when (source) {
            is ReelAudioSource.Bundled -> source.assetPath
            is ReelAudioSource.DownloadedFile -> source.absolutePath
        }
        session.playClip(path, speed) { end ->
            onFinished(
                when (end) {
                    PlaybackEnd.COMPLETED -> ReelPlaybackResult.COMPLETED
                    PlaybackEnd.FAILED -> ReelPlaybackResult.FAILED
                    PlaybackEnd.INTERRUPTED -> ReelPlaybackResult.INTERRUPTED
                },
            )
        }
    }

    override fun stop() = session.stopPlayback()

    override fun setSpeed(value: Float) = session.setSpeed(value)
}
