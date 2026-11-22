package app.tidemark.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Simple stroke icons drawn for this app on a 24 grid, 2px stroke (same
 * style as open sets such as Lucide). Tinted by Icon(tint = ...).
 */
object AppIcons {
    val Today = icon("today", "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0", "M12 7v5l3 2")
    val Stats = icon("stats", "M4 20V10", "M10 20V4", "M16 20v-7", "M22 20H2")
    val Goals = icon(
        "goals",
        "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0",
        "M17 12a5 5 0 1 1-10 0a5 5 0 1 1 10 0",
        "M13 12a1 1 0 1 1-2 0a1 1 0 1 1 2 0",
    )
    val Settings = icon(
        "settings",
        "M4 6h10", "M18 6h2", "M4 12h4", "M12 12h8", "M4 18h12",
        "M18 6a2 2 0 1 1-4 0a2 2 0 1 1 4 0",
        "M12 12a2 2 0 1 1-4 0a2 2 0 1 1 4 0",
        "M20 18a2 2 0 1 1-4 0a2 2 0 1 1 4 0",
    )
    val Check = icon("check", "M20 6L9 17l-5-5")
    val Warning = icon("warning", "M12 9v4", "M12 17h.01", "M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z")
    val Error = icon("error", "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0", "M12 8v4", "M12 16h.01")
    val Close = icon("close", "M18 6L6 18", "M6 6l12 12")
    val Back = icon("back", "M15 18l-6-6l6-6")
    val Chevron = icon("chevron", "M9 18l6-6l-6-6")
    val Plus = icon("plus", "M12 5v14", "M5 12h14")
    val Pause = icon("pause", "M8 5v14", "M16 5v14")
    val Video = icon("video", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M10 8l6 4l-6 4z")

    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(
            name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
        )
        paths.forEach { d ->
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }
}
