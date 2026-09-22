package com.root.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.root.app.ui.theme.RootTheme
import com.root.app.data.WeeklyChallengeEntity
import com.root.app.ui.brand.RootMark
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.motion.RootMotion
import com.root.app.ui.root.RecallRoots
import com.root.app.ui.theme.RootType

/** The weekly conversation nudge shown under the deck and on completion; a single
 *  `if (challenge == null) return` keeps it invisible until a challenge exists for
 *  the active language (see [com.root.app.data.RootRepository.weeklyChallenge]). */
@Composable
fun WeeklyChallengeCard(challenge: WeeklyChallengeEntity?, onDone: () -> Unit) {
    if (challenge == null) return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("OUT IN THE WORLD", style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("Let a word leave the page.", style = RootType.editorialTitle)
        Text("This week, use a ${challenge.theme.lowercase()} word in a real conversation.",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (challenge.completed) {
            val fraction = remember { Animatable(if (RootMotion.enabled()) 0f else 1f) }
            LaunchedEffect(Unit) { fraction.animateTo(1f, tween(200, easing = RootMotion.settleEase)) }
            val color = MaterialTheme.colorScheme.onSurface
            Row(Modifier.padding(vertical = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(20.dp)) {
                    val path = Path().apply {
                        moveTo(size.width * 0.1f, size.height * 0.5f)
                        lineTo(size.width * 0.4f, size.height * 0.8f)
                        lineTo(size.width * 0.9f, size.height * 0.15f)
                    }

                    val measure = PathMeasure().apply { setPath(path, false) }
                    val segment = Path()
                    measure.getSegment(0f, measure.length * fraction.value, segment)
                    drawPath(segment, color, style = Stroke(1.5.dp.toPx()))
                }
                Text("A word put to use.", Modifier.padding(start = 8.dp), style = RootType.meta)
            }
        } else TextButton(onClick = onDone, contentPadding = PaddingValues(vertical = 8.dp)) { Text("I did this") }
    }
}

/**
 * Shown when the session queue is empty: either the learner just finished a
 * session ([completed] = true) or there was simply nothing due ([completed] =
 * false, [current][com.root.app.RootViewModel.current] was already null). Distinct
 * copy for each case keeps "nothing due today" from reading as a failure state.
 */
@Composable
fun SessionCompleteScreen(
    correct: Int, capability: Int, completed: Boolean, language: String,
    challenge: WeeklyChallengeEntity?, onChallenge: () -> Unit,
    onMore: () -> Unit, onDone: () -> Unit,
    onRefresh: () -> Unit = {},
    canPracticeMore: Boolean = false,
    onContinuePracticing: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RootMark(compact = true)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onMore) { Icon(RootIcons.More, "More options") }
        }
        Spacer(Modifier.height(48.dp))
        Text(if (completed) "A GOOD PLACE TO PAUSE" else "ROOM TO BREATHE", style = RootType.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Text(if (completed) "Enough for today." else "Nothing due.\nNothing owed.", style = RootType.heroAnswer)
        Spacer(Modifier.height(20.dp))
        Text(if (completed) "You made space for your words. Take them with you."
            else "Your $language words will be here when they’re ready. You can also add a word of your own.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RecallRoots(correct, Modifier.fillMaxWidth().height(130.dp).padding(vertical = 16.dp))
        if (capability > 0) Text("$capability ${if (capability == 1) "phrase" else "phrases"} you recalled at last practice.",
            style = MaterialTheme.typography.bodyMedium)
        // Quiet ritual, open continuation: a page finished, but there is no fixed
        // session size — offer to keep going rather than forcing a restart/new session.
        if (completed && canPracticeMore) {
            OutlinedButton(onClick = onContinuePracticing, modifier = Modifier.padding(top = 24.dp),
                shape = MaterialTheme.shapes.small) { Text("Keep practicing") }
        }
        OutlinedButton(onClick = onDone, modifier = Modifier.padding(top = 12.dp), shape = MaterialTheme.shapes.small) {
            Text("Close this session")
        }
        TextButton(onClick = onRefresh) { Text("Check for due words") }
        HorizontalDivider(Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
        WeeklyChallengeCard(challenge, onChallenge)
    }
}
