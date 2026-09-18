package com.root.app.ui.brand

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.root.app.ui.root.RootPath
import com.root.app.ui.theme.RootTheme
import com.root.app.ui.theme.RootType

/** The Root wordmark: the fully-drawn brand path ([RootPath] at progress 1) beside
 *  the serif "Root" word. Used compact in screen headers and full-size in
 *  [com.root.app.ui.DesignStudyScreen] and the launch sequence. */
@Composable
fun RootMark(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RootPath(1f, Modifier.size(if (compact) 32.dp else 48.dp))
        Text("Root", style = RootType.editorialTitle.copy(
            fontSize = if (compact) 26.sp else 38.sp, letterSpacing = 0.8.sp,
        ), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Preview(name = "Mark / paper", showBackground = true)
@Preview(name = "Mark / charcoal", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun BrandPreview() {
    RootTheme {
        Surface {
            Column {
                RootMark()
                Row { listOf(24, 48, 96, 192).forEach { RootPath(1f, Modifier.size(it.dp)) } }
            }
        }
    }
}
