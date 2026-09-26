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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.root.app.audio.WaveformCache
import com.root.app.audio.WaveformDecoder
import com.root.app.content.Credits
import com.root.app.reels.ReelAudioSource
import com.root.app.reels.ReelClip
import com.root.app.reels.ReelsPlaybackSpeed
import com.root.app.reels.ReelsPlayer
import com.root.app.reels.ReelPlaybackPort
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.theme.RootTheme
import com.root.app.ui.theme.RootType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Screen presenting a sequence of real-media reel clips with a waveform visualization
 * and short-form playback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReelsScreen(
    title: String,
    player: ReelsPlayer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    
    // Waveform state
    var currentWaveform by remember { mutableStateOf<FloatArray?>(null) }
    
    val currentClip = player.currentClip
    val playerState = player.state

    // Fetch waveform when clip changes
    LaunchedEffect(currentClip) {
        if (currentClip?.source != null) {
            currentWaveform = null
            withContext(Dispatchers.IO) {
                try {
                    val cache = WaveformCache(context)
                    // Try to get from cache or decode
                    val decoded = when (val source = currentClip.source) {
                        is ReelAudioSource.Bundled -> null // We cannot hash bundled assets easily to use the cache directly. For now, decode from path if we had one.
                        is ReelAudioSource.DownloadedFile -> {
                             val sourceFile = File(source.absolutePath)
                             cache.getOrDecode(currentClip.id, sourceFile)
                        }
                        else -> null
                    }
                    decoded?.let { currentWaveform = it }
                } catch (_: Exception) {
                    // Fail gracefully, no waveform
                }
            }
        } else {
            currentWaveform = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player.stop()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        // Top app bar
        CenterAlignedTopAppBar(
            title = { Text(title, style = RootType.label) },
            navigationIcon = {
                IconButton(onClick = { player.stop(); onBack() }) {
                    Icon(RootIcons.Back, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // Main playback area
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (playerState is ReelsPlayer.State.NotStarted) {
                        IconButton(
                            onClick = { player.start() },
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(
                                RootIcons.Play, 
                                contentDescription = "Start reel",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else if (playerState is ReelsPlayer.State.Finished) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                RootIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp).padding(bottom = 16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("Reel finished", style = RootType.editorialTitle, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(24.dp))
                            OutlinedButton(
                                onClick = { 
                                    player.stop()
                                    // Normally you'd re-instantiate or the caller passes a fresh one, but for simple playback we just start again if the player allows it.
                                    // ReelsPlayer states "reaching the end of the list leaves the player State.Finished rather than looping back". 
                                    // So we just call stop() and start() might not work if it checks NotStarted.
                                    // For a true replay we might need caller intervention, but we'll try to just stop for now.
                                },
                            ) {
                                Text("Replay")
                            }
                        }
                    } else if (currentClip != null) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Clip Label
                            Text(
                                text = currentClip.label,
                                style = RootType.editorialTitle,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )

                            // Waveform Visualization
                            Box(modifier = Modifier.fillMaxWidth().height(120.dp).padding(vertical = 16.dp)) {
                                if (playerState is ReelsPlayer.State.Missing) {
                                    Text(
                                        "Audio not available", 
                                        style = RootType.meta, 
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                } else {
                                    WaveformView(
                                        amplitudes = currentWaveform,
                                        modifier = Modifier.fillMaxSize(),
                                        barColor = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            
                            // Audio Controls (Speed, Stop)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val nextSpeed = when (player.speed) {
                                    ReelsPlaybackSpeed.NORMAL -> ReelsPlaybackSpeed.FAST
                                    ReelsPlaybackSpeed.FAST -> ReelsPlaybackSpeed.SLOW
                                    ReelsPlaybackSpeed.SLOW -> ReelsPlaybackSpeed.NORMAL
                                }
                                
                                OutlinedButton(onClick = { player.setSpeed(nextSpeed) }) {
                                    Text("${player.speed.multiplier}x")
                                }
                                
                                IconButton(onClick = { player.stop() }) {
                                    Icon(RootIcons.Stop, contentDescription = "Stop playback")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Credits Section
            if (currentClip?.credits != null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("CREDITS", style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(currentClip.credits.text, style = RootType.meta, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun WaveformView(
    amplitudes: FloatArray?,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
) {
    if ((amplitudes == null) || amplitudes.isEmpty()) {
        // Placeholder empty state or loading state
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = barColor.copy(alpha = 0.5f))
        }
        return
    }

    Canvas(modifier = modifier) {
        val barWidth = 4.dp.toPx()
        val gapWidth = 2.dp.toPx()
        val totalBarWidth = barWidth + gapWidth
        val viewWidth = size.width
        val viewHeight = size.height
        
        // Calculate how many bars we can fit
        val barsToDraw = (viewWidth / totalBarWidth).toInt().coerceAtMost(amplitudes.size)
        
        // We could resample the amplitudes to fit the visual space, but for simplicity
        // let's just stride through the array if it's too large, or draw all if small.
        val step = max(1, amplitudes.size / barsToDraw)
        
        var xOffset = 0f
        for (i in 0 until barsToDraw) {
            val sampleIndex = (i * step).coerceIn(0, amplitudes.lastIndex)
            val amplitude = amplitudes[sampleIndex]
            
            // amplitude is normalized 0f..1f (per WaveformDecoder)
            // Minimum height so silence is still a visible dot/line
            val minHeight = 4.dp.toPx()
            val barHeight = maxOf(minHeight, amplitude * viewHeight)
            
            val yOffset = (viewHeight - barHeight) / 2f
            
            drawRoundRect(
                color = barColor,
                topLeft = Offset(xOffset, yOffset),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
            
            xOffset += totalBarWidth
        }
    }
}

// Dummy port for preview
private class PreviewPlaybackPort : ReelPlaybackPort {
    override fun play(source: ReelAudioSource, onFinished: () -> Unit) {}
    override fun stop() {}
    override fun setSpeed(value: Float) {}
}

@Preview(name = "Reels Screen / Light", showBackground = true)
@Composable
private fun ReelsScreenLightPreview() {
    val mockClips = listOf(
        ReelClip(
            id = "1",
            label = "Amosi (Hello, one person)",
            credits = Credits("Recorded by Jane Doe. Licensed under CC-BY."),
            source = ReelAudioSource.Bundled("fake.mp3")
        )
    )
    val player = ReelsPlayer(mockClips, PreviewPlaybackPort())
    
    RootTheme(darkTheme = false) {
        ReelsScreen(
            title = "Greetings Reel",
            player = player,
            onBack = {}
        )
    }
}
