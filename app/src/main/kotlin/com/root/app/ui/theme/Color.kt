package com.root.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Warm paper / warm charcoal palette (docs/DESIGN.md's color table). Independently
// tuned per mode rather than one palette inverted: dark mode uses lifted surfaces
// and fine rules instead of brighter elevation shadows, and the accent ("tertiary",
// the earth pigment) is lighter in dark mode for contrast against a darker ground.
val Paper = Color(0xFFF4F0E8)
val Ink = Color(0xFF27251F)

internal val LightColors = lightColorScheme(
    primary = Ink, onPrimary = Paper,
    primaryContainer = Color(0xFFE5DED2), onPrimaryContainer = Ink,
    inversePrimary = Color(0xFFE5DED2),
    secondary = Color(0xFF655E53), onSecondary = Paper,
    secondaryContainer = Color(0xFFE9E2D6), onSecondaryContainer = Ink,
    tertiary = Color(0xFF995137), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0DCCE), onTertiaryContainer = Color(0xFF633320),
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink,
    surfaceVariant = Color(0xFFEAE4D9), onSurfaceVariant = Color(0xFF655E53),
    surfaceTint = Color.Transparent,
    inverseSurface = Ink, inverseOnSurface = Paper,
    outline = Color(0xFF898174), outlineVariant = Color(0xFFD6CFC2),
    error = Color(0xFFA1342D), onError = Color.White,
    errorContainer = Color(0xFFFFDAD4), onErrorContainer = Color(0xFF67201C),
    scrim = Ink,
    surfaceBright = Color(0xFFFCF9F3), surfaceDim = Color(0xFFE8E1D5),
    surfaceContainer = Color(0xFFEFE9DF),
    surfaceContainerLowest = Color(0xFFFCF9F3),
    surfaceContainerLow = Color(0xFFF8F4ED),
    surfaceContainerHigh = Color(0xFFEAE3D7),
    surfaceContainerHighest = Color(0xFFE3DBCE),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFEAE0D0), onPrimary = Color(0xFF201E1A),
    primaryContainer = Color(0xFF38332C), onPrimaryContainer = Color(0xFFEAE0D0),
    inversePrimary = Ink,
    secondary = Color(0xFFB9AE9C), onSecondary = Color(0xFF27231D),
    secondaryContainer = Color(0xFF353028), onSecondaryContainer = Color(0xFFDFD4C2),
    tertiary = Color(0xFFD99D7D), onTertiary = Color(0xFF362319),
    tertiaryContainer = Color(0xFF4C3226), onTertiaryContainer = Color(0xFFEBC3AA),
    background = Color(0xFF201E1A), onBackground = Color(0xFFEAE0D0),
    surface = Color(0xFF201E1A), onSurface = Color(0xFFEAE0D0),
    surfaceVariant = Color(0xFF332F28), onSurfaceVariant = Color(0xFFB9AE9C),
    surfaceTint = Color.Transparent,
    inverseSurface = Paper, inverseOnSurface = Ink,
    outline = Color(0xFF918675), outlineVariant = Color(0xFF4C453A),
    error = Color(0xFFE9A398), onError = Color(0xFF491A15),
    errorContainer = Color(0xFF61312A), onErrorContainer = Color(0xFFF4C8BF),
    scrim = Color.Black,
    surfaceBright = Color(0xFF3A352E), surfaceDim = Color(0xFF191713),
    surfaceContainer = Color(0xFF29251F),
    surfaceContainerLowest = Color(0xFF181612),
    surfaceContainerLow = Color(0xFF26221D),
    surfaceContainerHigh = Color(0xFF302B24),
    surfaceContainerHighest = Color(0xFF373128),
)
