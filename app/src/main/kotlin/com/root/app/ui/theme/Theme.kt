package com.root.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Placeholder palette — a real design pass happens in Block 3 of the plan.
// Deliberately Kenyan-flag-adjacent (green/black/red) as a starting point, not a final call.
private val RootGreen = Color(0xFF1B5E3A)
private val RootGreenDark = Color(0xFF7ED0A0)

private val LightColors = lightColorScheme(
    primary = RootGreen,
    onPrimary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = RootGreenDark,
    onPrimary = Color.Black,
)

@Composable
fun RootTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
