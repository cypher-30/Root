package com.root.app.ui.audio

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.sp
import com.root.app.ui.motion.RootHaptics
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.root.app.ui.motion.RootMotion
import com.root.app.ui.root.RootPath
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.root.app.audio.RootSoundCue
import com.root.app.audio.SoundRequest
import com.root.app.audio.SoundResult
import com.root.app.audio.SoundSettings
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.theme.RootTheme
import com.root.app.ui.theme.RootType
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sound effects, volume, and startup switches, shown at the top of the Sound screen
 *  (Profile → Sound). Changing them is silent; nothing plays until a real moment or a preview tap. */
@Composable
fun SoundSettingsControls(settings: SoundSettings, onChange: (SoundSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingSwitch(
            title = "Sound effects",
            body = "Soft sounds when you reveal a word, save a Got it, answer a lesson correctly, or finish a lesson.",
            checked = settings.effectsEnabled,
            onCheckedChange = { onChange(settings.copy(effectsEnabled = it)) },
        )
        VolumeControl(
            volume = settings.volume,
            onVolumeChange = { onChange(settings.copy(volume = it)) },
        )
        SettingSwitch(
            title = "Startup sound",
            body = if (settings.effectsEnabled) "Plays Root's growth sound once as the app opens."
            else "Plays Root's growth sound as the app opens. Turn on Sound effects first.",
            checked = settings.startupEnabled,
            enabled = settings.effectsEnabled,
            onCheckedChange = { onChange(settings.copy(startupEnabled = it)) },
        )
        Text(
            "Pronunciation recordings aren't affected. Sounds stay quiet on silent or vibrate, " +
                "in Do Not Disturb, during calls, and while a recording or other audio plays.",
            style = RootType.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** An ink-ruler volume slider: a hairline track with quarter marks, an ink stroke for
 *  the level, and a seed-like thumb that swells while held. Moves in 5 % steps with a
 *  light haptic detent at each quarter; moving it never plays a sound. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeControl(volume: Float, onVolumeChange: (Float) -> Unit) {
    val percent = (volume * 100).roundToInt()
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val engaged = dragged || pressed
    val ink = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.surface
    val hairline = MaterialTheme.colorScheme.outlineVariant
    val idleMark = MaterialTheme.colorScheme.outline
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Sound effect volume", style = MaterialTheme.typography.bodyLarge)
                Text("Moving this is silent. Try a sound in the Sound Lab.",
                    style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.clearAndSetSemantics {}) {
                Text(
                    if (percent == 0) "Off" else "$percent",
                    style = RootType.editorialTitle.copy(fontSize = 30.sp, lineHeight = 32.sp),
                    color = if (percent == 0) muted else MaterialTheme.colorScheme.onSurface,
                )
                if (percent > 0) {
                    Text("%", style = RootType.meta, color = muted,
                        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
                }
            }
        }
        Slider(
            value = volume,
            onValueChange = { raw ->
                val next = raw.coerceIn(0f, 1f)
                val nextPercent = (next * 100).roundToInt()
                if (nextPercent != percent && nextPercent % 25 == 0) RootHaptics.detent(view)
                onVolumeChange(next)
            },
            valueRange = 0f..1f,
            steps = 19,
            interactionSource = interaction,
            thumb = {
                val scale by animateFloatAsState(if (engaged) 1.2f else 1f, RootMotion.settle(), label = "thumb")
                val halo by animateFloatAsState(if (engaged) 0.10f else 0f, RootMotion.settle(), label = "halo")
                Canvas(Modifier.size(28.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
                    val r = 8.5.dp.toPx()
                    if (halo > 0f) drawCircle(ink.copy(alpha = halo), radius = size.minDimension / 2)
                    drawCircle(paper, radius = r)
                    drawCircle(ink, radius = r, style = Stroke(1.5.dp.toPx()))
                    drawCircle(ink, radius = 3.dp.toPx())
                }
            },
            track = {
                Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                    val y = center.y
                    val level = size.width * volume
                    drawLine(hairline, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), StrokeCap.Round)
                    for (i in 0..4) {
                        val x = size.width * i / 4f
                        val half = (if (i == 2) 6.dp else 4.dp).toPx()
                        drawLine(if (x <= level + 0.5f) ink else idleMark,
                            Offset(x, y - half), Offset(x, y + half), 1.dp.toPx())
                    }
                    if (level > 0f) drawLine(ink, Offset(0f, y), Offset(level, y), 3.dp.toPx(), StrokeCap.Round)
                }
            },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Sound effect volume"
                stateDescription = "$percent percent"
            },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).offset(y = (-8).dp).clearAndSetSemantics {}) {
            Text("QUIET", style = RootType.label, color = muted, modifier = Modifier.weight(1f))
            Text("FULL", style = RootType.label, color = muted)
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked && enabled, onCheckedChange = null, enabled = enabled)
    }
}

