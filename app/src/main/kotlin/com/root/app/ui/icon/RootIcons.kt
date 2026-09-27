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
    val ChevronRight = icon("ChevronRight") { moveTo(9f,5f); lineTo(16f,12f); lineTo(9f,19f) }

    // Bottom-tab and header glyphs, same square-capped line vocabulary as above.
    val TabPractice = icon("TabPractice") { moveTo(4f,12f); lineTo(12f,5f); lineTo(20f,12f); moveTo(6f,10f); lineTo(6f,19f); lineTo(18f,19f); lineTo(18f,10f) }
    val TabLearn = icon("TabLearn") { moveTo(6f,19f); lineTo(6f,7f); curveTo(6f,5f,8f,4f,12f,5f); curveTo(16f,4f,18f,5f,18f,7f); lineTo(18f,19f); curveTo(16f,18f,8f,18f,6f,19f); close(); moveTo(12f,5f); lineTo(12f,18f) }
    val TabExplore = icon("TabExplore") { moveTo(15.5f,15.5f); lineTo(20f,20f); moveTo(11f,5f); curveTo(15f,5f,17f,7f,17f,11f); curveTo(17f,15f,15f,17f,11f,17f); curveTo(7f,17f,5f,15f,5f,11f); curveTo(5f,7f,7f,5f,11f,5f); close() }
    val TabProfile = icon("TabProfile") { moveTo(12f,5f); curveTo(14.5f,5f,15.5f,7f,15.5f,9f); curveTo(15.5f,11f,14.5f,12.5f,12f,12.5f); curveTo(9.5f,12.5f,8.5f,11f,8.5f,9f); curveTo(8.5f,7f,9.5f,5f,12f,5f); close(); moveTo(5f,20f); curveTo(5f,15.5f,8f,14f,12f,14f); curveTo(16f,14f,19f,15.5f,19f,20f) }
    val Search = icon("Search") { moveTo(15.5f,15.5f); lineTo(20f,20f); moveTo(11f,5f); curveTo(15f,5f,17f,7f,17f,11f); curveTo(17f,15f,15f,17f,11f,17f); curveTo(7f,17f,5f,15f,5f,11f); curveTo(5f,7f,7f,5f,11f,5f); close() }
}
