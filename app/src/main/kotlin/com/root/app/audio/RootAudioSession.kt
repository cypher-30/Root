package com.root.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Which clip a [RootAudioSession] is currently playing — reference (a
 *  catalog/contributed speaker recording) or the learner's own take. Kept as
 *  a typed enum (not a raw string) so a future third clip kind cannot be
 *  silently confused with an existing one at any call site. */
internal enum class AudioClipKind { REFERENCE, RECORDING }

/** How one [RootAudioSession.playReference] call ended, delivered exactly once
 *  to its optional listener. [INTERRUPTED] covers explicit stops, focus loss,
 *  lifecycle pauses, and disposal — a caller must never treat it as completion. */
internal enum class PlaybackEnd { COMPLETED, FAILED, INTERRUPTED }

/**
 * Main-thread audio owner. A practice session retains its learner clip; a contribution
 * owns its draft until the database save succeeds. No recording leaves app storage.
 */
internal class RootAudioSession(context: Context, private val phraseId: String? = null) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private val root = File(this.context.filesDir, "root_audio")
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var recordingFile: File? = null
    private var clipState by mutableStateOf(phraseId?.let { learnerFile(it).takeIf(File::isFile) })
    private var clip: File?
        get() = clipState
        set(value) { clipState = value }
    // A completed contribution take that a durable draft now references must
    // survive this session's disposal; see [retainClipForDraft].
    private var retainClip = false
    private var movedFromDraft: File? = null
    private var startedAt = 0L
    private var disposed = false
    private var saving = false
    private var transferred = false
    private var focusRequest: AudioFocusRequest? = null
    private var endListener: ((PlaybackEnd) -> Unit)? = null

    var isRecording by mutableStateOf(false)
        private set
    var playing by mutableStateOf<AudioClipKind?>(null)
        private set
    var hasRecording by mutableStateOf(clip != null)
        private set
    var elapsedSeconds by mutableStateOf(0)
        private set
    /** Total duration of the clip currently playing, once its [MediaPlayer]
     *  finished preparing — null before that (or when nothing is playing). */
    var durationMs by mutableStateOf<Int?>(null)
        private set
    /** Current playback position, refreshed on the same cadence as the
     *  recording ticker while [playing] is non-null. Only meaningful once
     *  [durationMs] is non-null (i.e. after the player has prepared). */
    var positionMs by mutableStateOf(0)
        private set
    /** Current playback speed multiplier — one of [SPEED_CHOICES]. Reset to
     *  1x at the start of every new [play] call so a leftover 1.25x from a
     *  previous clip never silently carries over to an unrelated one. */
    var playbackSpeed by mutableStateOf(1f)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** Absolute path of the current completed take, observable by Compose. */
    val clipPath: String? get() = clipState?.absolutePath

    /** Marks the current contribution take as owned by a durable draft, so
     *  leaving the screen keeps the file instead of deleting it. No-op when
     *  [path] is no longer the current take. */
    fun retainClipForDraft(path: String) {
        if (phraseId == null && clip?.absolutePath == path) retainClip = true
    }

    /** Restores a draft's previously completed contribution take. Returns
     *  false (and changes nothing) if the file is gone or not a draft file. */
    fun adoptDraftRecording(path: String): Boolean {
        if (phraseId != null || disposed || isRecording || clip != null) return false
        val file = File(path)
        return try {
            val canonical = file.canonicalFile
            if (!canonical.isFile || !canonical.toPath().startsWith(File(root, "drafts").canonicalFile.toPath())) return false
            clip = canonical
            hasRecording = true
            retainClip = true
            true
        } catch (error: IOException) {
            false
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!isRecording) return
            elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1_000).toInt()
            if (elapsedSeconds >= MAX_SECONDS) {
                finishRecording()
            } else {
                handler.postDelayed(this, 250)
            }
        }
    }

    private val positionTicker = object : Runnable {
        override fun run() {
            val current = player ?: return
            try {
                positionMs = current.currentPosition
            } catch (error: IllegalStateException) {
                return
            }
            if (playing != null) handler.postDelayed(this, 200)
        }
    }

    fun reportPermissionDenied() {
        message = "Microphone permission was not granted. You can continue without a recording. To enable it, open Root's app permissions in Settings."
    }

    fun reportNotForeground() {
        message = "Return to this screen and tap Record again."
    }

    fun startRecording() {
        if (disposed || saving || isRecording) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            reportPermissionDenied()
            return
        }
        message = null
        claimAudio()
        try {
            val directory = File(root, "drafts")
            ensureDirectory(directory)
            val file = File.createTempFile("voice-", ".m4a", directory)
            recordingFile = file
            @Suppress("DEPRECATION")
            val next = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            recorder = next
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioEncodingBitRate(96_000)
            next.setAudioSamplingRate(44_100)
            next.setOutputFile(file.absolutePath)
            next.setMaxDuration(MAX_SECONDS * 1_000)
            next.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finishRecording()
            }
            next.setOnErrorListener { _, _, _ ->
                cancelRecording()
                message = "Recording stopped unexpectedly. Please try again."
            }
            next.prepare()
            next.start()
            startedAt = SystemClock.elapsedRealtime()
            elapsedSeconds = 0
            isRecording = true
            handler.post(ticker)
        } catch (error: IOException) {
            recordingFailed(error)
        } catch (error: RuntimeException) {
            recordingFailed(error)
        }
    }

    fun finishRecording() {
        val current = recorder ?: return
        val file = recordingFile
        val duration = SystemClock.elapsedRealtime() - startedAt
        recorder = null
        recordingFile = null
        isRecording = false
        handler.removeCallbacks(ticker)
        var stopped = false
        try {
            current.stop()
            stopped = true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Recording could not be stopped cleanly", error)
        } finally {
            releaseRecorder(current)
            releaseAudio()
        }
        if (!stopped || duration < MIN_MILLIS || file == null || file.length() == 0L) {
            deleteOwned(file)
            message = "That recording was too short. Record for at least one second."
            return
        }
        try {
            val destination = phraseId?.let(::learnerFile)
            if (destination != null) {
                ensureDirectory(destination.parentFile!!)
                Files.move(file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                clip = destination
            } else {
                if (deleteOwned(clip)) {
                    clip = file
                    retainClip = false
                } else {
                    deleteOwned(file)
                    return
                }
            }
            hasRecording = true
            message = "Recording ready. Listen back before keeping it."
        } catch (error: IOException) {
            deleteOwned(file)
            message = "The recording could not be kept. Please try again."
            Log.w(TAG, "Could not retain recording", error)
        }
    }

    fun cancelRecording() {
        val current = recorder
        recorder = null
        isRecording = false
        handler.removeCallbacks(ticker)
        if (current != null) {
            try {
                current.stop()
            } catch (error: RuntimeException) {
                Log.d(TAG, "Discarding unfinished recording", error)
            } finally {
                releaseRecorder(current)
            }
        }
        deleteOwned(recordingFile)
        recordingFile = null
        releaseAudio()
    }

    fun playReference(source: String?) {
        if (source.isNullOrBlank()) {
            message = "No reference recording yet. This phrase needs a speaker's voice."
            return
        }
        play(AudioClipKind.REFERENCE, source)
    }

    /** Plays one reference clip from the start at [speed] and reports how it
     *  ended through [onEnded], exactly once. Never toggles an already-playing
     *  clip off; any previous playback is interrupted first. */
    fun playClip(source: String, speed: Float, onEnded: (PlaybackEnd) -> Unit) {
        require(speed in SPEED_CHOICES) { "Unsupported playback speed: $speed" }
        if (disposed || saving) {
            onEnded(PlaybackEnd.INTERRUPTED)
            return
        }
        stopPlayback()
        play(AudioClipKind.REFERENCE, source, speed, onEnded)
    }

    fun playRecording() {
        val file = clip
        if (file == null || !file.isFile) {
            hasRecording = false
            message = "No saved recording is available. Record your voice first."
            return
        }
        play(AudioClipKind.RECORDING, file.absolutePath)
    }

    private fun play(
        kind: AudioClipKind,
        source: String,
        initialSpeed: Float = 1f,
        onEnded: ((PlaybackEnd) -> Unit)? = null,
    ) {
        if (disposed || saving) {
            onEnded?.invoke(PlaybackEnd.INTERRUPTED)
            return
        }
        if (playing == kind && onEnded == null) {
            stopPlayback()
            return
        }
        claimAudio()
        endListener = onEnded
        message = null
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener({ change ->
                    if (change < 0) interrupt()
                }, handler)
                .build()
            focusRequest = request
            if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                message = "Audio is busy in another app. Try again in a moment."
                releaseAudio()
                notifyEnd(PlaybackEnd.FAILED)
                return
            }
            val next = MediaPlayer()
            player = next
            playing = kind
            durationMs = null
            positionMs = 0
            playbackSpeed = 1f
            next.setAudioAttributes(attributes)
            val file = File(source)
            if (file.isAbsolute) {
                require(file.canonicalFile.toPath().startsWith(context.filesDir.canonicalFile.toPath())) {
                    "Audio must be an app-internal recording."
                }
                require(file.isFile) { "Recording is missing." }
                next.setDataSource(file.canonicalPath)
            } else {
                require(source.split('/').none { it == ".." || it.isEmpty() } &&
                    !source.contains('\\') && !source.contains(':')) { "Invalid bundled audio name." }
                context.assets.openFd(source).use {
                    next.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            }
            next.setOnPreparedListener {
                if (player === it && !disposed) {
                    try {
                        durationMs = it.duration.takeIf { ms -> ms > 0 }
                        it.start()
                        // Speed must be applied after start: on some decoders,
                        // setting playback params on a prepared player starts it.
                        if (initialSpeed != 1f) setSpeed(initialSpeed)
                        handler.post(positionTicker)
                    } catch (error: RuntimeException) {
                        playbackFailed(kind, error)
                    }
                }
            }
            next.setOnCompletionListener { if (player === it) stopPlayback(PlaybackEnd.COMPLETED) }
            next.setOnErrorListener { mediaPlayer, _, _ ->
                if (player === mediaPlayer) playbackFailed(kind, null)
                true
            }
            next.prepareAsync()
        } catch (error: IOException) {
            playbackFailed(kind, error)
        } catch (error: RuntimeException) {
            playbackFailed(kind, error)
        }
    }

    /** Jumps the current clip to [ms] (clamped to `[0, durationMs]`). No-op if
     *  nothing is playing or the player has not finished preparing yet
     *  (before [durationMs] is known, its position is not yet seekable). */
    fun seekTo(ms: Int) {
        val current = player ?: return
        val total = durationMs ?: return
        try {
            val clamped = ms.coerceIn(0, total)
            current.seekTo(clamped)
            positionMs = clamped
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Could not seek", error)
        }
    }

    /** Sets the playback speed to one of [SPEED_CHOICES]; any other value is
     *  rejected rather than silently clamped, since an unlisted speed is a
     *  caller bug, not a valid user choice. No-op if nothing is playing. */
    fun setSpeed(value: Float) {
        require(value in SPEED_CHOICES) { "Unsupported playback speed: $value" }
        val current = player ?: return
        try {
            val wasPlaying = current.isPlaying
            current.playbackParams = current.playbackParams.setSpeed(value)
            playbackSpeed = value
            // Some OEM decoders pause playback as a side effect of a speed
            // change; explicitly resume rather than silently leaving it stopped.
            if (wasPlaying && !current.isPlaying) current.start()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Could not change playback speed", error)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Playback speed not supported by this decoder", error)
            message = "This speed is not supported on this device."
        }
    }

    fun stopPlayback() = stopPlayback(PlaybackEnd.INTERRUPTED)

    private fun stopPlayback(reason: PlaybackEnd) {
        val current = player
        player = null
        playing = null
        durationMs = null
        positionMs = 0
        handler.removeCallbacks(positionTicker)
        if (current != null) {
            current.setOnPreparedListener(null)
            current.setOnCompletionListener(null)
            current.setOnErrorListener(null)
            current.release()
        }
        releaseAudio()
        notifyEnd(reason)
    }

    private fun notifyEnd(reason: PlaybackEnd) {
        val listener = endListener ?: return
        endListener = null
        listener(reason)
    }

    fun deleteRecording() {
        if (saving) return
        stopPlayback()
        cancelRecording()
        if (deleteOwned(clip)) {
            clip = null
            retainClip = false
            hasRecording = false
            message = "Recording deleted."
        }
    }

    /** Move into durable internal storage immediately before the parent's database save. */
    fun beginContributionSave(): String? {
        check(phraseId == null && !disposed && !saving)
        stopPlayback()
        check(!isRecording) { "Stop recording before saving." }
        val current = clip
        if (current != null && current.parentFile?.name == "drafts") {
            val directory = File(root, "references")
            ensureDirectory(directory)
            val destination = File(directory, current.name)
            Files.move(current.toPath(), destination.toPath())
            clip = destination
            movedFromDraft = current
        }
        saving = true
        return clip?.absolutePath
    }

    fun endContributionSave(success: Boolean) {
        saving = false
        transferred = success
        val draftLocation = movedFromDraft
        movedFromDraft = null
        if (success) {
            clip = null
            hasRecording = false
            return
        }
        // Put a failed save's take back where its durable draft points.
        val current = clip
        if (current != null && draftLocation != null) {
            try {
                Files.move(current.toPath(), draftLocation.toPath())
                clip = draftLocation
            } catch (error: IOException) {
                Log.w(TAG, "Could not return recording to its draft", error)
            }
        }
        if (disposed && !retainClip) {
            deleteOwned(clip)
            clip = null
        }
    }

    fun interrupt() {
        val wasRecording = isRecording
        stopPlayback()
        cancelRecording()
        if (wasRecording) message = "Recording interrupted and discarded. Tap Record to try again."
    }

    fun dispose() {
        disposed = true
        interrupt()
        if (phraseId == null && !saving && !transferred && !retainClip) {
            deleteOwned(clip)
            clip = null
        }
    }

    private fun claimAudio() {
        active?.interrupt()
        active = this
    }

    private fun releaseAudio() {
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        focusRequest = null
        if (active === this && !isRecording && player == null) active = null
    }

    private fun recordingFailed(error: Exception) {
        Log.w(TAG, "Could not start recording", error)
        cancelRecording()
        message = "Microphone unavailable. Check permission and whether another app is recording."
    }

    private fun playbackFailed(kind: AudioClipKind, error: Exception?) {
        Log.w(TAG, "Could not play $kind audio", error)
        stopPlayback(PlaybackEnd.FAILED)
        message = if (kind == AudioClipKind.REFERENCE) {
            "Reference audio is unavailable. This phrase still needs a playable speaker recording."
        } else {
            "This recording could not be played. Delete it and record again."
        }
    }

    private fun learnerFile(id: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(root, "learners/$hash.m4a")
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create audio directory")
    }

    private fun deleteOwned(file: File?): Boolean {
        if (file == null || !file.exists()) return true
        return try {
            require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
            if (file.delete()) true else {
                Log.w(TAG, "Could not delete an internal audio file")
                message = "A recording could not be removed. Please try deleting it again."
                false
            }
        } catch (error: IOException) {
            Log.w(TAG, "Could not resolve recording for deletion", error)
            message = "A recording could not be removed."
            false
        }
    }

    private fun releaseRecorder(value: MediaRecorder) {
        value.setOnErrorListener(null)
        value.setOnInfoListener(null)
        value.release()
    }

    companion object {
        const val MAX_SECONDS = 30
        private const val MIN_MILLIS = 1_000L
        private const val TAG = "RootAudio"
        private var active: RootAudioSession? = null

        /** The only supported reels/playback speeds — see [setSpeed]. */
        val SPEED_CHOICES = listOf(0.75f, 1f, 1.25f)
    }
}