private data class DemoStep(val waitMs: Long, val cue: RootSoundCue, val caption: String)

private val DEMO = listOf(
    DemoStep(0, RootSoundCue.REVEAL, "You reveal a word."),
    DemoStep(900, RootSoundCue.RECALL_ACKNOWLEDGED, "You rate it Got it."),
    DemoStep(1_100, RootSoundCue.REVEAL, "The next word is revealed."),
    DemoStep(900, RootSoundCue.ANSWER_CORRECT, "A lesson answer is correct."),
    DemoStep(1_200, RootSoundCue.LESSON_SETTLED, "The lesson is finished."),
)

/**
 * Profile → Sound: only the settings, kept compact, with the Sound Lab one
 * level further in. Nothing plays here; changing a setting is silent.
 */
@Composable
fun SoundSettingsScreen(
    settings: SoundSettings,
    onSettingsChange: (SoundSettings) -> Unit,
    onOpenSoundLab: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                ScreenHeader("SOUND", onBack)
                Spacer(Modifier.height(20.dp))
                Text("Sound", style = RootType.editorialTitle)
                Spacer(Modifier.height(16.dp))
                SoundSettingsControls(settings, onSettingsChange)
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable(role = Role.Button, onClickLabel = "Open the Sound Lab", onClick = onOpenSoundLab)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Sound Lab", style = MaterialTheme.typography.bodyLarge)
                        Text("Listen to every sound before you choose.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(RootIcons.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ScreenHeader(label: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(RootIcons.Back, "Back") }
        Text(label, style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Explicit audition of every Root sound. Nothing plays on entry, scroll,
 * rotation, or return; each sound needs a tap. Previews work while automatic
 * sound effects are off, but never override device quiet modes or speech.
 * The demos are presentation only: they touch no practice, lesson, or recording.
 */
@Composable
fun SoundLabScreen(onBack: () -> Unit) {
    val sounds = LocalRootSounds.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf<String?>(null) }
    var demo by remember { mutableStateOf<Job?>(null) }
    var opening by remember { mutableStateOf<Job?>(null) }
    val growth = remember { Animatable(1f) }
    val context = LocalContext.current

    fun cancelDemo() {
        demo?.cancel()
        demo = null
        opening?.cancel()
        opening = null
    }
    DisposableEffect(owner, sounds) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) cancelDemo() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            cancelDemo()
            sounds?.stop()
        }
    }

    fun describe(result: SoundResult?, cue: RootSoundCue): String? = when (result) {
        null -> "Sounds aren't available here."
        SoundResult.Played -> null
        is SoundResult.Suppressed -> "${cue.label} stayed quiet: ${result.reason.explanation}"
        is SoundResult.Failed -> result.message
    }

    fun preview(cue: RootSoundCue) {
        cancelDemo()
        if (sounds?.playing == cue) {
            sounds.stop(fade = true)
            status = null
            return
        }
        val result = sounds?.play(cue, SoundRequest.PREVIEW)
        status = describe(result, cue) ?: "Playing ${cue.label}."
    }

    fun toggleDemo() {
        if (demo != null) {
            cancelDemo()
            sounds?.stop(fade = true)
            status = "Demo stopped."
            return
        }
        cancelDemo()
        demo = scope.launch {
            val self = coroutineContext[Job]
            try {
                for (step in DEMO) {
                    delay(step.waitMs)
                    val result = sounds?.play(step.cue, SoundRequest.PREVIEW)
                    val problem = describe(result, step.cue)
                    if (problem != null) {
                        status = "Demo stopped. $problem"
                        return@launch
                    }
                    status = step.caption
                }
                delay(RootSoundCue.LESSON_SETTLED.durationMs)
                status = "Demo finished."
            } finally {
                if (demo === self) demo = null
            }
        }
    }

    // Replays the launch exactly as the app opens (same timing, easing, and cue start),
    // so the sync between the drawing and the startup sound can be judged here.
    fun toggleOpening() {
        if (opening != null) {
            cancelDemo()
            sounds?.stopIf(RootSoundCue.STARTUP_MOTIF, fade = true)
            scope.launch { growth.snapTo(1f) }
            status = "Opening stopped."
            return
        }
        cancelDemo()
        opening = scope.launch {
            val self = coroutineContext[Job]
            try {
                growth.snapTo(0f)
                if (!RootMotion.enabled()) {
                    growth.snapTo(1f)
                    status = "Animations are off on this device, so the opening has no motion to follow."
                    return@launch
                }
                delay(RootMotion.launchSeedMillis)
                val cue = RootSoundCue.STARTUP_MOTIF
                status = describe(sounds?.play(cue, SoundRequest.PREVIEW), cue)
                    ?: if (RootMotion.normalSpeed(context)) "Playing the opening with ${cue.label}."
                    else "Animation speed is changed on this device, so the drawing won't line up. At launch this stays silent."
                growth.animateTo(1f, tween(RootMotion.launchMillis, easing = RootMotion.launchEase))
                delay(RootMotion.launchHoldMillis)
                status = "Opening finished."
            } finally {
                if (opening === self) opening = null
                growth.snapTo(1f)
            }
        }
    }

    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                ScreenHeader("SOUND LAB", onBack)
                Spacer(Modifier.height(20.dp))
                Text("Listen before you choose.", style = RootType.editorialTitle)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Root's sounds are soft wooden notes with a little paper grain. Tap any sound to hear it. " +
                        "These are app sounds only; they never stand in for a speaker's voice.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }

                Spacer(Modifier.height(24.dp))
                Text("STARTUP SOUND", style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OpeningPreview(growth.value, running = opening != null, onToggle = ::toggleOpening)
                Spacer(Modifier.height(8.dp))
                CueRow(RootSoundCue.STARTUP_MOTIF, sounds?.playing == RootSoundCue.STARTUP_MOTIF) {
                    preview(RootSoundCue.STARTUP_MOTIF)
                }

                CueGroup("PLAYS WHEN SOUND EFFECTS ARE ON",
                    RootSoundCue.entries.filter { it.pilot && it != RootSoundCue.STARTUP_MOTIF }, sounds?.playing, ::preview)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = ::toggleDemo, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                    Text(if (demo != null) "Stop practice sequence" else "Try a practice sequence")
                }
                CueGroup("NOT USED YET", RootSoundCue.entries.filter { !it.pilot && !it.isMotif },
                    sounds?.playing, ::preview)
                CueGroup("EARLIER MOTIFS", RootSoundCue.entries.filter { it.isMotif }, sounds?.playing, ::preview)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun OpeningPreview(progress: Float, running: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
                RootPath(progress, Modifier.size(72.dp))
                Text("Root", Modifier.padding(top = 8.dp).alpha(((progress - 0.5f) * 2).coerceIn(0f, 1f)),
                    style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                Text(if (running) "Stop the opening" else "Watch the opening with sound")
            }
        }
    }
}
@Composable
private fun CueGroup(title: String, cues: List<RootSoundCue>, playing: RootSoundCue?, onPreview: (RootSoundCue) -> Unit) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cues.forEach { cue -> CueRow(cue, playing == cue) { onPreview(cue) } }
    }
}

@Composable
private fun CueRow(cue: RootSoundCue, playing: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(cue.label, style = MaterialTheme.typography.titleMedium)
                Text(cue.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("%.1f s".format(cue.durationMs / 1000f), style = RootType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onClick) {
                Icon(if (playing) RootIcons.Stop else RootIcons.Play,
                    contentDescription = if (playing) "Stop ${cue.label}" else "Play ${cue.label}")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SoundLabPreview() {
    RootTheme { SoundLabScreen {} }
}

@Preview(showBackground = true)
@Composable
private fun SoundSettingsPreview() {
    RootTheme { SoundSettingsScreen(SoundSettings(), {}, {}, {}) }
}
