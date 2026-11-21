package com.mycro.cinegl.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** 自绘线条图标（24x24 视口），避免引入整个 material-icons-extended。 */
object CineIcons {
    val Torch = icon("torch", "M13 2.5 L5 13.5 H11 L10 21.5 L19 10 H13 Z")
    val Flip = icon(
        "flip",
        "M4.5 11.5 A7.5 7.5 0 0 1 18 7.2", "M18.6 3.6 V7.6 H14.6",
        "M19.5 12.5 A7.5 7.5 0 0 1 6 16.8", "M5.4 20.4 V16.4 H9.4",
    )
    val More = icon(
        "more",
        "M5 12 m-1.6 0 a1.6 1.6 0 1 0 3.2 0 a1.6 1.6 0 1 0 -3.2 0",
        "M12 12 m-1.6 0 a1.6 1.6 0 1 0 3.2 0 a1.6 1.6 0 1 0 -3.2 0",
        "M19 12 m-1.6 0 a1.6 1.6 0 1 0 3.2 0 a1.6 1.6 0 1 0 -3.2 0",
        filled = true,
    )
    val Focus = icon(
        "focus",
        "M4 9 V4 H9", "M15 4 H20 V9", "M20 15 V20 H15", "M9 20 H4 V15",
        "M12 12 m-3 0 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
    )
    val WhiteBalance = icon(
        "wb",
        "M12 12 m-4 0 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0",
        "M12 2.5 V4.5", "M12 19.5 V21.5", "M2.5 12 H4.5", "M19.5 12 H21.5",
        "M5.3 5.3 L6.7 6.7", "M17.3 17.3 L18.7 18.7", "M5.3 18.7 L6.7 17.3", "M17.3 6.7 L18.7 5.3",
    )
    val Shutter = icon(
        "shutter",
        "M12 12 m-9 0 a9 9 0 1 0 18 0 a9 9 0 1 0 -18 0",
        "M12 3 L14.6 10.5", "M19.8 7.5 L13.4 13", "M19.8 16.5 L11 15.2",
        "M12 21 L9.4 13.5", "M4.2 16.5 L10.6 11", "M4.2 7.5 L13 8.8",
    )
    val Exposure = icon("ev", "M4 4 H20 V20 H4 Z", "M20 4 L4 20", "M6.5 8.5 H11.5", "M9 6 V11", "M12.5 15.5 H17.5")
    val Close = icon("close", "M6 6 L18 18", "M18 6 L6 18")
    val Plus = icon("plus", "M12 5 V19", "M5 12 H19")
    val Reset = icon("reset", "M5 12 A7 7 0 1 0 7.5 6.6", "M4.5 3.5 V7.5 H8.5")

    private fun icon(name: String, vararg paths: String, filled: Boolean = false): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = if (filled) SolidColor(Color.White) else null,
                stroke = if (filled) null else SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
