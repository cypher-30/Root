package com.root.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.root.app.content.Credits
import com.root.app.reels.ReelAudioSource
import com.root.app.reels.ReelClip
import com.root.app.reels.ReelPlaybackPort
import com.root.app.reels.ReelPlaybackResult
import com.root.app.reels.ReelsPlaybackSpeed
import com.root.app.reels.ReelsPlayer
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.theme.RootTheme
import com.root.app.ui.theme.RootType
import kotlinx.coroutines.CancellationException
import kotlin.math.max

/** What the waveform area can honestly show for the current clip. */
sealed interface WaveformUi {
    data object Loading : WaveformUi
    data object Unavailable : WaveformUi
    data class Ready(val bins: FloatArray) : WaveformUi
}

/**
 * A single-pass reel over a pack's ordered phrase recordings. Nothing plays
 * until the learner taps Play; Replay starts a fresh pass; leaving or
 * backgrounding the screen stops playback (the owner's audio session reports
 * that as an interruption) and nothing resumes on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReelsScreen(
    title: String,
    player: ReelsPlayer,
    onBack: () -> Unit,
    loadWaveform: suspend (ReelClip) -> FloatArray?,
    modifier: Modifier = Modifier,
) {
    val snapshot by player.snapshot.collectAsStateWithLifecycle()
    val state = snapshot.state
    val currentClip = snapshot.currentClip

    var waveform by remember { mutableStateOf<WaveformUi>(WaveformUi.Loading) }
    LaunchedEffect(currentClip?.id) {
        val clip = currentClip ?: return@LaunchedEffect
        waveform = WaveformUi.Loading
        waveform = try {
            loadWaveform(clip)?.takeIf { it.isNotEmpty() }?.let { WaveformUi.Ready(it) } ?: WaveformUi.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WaveformUi.Unavailable
        }
    }

    DisposableEffect(player) { onDispose { player.stop() } }

    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        CenterAlignedTopAppBar(
            title = { Text(title, style = RootType.label) },
            navigationIcon = {
                IconButton(onClick = { player.stop(); onBack() }) {
                    Icon(RootIcons.Back, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .widthIn(max = 640.dp)
                .align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${player.clipCount} ${if (player.clipCount == 1) "phrase" else "phrases"} · " +
                    "${player.playableCount} with a recording",
                style = RootType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    when (state) {
                        ReelsPlayer.State.NotStarted -> {
                            if (player.playableCount == 0) {
                                Text("Nothing to play yet.", style = RootType.editorialTitle)
                                Text(
                                    "None of these phrases has a speaker's recording on this device, so this reel stays silent rather than using a substitute voice.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text("Listen once, start to finish.", style = RootType.editorialTitle)
                                Button(onClick = player::start, shape = MaterialTheme.shapes.small) {
                                    Icon(RootIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Play reel")
                                }
                            }
                        }
                        is ReelsPlayer.State.Playing -> {
                            Text(
                                "Phrase ${state.index + 1} of ${player.clipCount}",
                                style = RootType.label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            currentClip?.let { Text(it.label, style = RootType.editorialTitle) }
                            WaveformArea(waveform, Modifier.fillMaxWidth().height(96.dp))
                            OutlinedButton(onClick = player::stop, shape = MaterialTheme.shapes.small) {
                                Icon(RootIcons.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Stop")
                            }
                        }
                        ReelsPlayer.State.Stopped, ReelsPlayer.State.Finished -> {
                            val finished = state == ReelsPlayer.State.Finished
                            if (finished) {
                                Icon(
                                    RootIcons.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(if (finished) "Reel finished." else "Reel stopped.", style = RootType.editorialTitle)
                            if (snapshot.failedClipIds.isNotEmpty()) {
                                val count = snapshot.failedClipIds.size
                                Text(
                                    "$count ${if (count == 1) "recording" else "recordings"} couldn't be played on this device.",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Button(onClick = player::replay, shape = MaterialTheme.shapes.small) {
                                Text("Play from the start")
                            }
                        }
                    }
                }
            }

            SpeedControl(snapshot.speed, onChange = player::setSpeed)

            currentClip?.credits?.let { credits ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("CREDITS", style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { heading() })
                    Text(credits.text, style = RootType.meta, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            if (player.missingClips.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("NO RECORDING YET", style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { heading() })
                    Text(
                        "These phrases are skipped until a speaker's recording is available.",
                        style = RootType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    player.missingClips.forEach { clip ->
                        Text(clip.label, style = MaterialTheme.typography.bodyMedium)
                        clip.credits?.let {
                            Text(it.text, style = RootType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SpeedControl(speed: ReelsPlaybackSpeed, onChange: (ReelsPlaybackSpeed) -> Unit) {
    val next = when (speed) {
        ReelsPlaybackSpeed.NORMAL -> ReelsPlaybackSpeed.FAST
        ReelsPlaybackSpeed.FAST -> ReelsPlaybackSpeed.SLOW
        ReelsPlaybackSpeed.SLOW -> ReelsPlaybackSpeed.NORMAL
    }
    OutlinedButton(
        onClick = { onChange(next) },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "Playback speed ${speed.multiplier}x. Tap for ${next.multiplier}x."
        },
    ) { Text("Speed: ${speed.multiplier}x") }
}

@Composable
private fun WaveformArea(waveform: WaveformUi, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (waveform) {
            WaveformUi.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp).semantics { contentDescription = "Loading waveform" },
                strokeWidth = 2.dp,
            )
            WaveformUi.Unavailable -> Text(
                "Waveform unavailable for this recording.",
                style = RootType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is WaveformUi.Ready -> WaveformView(
                waveform.bins,
                Modifier.fillMaxSize().clearAndSetSemantics { },
                MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun WaveformView(amplitudes: FloatArray, modifier: Modifier, barColor: Color) {
    Canvas(modifier = modifier) {
        val barWidth = 4.dp.toPx()
        val totalBarWidth = barWidth + 2.dp.toPx()
        val barsToDraw = (size.width / totalBarWidth).toInt().coerceIn(1, amplitudes.size)
        val step = max(1, amplitudes.size / barsToDraw)
        val minHeight = 4.dp.toPx()
        for (i in 0 until barsToDraw) {
            val amplitude = amplitudes[(i * step).coerceIn(0, amplitudes.lastIndex)]
            val barHeight = maxOf(minHeight, amplitude * size.height)
            drawRoundRect(
                color = barColor,
                topLeft = Offset(i * totalBarWidth, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

private class PreviewPlaybackPort : ReelPlaybackPort {
    override fun play(source: ReelAudioSource, speed: Float, onFinished: (ReelPlaybackResult) -> Unit) {}
    override fun stop() {}
    override fun setSpeed(value: Float) {}
}

@Preview(name = "Reels Screen / Light", showBackground = true)
@Composable
private fun ReelsScreenLightPreview() {
    val clips = listOf(
        ReelClip("1", "Amosi (Hello)", Credits("Recorded by a consenting speaker. Licensed under CC-BY."), ReelAudioSource.Bundled("fake.m4a")),
        ReelClip("2", "Erokamano (Thank you)", null, null),
    )
    RootTheme(darkTheme = false) {
        ReelsScreen(
            title = "Greetings",
            player = remember { ReelsPlayer(clips, PreviewPlaybackPort()) },
            onBack = {},
            loadWaveform = { null },
        )
    }
}
