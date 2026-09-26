package com.root.app.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Android owner of Root's interaction sounds: a single-stream [SoundPool]
 * preloaded with [RootSoundCue]s, sonification audio attributes, a brief
 * transient focus request, and a quiet-state sample taken right before each
 * play. Speech playback/recording always wins through [RootAudioCoordinator].
 *
 * Main-thread only. Create once per Activity content and [release] on disposal.
 */
class RootSoundPlayer internal constructor(
    context: Context,
    private val settings: () -> SoundSettings,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val notifications = appContext.getSystemService(NotificationManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build()
    private val soundIds = mutableMapOf<RootSoundCue, Int>()
    private val loadedIds = mutableSetOf<Int>()
    private var focusRequest: AudioFocusRequest? = null

    /** The cue currently playing, observable by Compose (Sound Lab state). */
    var playing by mutableStateOf<RootSoundCue?>(null)
        private set

    /** Set by the Activity lifecycle; leaving the foreground stops sound. */
    var foreground: Boolean = false
        set(value) {
            field = value
            if (!value) engine.stop()
        }

    private val backend = object : SoundBackend {
        override fun isLoaded(cue: RootSoundCue): Boolean = soundIds[cue]?.let { it in loadedIds } == true
        override fun play(cue: RootSoundCue, volume: Float): Int {
            val id = soundIds[cue] ?: return 0
            return pool.play(id, volume, volume, 1, 0, 1f)
        }
        override fun setVolume(streamId: Int, volume: Float) = pool.setVolume(streamId, volume, volume)
        override fun stop(streamId: Int) { if (streamId != 0) pool.stop(streamId) }
        override fun release() = pool.release()
    }

    private val focus = object : SoundFocus {
        override fun request(onLoss: () -> Unit): Boolean {
            abandon()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener({ change -> if (change < 0) onLoss() }, handler)
                .build()
            val granted = try {
                audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } catch (error: RuntimeException) {
                Log.w(TAG, "Audio focus request failed", error)
                false
            }
            if (granted) focusRequest = request
            return granted
        }

        override fun abandon() {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

    private val engine = RootSoundEngine(
        backend = backend,
        focus = focus,
        scheduler = { delayMs, block ->
            val runnable = Runnable(block)
            handler.postDelayed(runnable, delayMs)
            ({ handler.removeCallbacks(runnable) })
        },
        clock = SystemClock::elapsedRealtime,
        settings = settings,
        conditions = ::sampleConditions,
        log = { Log.w(TAG, it) },
        onChange = { playing = it },
    )

    private val stopForSpeech: () -> Unit = { engine.stop() }

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedIds += sampleId else Log.w(TAG, "Could not load sound sample $sampleId")
        }
        RootSoundCue.entries.forEach { cue -> soundIds[cue] = pool.load(appContext, cue.resId, 1) }
        RootAudioCoordinator.registerEffects(stopForSpeech)
    }

    val allLoaded: Boolean get() = RootSoundCue.entries.all(backend::isLoaded)

    fun play(cue: RootSoundCue, request: SoundRequest): SoundResult = engine.play(cue, request)

    fun stop(fade: Boolean = false) = engine.stop(fade)

    fun stopIf(cue: RootSoundCue, fade: Boolean = false) = engine.stopIf(cue, fade)

    fun release() {
        RootAudioCoordinator.unregisterEffects(stopForSpeech)
        engine.release()
    }

    private fun sampleConditions(): SoundConditions {
        val base = SoundConditions(foreground = foreground, speechActive = RootAudioCoordinator.speechActive)
        return try {
            val filter = notifications.currentInterruptionFilter
            base.copy(
                known = filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
                inCall = audioManager.mode != AudioManager.MODE_NORMAL,
                ringerSilent = audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL,
                doNotDisturb = filter != NotificationManager.INTERRUPTION_FILTER_ALL,
                deviceVolumeZero = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM) == 0,
                otherMediaActive = audioManager.isMusicActive,
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not read device sound state", error)
            base.copy(known = false)
        }
    }

    private companion object {
        const val TAG = "RootSound"
    }
}
