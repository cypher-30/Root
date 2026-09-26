package com.root.app.ui.teach

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.root.app.content.LibraryStatus
import com.root.app.teach.TeachUnitRow
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.theme.RootType

/** Lists installed/available teaching units: objective, lesson counts, review
 *  provenance (never implying more review than actually happened), and
 *  download/retry/update state. A separate destination from
 *  [com.root.app.ui.PacksScreen] — units are lessons, not recall packs. */
@Composable
fun TeachCatalogScreen(
    rows: List<TeachUnitRow>,
    onOpenUnit: (String) -> Unit,
    onDownload: (String) -> Unit,
    onRetry: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onBack: () -> Unit,
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
                        Text("TEACH", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(32.dp))
                    Text("Learn a\nteaching unit.", style = RootType.editorialTitle)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Guided conversations, listening, and pattern practice — separate from phrase recall.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (rows.isEmpty()) {
                    item {
                        Text(
                            "No teaching units yet.",
                            Modifier.padding(vertical = 32.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(rows, key = { it.pack.id }) { row ->
                    val pack = row.pack
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClick = { onOpenUnit(pack.id) })
                            .padding(vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(pack.title, style = RootType.editorialTitle)
                            ReviewBadge(pack.development)
                        }
                        Text(pack.objective, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${pack.languageName} · ${pack.lessonCount} lessons · ${pack.phraseCount} phrases",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        InstallStatusRow(pack, onDownload = { onDownload(pack.id) }, onRetry = { onRetry(pack.id) }, onUpdate = { onUpdate(pack.id) })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** Development-pilot content is labeled unmistakably; published content shows
 *  no badge at all (the unmarked/default expectation), never the reverse. */
@Composable
internal fun ReviewBadge(development: Boolean) {
    if (development) {
        Text("DEVELOPMENT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun InstallStatusRow(
    pack: com.root.app.content.LibraryPack,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onUpdate: () -> Unit,
) {
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (pack.status) {
            LibraryStatus.AVAILABLE, LibraryStatus.CANCELLED -> TextButton(onClick = onDownload) { Text("Download") }
            LibraryStatus.FAILED -> TextButton(onClick = onRetry) { Text("Retry") }
            LibraryStatus.UPDATE_AVAILABLE -> TextButton(onClick = onUpdate) { Text("Update") }
            else -> {}
        }
    }
}
