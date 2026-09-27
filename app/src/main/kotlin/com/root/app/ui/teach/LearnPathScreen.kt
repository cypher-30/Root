package com.root.app.ui.teach

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.root.app.content.Activity
import com.root.app.content.Lesson
import com.root.app.content.LibraryStatus
import com.root.app.learning.LessonProgress
import com.root.app.learning.LessonProgressSummary
import com.root.app.teach.TeachUnitRow
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.navigation.RootTabHeader
import com.root.app.ui.theme.RootType

/**
 * The Learn tab's root: every installed/available unit for the active
 * language shown as its own section, each with a visible, connected path of
 * lesson nodes — a *recommended* order, never a locked one; any available
 * lesson can be opened out of turn. Finished lessons stay marked Completed.
 * "Manage" only offers removing the unit. Reels appear only for units that
 * ship genuine recordings. A unit that isn't on the path shows an Add prompt.
 */
@Composable
fun LearnPathScreen(
    languageName: String,
    rows: List<TeachUnitRow>,
    progressFor: (String) -> Map<String, LessonProgressSummary>,
    onOpenLesson: (unitId: String, lesson: Lesson) -> Unit,
    onRemoveUnit: (String) -> Unit,
    onPlayReels: (String) -> Unit,
    onDownload: (String) -> Unit,
    onExplore: () -> Unit,
    onProfileClick: () -> Unit,
) {
    var managing by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            RootTabHeader(label = "LEARN", onProfileClick = onProfileClick, modifier = Modifier.widthIn(max = 760.dp).padding(horizontal = 24.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
                item {
                    Text("A path through\n$languageName.", style = RootType.editorialTitle)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Move through each unit at your own pace. Nothing here is locked, skip ahead or come back anytime.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (rows.isEmpty()) {
                    item {
                        Column(Modifier.padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("No units for $languageName yet.", style = RootType.editorialTitle)
                            Text(
                                "Explore has everyday situations, stories, and culture notes for $languageName in the meantime.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = onExplore, shape = MaterialTheme.shapes.small) { Text("Go to Explore") }
                        }
                    }
                }
                items(rows, key = { it.pack.id }) { row ->
                    Spacer(Modifier.height(20.dp))
                    if (row.pack.installedVersion == null || row.lessons.isEmpty()) {
                        UnitAddPrompt(row, onDownload)
                    } else {
                        UnitPathSection(
                            row = row,
                            progress = progressFor(row.pack.id),
                            onOpenLesson = { lesson -> onOpenLesson(row.pack.id, lesson) },
                            onManageUnit = { managing = row.pack.id },
                            onPlayReels = { onPlayReels(row.pack.id) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    val managedRow = rows.firstOrNull { it.pack.id == managing }
    if (managedRow != null) {
        RemoveUnitDialog(
            title = managedRow.pack.title,
            onRemove = { onRemoveUnit(managedRow.pack.id); managing = null },
            onDismiss = { managing = null },
        )
    }
}

@Composable
private fun RemoveUnitDialog(title: String, onRemove: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove $title?") },
        text = {
            Text(
                "It leaves your Learn path. The lessons you finished are remembered, " +
                    "so if you add it back later they still show as completed.",
            )
        },
        confirmButton = { TextButton(onClick = onRemove) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

@Composable
private fun UnitAddPrompt(row: TeachUnitRow, onDownload: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(row.pack.title, style = RootType.editorialTitle)
        Text(row.pack.objective, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${row.pack.lessonCount} lessons",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            row.pack.status == LibraryStatus.DOWNLOADING || row.pack.status == LibraryStatus.VERIFYING ->
                Text("Adding…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            row.pack.installedVersion != null ->
                Text("Loading lessons…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> OutlinedButton(onClick = { onDownload(row.pack.id) }, shape = MaterialTheme.shapes.small) {
                Text("Add to my path")
            }
        }
        row.pack.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun UnitPathSection(
    row: TeachUnitRow,
    progress: Map<String, LessonProgressSummary>,
    onOpenLesson: (Lesson) -> Unit,
    onManageUnit: () -> Unit,
    onPlayReels: () -> Unit,
) {
    val completedCount = row.lessons.count { progress[it.id]?.progress == LessonProgress.COMPLETED }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(row.pack.title, style = RootType.editorialTitle, modifier = Modifier.weight(1f))
            TextButton(onClick = onManageUnit) { Text("Manage") }
        }
        if (row.pack.objective.isNotBlank()) {
            Text(row.pack.objective, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "$completedCount of ${row.lessons.size} lessons completed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (row.pack.phraseCount > 0 && row.pack.audioCount > 0) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onPlayReels, shape = MaterialTheme.shapes.small) {
                Icon(RootIcons.Play, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Listen · Reels")
            }
        }
        Spacer(Modifier.height(12.dp))
        Column {
            row.lessons.forEachIndexed { index, lesson ->
                LessonPathNode(
                    lesson = lesson,
                    summary = progress[lesson.id] ?: LessonProgressSummary(LessonProgress.NOT_STARTED, hasOpenRun = false),
                    isLast = index == row.lessons.lastIndex,
                    onClick = { onOpenLesson(lesson) },
                )
            }
        }
    }
}

/** True when finishing this lesson needs a recording that doesn't exist yet. */
internal fun Lesson.needsRecording(): Boolean = activities.any {
    it is Activity.Listening && it.audioAssetId == null && it.id in requiredActivityIds
}

@Composable
private fun LessonPathNode(
    lesson: Lesson,
    summary: LessonProgressSummary,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val progress = summary.progress
    val done = progress == LessonProgress.COMPLETED || progress == LessonProgress.COMPLETED_EARLIER_REVISION
    val (nodeColor, nodeLabel) = when {
        progress == LessonProgress.COMPLETED && summary.hasOpenRun ->
            MaterialTheme.colorScheme.tertiary to "Completed · review in progress"
        progress == LessonProgress.COMPLETED -> MaterialTheme.colorScheme.tertiary to "Completed"
        progress == LessonProgress.COMPLETED_EARLIER_REVISION && summary.hasOpenRun ->
            MaterialTheme.colorScheme.tertiary to "Updated since you finished · resume"
        progress == LessonProgress.COMPLETED_EARLIER_REVISION ->
            MaterialTheme.colorScheme.tertiary to "Updated since you finished · open to redo"
        progress == LessonProgress.UNAVAILABLE -> MaterialTheme.colorScheme.outline to "Unavailable"
        lesson.needsRecording() && progress == LessonProgress.IN_PROGRESS ->
            MaterialTheme.colorScheme.outline to "Resume · needs a real recording to finish"
        lesson.needsRecording() -> MaterialTheme.colorScheme.outline to "Preview · needs a real recording to finish"
        progress == LessonProgress.IN_PROGRESS -> MaterialTheme.colorScheme.primary to "Resume"
        else -> MaterialTheme.colorScheme.outline to "Start"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; contentDescription = "${lesson.title}, $nodeLabel" }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = CircleShape,
                color = if (done) nodeColor else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.5.dp, nodeColor),
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (done) {
                        Icon(RootIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (!isLast) {
                Box(Modifier.width(1.5.dp).height(36.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(lesson.title, style = MaterialTheme.typography.titleMedium)
            Text(formatLabel(lesson.format), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(nodeLabel, style = MaterialTheme.typography.labelSmall, color = nodeColor)
        }
    }
}
