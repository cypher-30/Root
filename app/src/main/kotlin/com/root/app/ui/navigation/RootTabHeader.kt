package com.root.app.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.root.app.ui.icon.RootIcons

/**
 * Shared top row for the Practice/Learn/Explore tab roots: an eyebrow label
 * on the left, an optional trailing slot (e.g. a search field toggle), and a
 * Profile shortcut on the right — the same destination the Profile tab
 * itself opens, not a second copy of it (see docs/DESIGN.md's navigation
 * contract). Absent on Profile's own root, which has no need to shortcut to itself.
 */
@Composable
fun RootTabHeader(
    label: String,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        trailing()
        IconButton(onClick = onProfileClick) {
            Icon(RootIcons.TabProfile, contentDescription = "Profile")
        }
    }
}
