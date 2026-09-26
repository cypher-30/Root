package com.root.app.ui.audio

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.root.app.audio.RootAudioCoordinator
import com.root.app.audio.RootSoundEvent
import com.root.app.audio.RootSoundPlayer
import com.root.app.audio.SoundRequest
import com.root.app.audio.SoundSettings
import kotlinx.coroutines.flow.Flow

/** The Activity's interaction-sound owner; null in previews and tests that
 *  compose screens without it, where every sound call is simply skipped. */
val LocalRootSounds = staticCompositionLocalOf<RootSoundPlayer?> { null }

/** Creates the Activity-scoped sound player above launch and navigation.
 *  It is foreground only while the lifecycle is resumed and is released
 *  when this composition leaves. */
@Composable
fun rememberRootSoundPlayer(settings: () -> SoundSettings): RootSoundPlayer {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentSettings = rememberUpdatedState(settings)
    val player = remember(context) { RootSoundPlayer(context) { currentSettings.value() } }
    DisposableEffect(player, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> player.foreground = true
                Lifecycle.Event.ON_PAUSE -> player.foreground = false
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            player.foreground = false
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/** Plays automatic cue requests from [events] only while the calling route is
 *  composed and resumed. Late events (older than [RootSoundEvent.MAX_AGE_MS])
 *  are dropped rather than played out of context. */
@Composable
fun CollectSoundEvents(events: Flow<RootSoundEvent>) {
    val sounds = LocalRootSounds.current ?: return
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(events, sounds, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            events.collect { event ->
                if (!event.isStale(SystemClock.elapsedRealtime())) sounds.play(event.cue, SoundRequest.AUTOMATIC)
            }
        }
    }
}

/** Holds speech priority while [active] (for example across a reel's
 *  clip-to-clip transitions), so no interaction sound slips in between. */
@Composable
fun ReserveSpeech(active: Boolean) {
    DisposableEffect(active) {
        val token = Any()
        if (active) RootAudioCoordinator.claimSpeech(token)
        onDispose { RootAudioCoordinator.releaseSpeech(token) }
    }
}
