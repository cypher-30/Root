package com.root.app.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.root.app.data.ConfidenceLevel
import com.root.app.data.PhraseEntity
import com.root.app.ui.audio.AudioPracticeControls
import com.root.app.ui.brand.RootMark
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.motion.RootHaptics
import com.root.app.ui.motion.RootMotion
import com.root.app.ui.root.RecallRoots
import com.root.app.ui.theme.RootTheme
import com.root.app.ui.theme.RootType
import com.root.app.ui.theme.paperSurface
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlin.math.abs

private fun ConfidenceLevel.label() = when (this) {
    ConfidenceLevel.MISSED -> "Missed"
    ConfidenceLevel.CLOSE -> "Close"
    ConfidenceLevel.GOT_IT -> "Got it"
}

/**
 * The core recall screen: prompt -> reveal -> outcome. [PracticeCard] owns reveal
 * animation, drag-to-rate gestures, and the exit/return animation for a Missed card;
 * [onRate] is a suspend callback so the card can wait for the rating to actually
 * persist (see [com.root.app.RootViewModel.rate]) before deciding whether the exit
 * animation should complete or the card should snap back on failure.
 */
@Composable
fun PracticeScreen(
    phrase: PhraseEntity,
    languageName: String,
    correctCount: Int,
    turn: Int,
    onRate: suspend (ConfidenceLevel) -> Boolean,
    onMore: () -> Unit,
    onTeach: () -> Unit,
    challenge: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RootMark(compact = true)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onMore) { Icon(RootIcons.More, "More options") }
        }
        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("THE DAILY PRACTICE", style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(languageName, style = RootType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        key(phrase.id, turn) {
            PracticeCard(phrase, onRate)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("A little closer.", style = RootType.editorialTitle)
                Text("One word, then another.", style = RootType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RecallRoots(correctCount, Modifier.width(128.dp).height(64.dp))
        }
        TextButton(onClick = onTeach, contentPadding = PaddingValues(vertical = 8.dp)) {
            Icon(RootIcons.Share, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Teach someone this word")
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        challenge()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
/**
 * A single recall card: prompt, reveal, drag/swipe-to-rate, and the settle/exit
 * animation for each outcome. `drag` tracks live gesture offset; `exitX`/`exitY`
 * animate the card fully off-screen (or, for Missed, partway back with a fade) once
 * a rating is committed. [rate] fires the haptic immediately (feedback should feel
 * instant) but leaves the card interactive-locked via `busy` until [onRate] resolves.
 */
private fun PracticeCard(phrase: PhraseEntity, onRate: suspend (ConfidenceLevel) -> Boolean) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    var audioExpanded by rememberSaveable { mutableStateOf(false) }
    val reveal = remember { Animatable(if (revealed) 1f else 0f) }
    val exitX = remember { Animatable(0f) }
    val exitY = remember { Animatable(0f) }
    val exitAlpha = remember { Animatable(1f) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    var busy by remember { mutableStateOf(false) }
    var returning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current
    val threshold = with(density) { 80.dp.toPx() }
    val travel = with(density) { 650.dp.toPx() }
    val lift = with(density) { 12.dp.toPx() }
    val velocityTracker = remember { VelocityTracker() }
    val ratingsView = remember { BringIntoViewRequester() }
    val cue = when {
        drag.y < -abs(drag.x) -> ConfidenceLevel.CLOSE
        drag.x < 0 -> ConfidenceLevel.MISSED
        else -> ConfidenceLevel.GOT_IT
    }
    LaunchedEffect(revealed) {
        if (revealed) {
            reveal.animateTo(1f, tween(if (RootMotion.enabled()) RootMotion.revealMillis else 0, easing = RootMotion.settleEase))
            ratingsView.bringIntoView()
        }
    }
    fun rate(level: ConfidenceLevel) {
        if (!revealed || busy) return
        busy = true
        RootHaptics.rate(view, level)
        scope.launch {
            val result = async(start = CoroutineStart.UNDISPATCHED) { onRate(level) }
            val start = drag
            drag = Offset.Zero
            exitX.snapTo(start.x)
            exitY.snapTo(start.y)
            if (RootMotion.enabled()) {
                when (level) {
                    ConfidenceLevel.MISSED -> exitX.animateTo(-travel, RootMotion.settle())
                    ConfidenceLevel.CLOSE -> exitY.animateTo(-travel, RootMotion.settle())
                    ConfidenceLevel.GOT_IT -> exitX.animateTo(travel, RootMotion.settle())
                }
                if (level == ConfidenceLevel.MISSED) {
                    returning = true
                    exitAlpha.snapTo(0.35f)
                    exitY.snapTo(42f)
                    exitX.animateTo(0f, tween(330, easing = RootMotion.settleEase))
                    exitAlpha.animateTo(0f, tween(120))
                }
            }
            if (!result.await()) {
                exitX.snapTo(0f); exitY.snapTo(0f); exitAlpha.snapTo(1f)
                returning = false
                busy = false
            }
        }
    }
    Column {
        Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp)) {
            Surface(Modifier.matchParentSize().padding(top = 16.dp).offset(y = 10.dp).padding(horizontal = 12.dp),
                shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {}
            Surface(Modifier.matchParentSize().padding(top = 8.dp).offset(y = 5.dp).padding(horizontal = 6.dp),
                shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {}
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .graphicsLayer {
                        translationX = drag.x + exitX.value
                        translationY = drag.y + exitY.value
                        rotationZ = ((drag.x + exitX.value) / threshold * 3f).coerceIn(-14f, 14f)
                        scaleX = if (returning) 0.96f else 1f + (drag.getDistance() / threshold).coerceAtMost(1f) * 0.015f
                        scaleY = scaleX
                        alpha = exitAlpha.value
                    }
                    .semantics {
                        if (revealed && !busy) customActions = ConfidenceLevel.entries.map {
                            CustomAccessibilityAction(it.label()) { rate(it); true }
                        }
                    }
                    .pointerInput(revealed, busy) {
                        if (!revealed || busy) return@pointerInput
                        detectDragGestures(
                            onDragStart = { velocityTracker.resetTracking() },
                            onDrag = { change, amount ->
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                drag += amount
                            },
                            onDragCancel = { drag = Offset.Zero },
                            onDragEnd = {
                                val velocity = velocityTracker.calculateVelocity()
                                val fast = (abs(velocity.x) > 900 || velocity.y < -900) && drag.getDistance() > threshold / 4
                                if ((abs(drag.x) > threshold || drag.y < -threshold || fast) &&
                                    (abs(drag.x) > abs(drag.y) || drag.y < 0)) {
                                    rate(when {
                                        drag.y < -abs(drag.x) -> ConfidenceLevel.CLOSE
                                        drag.x < 0 -> ConfidenceLevel.MISSED
                                        else -> ConfidenceLevel.GOT_IT
                                    })
                                } else {
                                    val last = drag
                                    drag = Offset.Zero
                                    scope.launch { exitX.snapTo(last.x); exitX.animateTo(0f, RootMotion.settle()) }
                                    scope.launch { exitY.snapTo(last.y); exitY.animateTo(0f, RootMotion.settle()) }
                                }
                            },
                        )
                    },
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.paperSurface(MaterialTheme.colorScheme.onSurface).padding(24.dp).heightIn(min = 288.dp)) {
                    Text(if (drag.getDistance() > threshold / 4) cue.label().uppercase() else "BRING IT TO MIND",
                        style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    Text(phrase.prompt, style = RootType.promptLarge)
                    Spacer(Modifier.height(24.dp))
                    if (revealed) {
                        Text(phrase.answer,
                            Modifier.fillMaxWidth()
                                .graphicsLayer { alpha = reveal.value; translationY = (1 - reveal.value) * lift }
                                .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(((1 - reveal.value) * 12).dp) else Modifier)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            style = RootType.heroAnswer)
                        Spacer(Modifier.height(20.dp))
                        Text(if (returning) "Back in the deck, without judgment." else "How did it come back to you?",
                            style = RootType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Spacer(Modifier.height(24.dp))
                        Text("Take a breath.\nThe word is somewhere in you.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(onClick = { RootHaptics.reveal(view); revealed = true },
                            shape = MaterialTheme.shapes.small) { Text("Reveal the word") }
                    }
                }
            }
        }
        if (revealed) {
            Row(Modifier.fillMaxWidth().bringIntoViewRequester(ratingsView), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfidenceLevel.entries.forEach { level ->
                    OutlinedButton(onClick = { rate(level) }, enabled = !busy, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(level.label())
                    }
                }
            }
            Text("Swipe left · Missed     Up · Close     Right · Got it",
                Modifier.padding(vertical = 12.dp), style = RootType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { audioExpanded = !audioExpanded }, enabled = !busy,
                contentPadding = PaddingValues(vertical = 8.dp)) {
                Icon(RootIcons.Play, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (audioExpanded) "Hide voice practice" else "Listen & compare")
            }
            if (audioExpanded && !busy) AudioPracticeControls(phrase)
            Spacer(Modifier.height(20.dp))
        } else {
            Text("Recall, not a test. Only you decide.", Modifier.padding(bottom = 16.dp),
                style = RootType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(name = "Practice / paper", widthDp = 390, heightDp = 844, showBackground = true)
@Preview(name = "Practice / charcoal", widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Practice / compact", widthDp = 320, heightDp = 640)
@Composable
private fun PracticePreview() {
    RootTheme {
        Surface { PracticeScreen(PhraseEntity(id = "preview", packId = "", prompt = "Hello", answer = "Amosi", audioAsset = null),
            "Dholuo · sample", 2, 0, { true }, {}, {}) }
    }
}
