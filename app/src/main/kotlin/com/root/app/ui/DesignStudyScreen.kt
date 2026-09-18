package com.root.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.root.app.ui.brand.RootMark
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.root.RootPath
import com.root.app.ui.theme.RootTheme
import com.root.app.ui.theme.RootType

/**
 * "More options -> The design study": an inspectable reference for the brand mark,
 * launch keyframes, and motion/haptic annotations, matching the specification in
 * docs/DESIGN.md. [onReplay] re-runs the actual [com.root.app.ui.launch.LaunchScreen]
 * rather than a mocked animation, so this stays accurate as launch timing changes.
 */
@Composable
fun DesignStudyScreen(onBack: () -> Unit, onReplay: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        IconButton(onClick = onBack) { Icon(RootIcons.Back, "Back") }
        Text("THE ROOT STUDY", style = RootType.label)
        Spacer(Modifier.height(20.dp))
        RootMark()
        Row(Modifier.padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Bottom) {
            RootPath(1f, Modifier.size(24.dp))
            RootPath(1f, Modifier.size(48.dp))
            RootPath(1f, Modifier.size(96.dp))
        }
        Text("One mark, in three moments.", style = RootType.editorialTitle)
        Text("A seed becomes a path. A path becomes a practice.", Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
            listOf("Seed" to 0f, "Taking root" to 0.48f, "Settled" to 1f).forEach { (label, fraction) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    RootPath(fraction, Modifier.size(72.dp))
                    Text(label, style = RootType.meta)
                }
            }
        }
        OutlinedButton(onClick = onReplay, shape = MaterialTheme.shapes.small) { Text("Replay launch") }
        HorizontalDivider(Modifier.padding(vertical = 24.dp))
        Text("A memory surfaces.", style = RootType.editorialTitle)
        Text("Reveal · 520 ms\nFade from inkless to ink, drift upward 12 dp, soften from 12 dp blur to sharp. A light haptic tick begins the reveal.",
            Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Text("A card has weight.", style = RootType.editorialTitle)
        Text("Left: Missed. Up: Close. Right: Got it. Drag to lift and tilt; release to settle off-screen. A missed card returns to the deck once. Buttons do the same work.",
            Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Text("Soft tap · two light taps · firm tap", style = RootType.meta)
        Text("Roots grow only when you recall a phrase. No streak, no score to protect. With system animations disabled, motion is reduced; words and controls remain.",
            Modifier.padding(vertical = 20.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(name = "Launch sequence / light", widthDp = 390, heightDp = 1100)
@Preview(name = "Launch sequence / dark", widthDp = 390, heightDp = 1100, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StudyPreview() { RootTheme { Surface { DesignStudyScreen({}, {}) } } }
