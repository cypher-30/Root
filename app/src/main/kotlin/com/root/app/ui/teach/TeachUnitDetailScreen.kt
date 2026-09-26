@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.root.app.ui.teach

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.root.app.content.Lesson
import com.root.app.content.LessonFormat
import com.root.app.content.LibraryPack
import com.root.app.content.LibraryStatus
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.theme.RootType

/** Objective, per-lesson format, and download/cancel/retry/update/uninstall
 *  controls for one teaching unit's installed pack. [pack] is the frozen,
 *  type-safe catalog entry (see `com.root.app.content.LibraryPack`); [lessons]
 *  is empty until the unit is installed and its manifest has been fetched
 *  (see [com.root.app.teach.ContentViewModel.loadDetail]). */
@Composable
fun TeachUnitDetailScreen(
    pack: LibraryPack,
    lessons: List<Lesson>,
    onOpenLesson: (Lesson) -> Unit,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUninstall: () -> Unit,
    onRetry: () -> Unit,
    onUpdate: () -> Unit,
    // True for a lesson with a currently-open (ACTIVE/PAUSED) durable run —
    // see `com.root.app.teach.ContentViewModel.resumableLessonIds`, sourced
    // from `LessonRunner.openRunState`, never fabricated/guessed client-side.
    isResumable: (String) -> Boolean = { false },
    // Null hides the action entirely — only shown once this unit's linked
    // recall phrases are actually installed, never presented as available
    // before there is anything to review.
    onReviewPhrases: (() -> Unit)? = null,
    // Provide a callback to launch the reel playback if audio is available
    onPlayReels: (() -> Unit)? = null,
) {
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(RootIcons.Back, contentDescription = "Back") }
                        ReviewBadge(pack.development)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(pack.title, style = RootType.editorialTitle)
                    Spacer(Modifier.height(8.dp))
                    Text(pack.objective, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${pack.languageName} · ${pack.lessonCount} lessons · ${pack.phraseCount} phrases",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pack.development) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Development content: not yet native-speaker reviewed. Some audio may be unavailable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    UnitInstallControls(pack, onDownload, onCancel, onUninstall, onRetry, onUpdate)
                    if (onReviewPhrases != null || onPlayReels != null) {
                        Spacer(Modifier.height(12.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (onReviewPhrases != null) {
                                OutlinedButton(onClick = onReviewPhrases, shape = MaterialTheme.shapes.small) {
                                    Text("Review these phrases")
                                }
                            }
                            if (onPlayReels != null) {
                                OutlinedButton(onClick = onPlayReels, shape = MaterialTheme.shapes.small) {
                                    Text("Play reel")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                // A FAILED status with a nonnull installedVersion still has old
                // installed content reachable — the "download to see lessons"
                // message is gated on there being no lessons loaded at all, not
                // on the current catalog status.
                if (lessons.isEmpty() && pack.installedVersion == null) {
                    item {
                        Text(
                            "Download this unit to see its lessons.",
                            Modifier.padding(vertical = 24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(lessons, key = { it.id }) { lesson ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClick = { onOpenLesson(lesson) })
                            .padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(lesson.title, style = MaterialTheme.typography.titleMedium)
                            Text(formatLabel(lesson.format), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Sourced from the runner's own durable state
                        // (`LessonRunner.openRunState`) via `isResumable`, never
                        // guessed/cached client-side — opening the lesson itself
                        // resumes the exact persisted run if one exists.
                        OutlinedButton(onClick = { onOpenLesson(lesson) }, shape = MaterialTheme.shapes.small) {
                            Text(if (isResumable(lesson.id)) "Resume" else "Start")
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

internal fun formatLabel(format: LessonFormat): String = when (format) {
    LessonFormat.GUIDED_CONVERSATION -> "Guided conversation"
    LessonFormat.LISTENING -> "Listening / story"
    LessonFormat.PATTERN_WORKSHOP -> "Pattern workshop"
}

@Composable
internal fun UnitInstallControls(
    pack: LibraryPack,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUninstall: () -> Unit,
    onRetry: () -> Unit,
    onUpdate: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val text = when (pack.status) {
            LibraryStatus.AVAILABLE -> "Not downloaded · ${pack.downloadBytes / 1024} KB"
            LibraryStatus.DOWNLOADING -> "Downloading…"
            LibraryStatus.VERIFYING -> "Verifying…"
            LibraryStatus.INSTALLED -> "Downloaded"
            LibraryStatus.UPDATE_AVAILABLE -> "Update available"
            LibraryStatus.FAILED -> "Download failed: ${pack.error ?: "please retry"}"
            LibraryStatus.CANCELLED -> "Download cancelled"
            LibraryStatus.RETIRED -> "No longer available"
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (pack.status) {
            LibraryStatus.AVAILABLE, LibraryStatus.CANCELLED ->
                OutlinedButton(onClick = onDownload, shape = MaterialTheme.shapes.small) { Text("Download") }
            LibraryStatus.DOWNLOADING, LibraryStatus.VERIFYING ->
                OutlinedButton(onClick = onCancel, shape = MaterialTheme.shapes.small) { Text("Cancel") }
            LibraryStatus.FAILED ->
                OutlinedButton(onClick = onRetry, shape = MaterialTheme.shapes.small) { Text("Retry") }
            LibraryStatus.UPDATE_AVAILABLE ->
                OutlinedButton(onClick = onUpdate, shape = MaterialTheme.shapes.small) { Text("Update") }
            LibraryStatus.INSTALLED ->
                OutlinedButton(onClick = onUninstall, shape = MaterialTheme.shapes.small) { Text("Remove") }
            LibraryStatus.RETIRED -> {}
        }
    }
}
