package com.root.app.ui.audio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.root.app.audio.AudioClipKind
import com.root.app.audio.RootAudioSession
import com.root.app.data.PhraseEntity

/** Internal (module-scoped, so usable from `com.root.app.ui.teach.LessonScreen`
 *  in this same module) so lesson playback can reuse the same session
 *  lifecycle/audio-focus handling as phrase practice, instead of duplicating it.
 *  `phraseId` scopes a learner recording; pass a distinct lesson step id so a
 *  lesson speaking take never overwrites an unrelated phrase clip. */
@Composable
internal fun rememberRootAudioSession(phraseId: String? = null): RootAudioSession {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val session = remember(context, owner, phraseId) { RootAudioSession(context, phraseId) }
    DisposableEffect(session, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) session.interrupt()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            session.dispose()
        }
    }
    return session
}

@Composable
fun AudioPracticeControls(phrase: PhraseEntity, onMarkPracticed: (() -> Unit)? = null) {
    val session = rememberRootAudioSession(phrase.id)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Listen & compare", style = MaterialTheme.typography.titleMedium)
        if (phrase.audioAsset.isNullOrBlank()) {
            Text(
                "No reference voice yet. This phrase needs a real speaker's recording. You can still record your own practice.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedButton(
                onClick = { session.playReference(phrase.audioAsset) },
                enabled = !session.isRecording,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(if (session.playing == AudioClipKind.REFERENCE) "Stop reference" else "Play reference")
            }
        }
        VoiceRecordingControls(session)
        if (session.playing != null) AccessibleAudioTransport(session)
        if (onMarkPracticed != null) {
            var justMarked by remember(phrase.id) { mutableStateOf(false) }
            OutlinedButton(
                onClick = { onMarkPracticed(); justMarked = true },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) { Text(if (justMarked) "Marked practiced" else "Mark practiced") }
            Text(
                // Self-reported only: tapping this never rates or reschedules
                // recall — it is a separate acknowledgement, not a grade.
                "This is just for you — it doesn't rate or reschedule this word.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Your practice recording stays on this device, separate from the reference. Compare by listening; Root does not score pronunciation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A minimal, screen-reader-friendly seek + speed transport for whatever clip
 *  a [RootAudioSession] is currently playing (reference, recording, or a
 *  reels clip). Renders nothing while nothing is playing rather than showing
 *  a disabled/dead control. The slider's `contentDescription` states the
 *  current position in whole seconds so a screen reader announces real
 *  progress, not just a bare percentage; the speed button announces both the
 *  current speed and what tapping it will change it to. */
@Composable
internal fun AccessibleAudioTransport(session: RootAudioSession, modifier: Modifier = Modifier) {
    val duration = session.durationMs ?: return
    if (duration <= 0) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val positionSeconds = session.positionMs / 1_000
        val durationSeconds = duration / 1_000
        androidx.compose.material3.Slider(
            value = session.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
            onValueChange = { session.seekTo(it.toInt()) },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Playback position: $positionSeconds of $durationSeconds seconds"
            },
        )
        val speedIndex = RootAudioSession.SPEED_CHOICES.indexOf(session.playbackSpeed).coerceAtLeast(0)
        OutlinedButton(
            onClick = {
                val next = RootAudioSession.SPEED_CHOICES[(speedIndex + 1) % RootAudioSession.SPEED_CHOICES.size]
                session.setSpeed(next)
            },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.semantics {
                contentDescription = "Playback speed ${session.playbackSpeed}x. Tap to change."
            },
        ) { Text("Speed: ${session.playbackSpeed}x") }
    }
}

/** Internal: reusable record/play/delete controls bound to a [RootAudioSession],
 *  independent of any specific phrase — used by phrase practice and lesson
 *  reflection ("optional self-assessed recording") alike. */
@Composable
internal fun VoiceRecordingControls(
    session: RootAudioSession,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentEnabled by rememberUpdatedState(enabled)
    var requestedFor by remember { mutableStateOf<RootAudioSession?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = requestedFor
        requestedFor = null
        if (requested === session && currentEnabled) {
            if (!granted) session.reportPermissionDenied()
            else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.startRecording()
            else session.reportNotForeground()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                if (session.isRecording) {
                    session.finishRecording()
                } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.startRecording()
                    else session.reportNotForeground()
                } else {
                    requestedFor = session
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            enabled = enabled && requestedFor == null,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (session.isRecording) "Stop recording (${session.elapsedSeconds}s / ${RootAudioSession.MAX_SECONDS}s)"
                else if (session.hasRecording) "Re-record your voice" else "Record your voice",
            )
        }
        if (session.isRecording) {
            OutlinedButton(
                onClick = { session.cancelRecording() },
                enabled = enabled,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel recording") }
        } else if (session.hasRecording) {
            OutlinedButton(
                onClick = session::playRecording,
                enabled = enabled,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (session.playing == AudioClipKind.RECORDING) "Stop your recording" else "Play your recording") }
            OutlinedButton(
                onClick = session::deleteRecording,
                enabled = enabled,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Delete recording") }
        }
        session.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (!session.isRecording) {
            Text("Record 1-30 seconds. Leaving this screen discards an unfinished recording.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
