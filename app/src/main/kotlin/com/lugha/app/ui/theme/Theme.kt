package com.lugha.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Placeholder palette — a real design pass happens in Block 3 of the plan.
// Deliberately Kenyan-flag-adjacent (green/black/red) as a starting point, not a final call.
private val LughaGreen = Color(0xFF1B5E3A)
private val LughaGreenDark = Color(0xFF7ED0A0)

private val LightColors = lightColorScheme(
    primary = LughaGreen,
    onPrimary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = LughaGreenDark,
    onPrimary = Color.Black,
)

@Composable
fun LughaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
