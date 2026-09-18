package com.root.app.ui.root

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.root.app.ui.motion.RootMotion
import kotlin.math.min

/** Frozen, ordered branch geometry shared by the mark, launch, and recall path. */
object RootGeometry {
    fun branches(): List<Path> = listOf(
        Path().apply { moveTo(50f, 12f); cubicTo(50f, 27f, 48f, 42f, 51f, 56f); cubicTo(53f, 66f, 49f, 77f, 48f, 90f) },
        Path().apply { moveTo(49f, 34f); cubicTo(39f, 43f, 29f, 44f, 22f, 55f); lineTo(12f, 69f) },
        Path().apply { moveTo(50f, 45f); cubicTo(62f, 50f, 65f, 59f, 76f, 64f); lineTo(89f, 69f) },
        Path().apply { moveTo(30f, 47f); cubicTo(29f, 58f, 32f, 64f, 27f, 75f); lineTo(23f, 86f) },
        Path().apply { moveTo(67f, 58f); cubicTo(64f, 68f, 69f, 77f, 71f, 85f) },
    )
}

@Composable
/**
 * Draws the shared root-branch geometry up to `progress` (0..1), branch by branch:
 * with N branches, branch index i is fully drawn once `progress >= (i+1)/N`, and
 * partially drawn in between via [PathMeasure.getSegment]. Coordinates are authored
 * in a fixed 100x100 unit space and scaled to the actual draw size, so the same path
 * data renders identically at icon scale, launch scale, and recall-roots scale.
 */
fun RootPath(
    progress: Float,
    modifier: Modifier = Modifier.size(100.dp),
    color: Color = MaterialTheme.colorScheme.primary,
    showGuide: Boolean = false,
    description: String? = null,
) {
    val branches = remember { RootGeometry.branches() }
    val measures = remember { branches.map { path -> PathMeasure().apply { setPath(path, false) } } }
    Canvas(if (description == null) modifier else modifier.semantics { contentDescription = description }) {
        val fraction = progress.coerceIn(0f, 1f)
        val lineWidth = (1.dp.toPx() * 100f / min(size.width, size.height).coerceAtLeast(1f)).coerceAtLeast(1.6f)
        scale(size.width / 100f, size.height / 100f, pivot = Offset.Zero) {
            if (showGuide) branches.forEach { drawPath(it, color.copy(alpha = 0.12f), style = Stroke(1.25f)) }
            measures.forEachIndexed { index, measure ->
                val amount = (fraction * measures.size - index).coerceIn(0f, 1f)
                if (amount > 0f) {
                    val segment = Path()
                    measure.getSegment(0f, measure.length * amount, segment, true)
                    drawPath(segment, color, style = Stroke(lineWidth, cap = StrokeCap.Square))
                }
            }
            drawCircle(color, 1.8f, Offset(50f, 12f))
        }
    }
}

/** The in-session "roots grown" indicator: progress is `correct / 8`, the fixed
 *  session cap (see [com.root.app.data.SessionQueue]'s `initialLimit`), animated with
 *  [RootMotion.settle] rather than a linear tween so growth reads as organic. */
@Composable
fun RecallRoots(correct: Int, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = (correct / 8f).coerceIn(0f, 1f),
        animationSpec = RootMotion.settle(), label = "Recall roots",
    )
    RootPath(progress, modifier, MaterialTheme.colorScheme.tertiary, showGuide = true,
        description = "Roots grown from $correct distinct phrases recalled in this session")
}
