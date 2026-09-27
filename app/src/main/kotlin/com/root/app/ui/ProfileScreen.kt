package com.root.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.root.app.audio.SoundSettings
import kotlin.math.roundToInt
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.root.RecallRoots
import com.root.app.ui.theme.RootType

/**
 * The Profile tab: appearance, on-device progress, language, unlocking more
 * words, sharing, and help — everything that was previously scattered across
 * the "More options" sheet (see docs/DESIGN.md's navigation contract). Your
 * words / open drafts now live in Explore, scoped to the active language, so
 * this screen stays about the learner and their settings, not their content.
 * No account or cloud sync is implied anywhere here; every fact shown reads
 * from local state. Sections are grouped by purpose (progress, settings, more words
 * and sharing, about), and anything that opens another screen ends in a chevron.
 * Detailed settings like Sound live one level in, so this screen stays short.
 */
@Composable
fun ProfileScreen(
    languageName: String,
    capability: Int,
    correctThisSession: Int,
    onLanguageClick: () -> Unit,
    onUnlockMoreWords: () -> Unit,
    onShareAWord: () -> Unit,
    theme: String,
    onThemeChange: (String) -> Unit,
    onHowRootWorks: () -> Unit,
    soundSettings: SoundSettings = SoundSettings(),
    onOpenSound: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Text("PROFILE", style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
            item {
                Text("Your practice, quietly kept.", style = RootType.editorialTitle)
                Spacer(Modifier.height(20.dp))

                ProfileSection(title = "YOUR PROGRESS") {
                    Text(languageName, style = MaterialTheme.typography.titleMedium)
                    RecallRoots(correctThisSession, Modifier.size(96.dp).padding(top = 8.dp))
                    if (capability > 0) {
                        Text(
                            "$capability ${if (capability == 1) "phrase" else "phrases"} recalled last practice.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                ProfileSection(title = "SETTINGS") {
                    ProfileEntry("Language · $languageName", onLanguageClick)
                    val sound = if (soundSettings.effectsEnabled) "On · ${(soundSettings.volume * 100).roundToInt()}%" else "Off"
                    ProfileEntry("Sound · $sound", onOpenSound)
                    Text("Paper & ink", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("system", "light", "dark").forEach { value ->
                            OutlinedButton(onClick = { onThemeChange(value) }, modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text((if (value == theme) "• " else "") + value.replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                ProfileSection(title = "MORE WORDS & SHARING") {
                    ProfileEntry("Root Premium", onUnlockMoreWords)
                    ProfileEntry("Teach someone one word", onShareAWord)
                }
                Spacer(Modifier.height(16.dp))
                ProfileSection(title = "ABOUT ROOT") {
                    HowRootWorksEntry(onHowRootWorks)
                    Text("No account. Your practice stays on this device.", style = RootType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(24.dp))
            }
            }
        }
    }
}

@Composable
private fun ProfileSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ProfileEntry(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Icon(RootIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun HowRootWorksEntry(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "See how Root works", onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("How Root works", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Icon(RootIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}
