package com.root.app.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

/** A subtle, deterministic paper-grain texture behind card content. A fixed seed
 *  ([Random](407)) means the speckle pattern is identical every recomposition/frame
 *  instead of animating or flickering, and one Canvas path works on every supported
 *  API level (no AGSL/GPU-shader branch to maintain). Deliberately near-invisible:
 *  0.035 alpha keeps it subordinate to text. */
fun Modifier.paperSurface(ink: Color) = drawWithCache {
    // Cached deterministic specks avoid a shader/API split and never animate behind text.
    val random = Random(407)
    val points = List((size.width * size.height / 1800f).toInt().coerceAtMost(1800)) {
        Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
    }
    onDrawBehind { points.forEach { drawCircle(ink.copy(alpha = 0.035f), 0.55f, it) } }
}
