package com.root.app.ui.teach

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.root.app.content.LibraryStatus

/** A unit's install/download state as a single row: status text plus the one
 *  action available for that state (download / retry / update), or nothing
 *  once it's installed and current. Shared by the Learn and Explore tabs and
 *  the unit detail screen. */
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
