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
    private var clip: File? = phraseId?.let { learnerFile(it).takeIf(File::isFile) }
    private var startedAt = 0L
    private var disposed = false
    private var saving = false
    private var transferred = false
    private var focusRequest: AudioFocusRequest? = null

    var isRecording by mutableStateOf(false)
        private set
    var playing by mutableStateOf<String?>(null)
        private set
    var hasRecording by mutableStateOf(clip != null)
        private set
    var elapsedSeconds by mutableStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
        private set

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
        play("reference", source)
    }

    fun playRecording() {
        val file = clip
        if (file == null || !file.isFile) {
            hasRecording = false
            message = "No saved recording is available. Record your voice first."
            return
        }
        play("recording", file.absolutePath)
    }

    private fun play(kind: String, source: String) {
        if (disposed || saving) return
        if (playing == kind) {
            stopPlayback()
            return
        }
        claimAudio()
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
                return
            }
            val next = MediaPlayer()
            player = next
            playing = kind
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
                        it.start()
                    } catch (error: RuntimeException) {
                        playbackFailed(kind, error)
                    }
                }
            }
            next.setOnCompletionListener { if (player === it) stopPlayback() }
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

    fun stopPlayback() {
        val current = player
        player = null
        playing = null
        if (current != null) {
            current.setOnPreparedListener(null)
            current.setOnCompletionListener(null)
            current.setOnErrorListener(null)
            current.release()
        }
        releaseAudio()
    }

    fun deleteRecording() {
        if (saving) return
        stopPlayback()
        cancelRecording()
        if (deleteOwned(clip)) {
            clip = null
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
        }
        saving = true
        return clip?.absolutePath
    }

    fun endContributionSave(success: Boolean) {
        saving = false
        transferred = success
        if (success) {
            clip = null
            hasRecording = false
        } else if (disposed) {
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
        if (phraseId == null && !saving && !transferred) {
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

    private fun playbackFailed(kind: String, error: Exception?) {
        Log.w(TAG, "Could not play $kind audio", error)
        stopPlayback()
        message = if (kind == "reference") {
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
    }
}
