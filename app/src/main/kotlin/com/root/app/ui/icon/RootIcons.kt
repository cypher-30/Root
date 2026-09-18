package com.root.app.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Thin, square-capped line icons matching the app's linework vocabulary — no filled
 *  glyphs, emoji, or third-party icon packs. Each icon is built directly from path
 *  commands rather than imported SVG/XML so stroke weight stays visually consistent. */
object RootIcons {
    private fun icon(name: String, draw: PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square, strokeLineJoin = StrokeJoin.Miter,
                pathFillType = PathFillType.NonZero, pathBuilder = draw)
        }.build()

    val Back = icon("Back") { moveTo(19f,12f); lineTo(5f,12f); moveTo(11f,6f); lineTo(5f,12f); lineTo(11f,18f) }
    val More = icon("More") { moveTo(5f,11f); lineTo(5f,13f); moveTo(12f,11f); lineTo(12f,13f); moveTo(19f,11f); lineTo(19f,13f) }
    val Play = icon("Play") { moveTo(8f,5f); lineTo(19f,12f); lineTo(8f,19f); close() }
    val Record = icon("Record") { moveTo(12f,5f); curveTo(21f,5f,21f,19f,12f,19f); curveTo(3f,19f,3f,5f,12f,5f); close() }
    val Share = icon("Share") { moveTo(12f,15f); lineTo(12f,3f); moveTo(7f,8f); lineTo(12f,3f); lineTo(17f,8f); moveTo(5f,13f); lineTo(5f,21f); lineTo(19f,21f); lineTo(19f,13f) }
    val Check = icon("Check") { moveTo(5f,12f); lineTo(10f,17f); lineTo(20f,6f) }
    val Lock = icon("Lock") { moveTo(7f,10f); lineTo(7f,7f); curveTo(7f,0f,17f,0f,17f,7f); lineTo(17f,10f); moveTo(5f,10f); lineTo(19f,10f); lineTo(19f,21f); lineTo(5f,21f); close(); moveTo(12f,14f); lineTo(12f,17f) }
    val Plus = icon("Plus") { moveTo(12f,5f); lineTo(12f,19f); moveTo(5f,12f); lineTo(19f,12f) }
    val Close = icon("Close") { moveTo(6f,6f); lineTo(18f,18f); moveTo(18f,6f); lineTo(6f,18f) }
    val Stop = icon("Stop") { moveTo(6f,6f); lineTo(18f,6f); lineTo(18f,18f); lineTo(6f,18f); close() }
}
