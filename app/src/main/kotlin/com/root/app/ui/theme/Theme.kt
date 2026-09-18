package com.root.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Applies the paper/charcoal Material color scheme, [RootTypography], and
 * [RootShapes] to [content]. [darkTheme] defaults to the system setting but can be
 * overridden by the user's saved appearance preference (see
 * [com.root.app.data.RootPreferences.theme]).
 */
@Composable
fun RootTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = RootTypography,
        shapes = RootShapes,
        content = content,
    )
}
