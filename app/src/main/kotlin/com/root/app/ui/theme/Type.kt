package com.root.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.root.app.R

// Bundled, licensed fonts (SIL OFL; see app/src/main/assets/licenses) rather than
// fetched at runtime. Explicit variation settings (weight/optical size) are used
// because the optical-size axis does not automatically track Compose's font size.
@OptIn(ExperimentalTextApi::class)
val EditorialSerif = FontFamily(
    Font(R.font.source_serif, variationSettings = FontVariation.Settings(
        FontVariation.weight(400), FontVariation.opticalSizing(48.sp),
    )),
)

@OptIn(ExperimentalTextApi::class)
val PlainSans = FontFamily(
    Font(R.font.inter, variationSettings = FontVariation.Settings(
        FontVariation.weight(400), FontVariation.opticalSizing(14.sp),
    )),
    Font(R.font.inter, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(
        FontVariation.weight(500), FontVariation.opticalSizing(14.sp),
    )),
)

object RootType {
    // Semantic text styles used directly by screens (docs/DESIGN.md's type table),
    // in addition to being wired into Material's `Typography` roles below so
    // standard components (buttons, TextFields) pick up the same faces.
    val heroAnswer = TextStyle(fontFamily = EditorialSerif, fontSize = 48.sp, lineHeight = 58.sp, letterSpacing = 0.6.sp)
    val editorialTitle = TextStyle(fontFamily = EditorialSerif, fontSize = 32.sp, lineHeight = 39.sp)
    val promptLarge = TextStyle(fontFamily = PlainSans, fontSize = 22.sp, lineHeight = 31.sp)
    val label = TextStyle(fontFamily = PlainSans, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.5.sp)
    val meta = TextStyle(fontFamily = PlainSans, fontSize = 12.sp, lineHeight = 18.sp)
}

private fun sans(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = PlainSans, fontSize = size.sp, lineHeight = height.sp, fontWeight = weight)

val RootTypography = Typography(
    displayLarge = RootType.heroAnswer.copy(fontSize = 56.sp, lineHeight = 64.sp),
    displayMedium = RootType.heroAnswer,
    displaySmall = RootType.heroAnswer.copy(fontSize = 40.sp, lineHeight = 49.sp),
    headlineLarge = RootType.editorialTitle,
    headlineMedium = RootType.editorialTitle.copy(fontSize = 28.sp, lineHeight = 35.sp),
    headlineSmall = RootType.editorialTitle.copy(fontSize = 24.sp, lineHeight = 31.sp),
    titleLarge = sans(22, 30),
    titleMedium = sans(16, 24, FontWeight.Medium),
    titleSmall = sans(14, 20, FontWeight.Medium),
    bodyLarge = sans(16, 25), bodyMedium = sans(14, 22), bodySmall = sans(12, 18),
    labelLarge = sans(14, 20, FontWeight.Medium),
    labelMedium = sans(12, 18, FontWeight.Medium), labelSmall = RootType.label,
)
