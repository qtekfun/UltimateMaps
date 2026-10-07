package com.qtekfun.mapas.route

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Small glyphs of the route panel, drawn from scratch as strokes on a 24 x 24 grid (own work, same licence as the
 * app). One colour (the caller tints them), round caps and joins, built once.
 */
internal object RouteIcons {
    private class Piece(val d: String, val filled: Boolean = false)

    private fun circle(cx: Int, cy: Int, r: Int, filled: Boolean = false) =
        Piece("M$cx ${cy}m-$r 0a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0-${2 * r} 0Z", filled)

    private fun build(name: String, vararg pieces: Piece): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (piece in pieces) {
            b.addPath(
                pathData = PathParser().parsePathString(piece.d).toNodes(),
                fill = if (piece.filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val close: ImageVector by lazy { build("route_close", Piece("M6 6L18 18"), Piece("M18 6L6 18")) }

    val up: ImageVector by lazy { build("route_up", Piece("M12 19V6"), Piece("M6 11L12 5L18 11")) }

    val down: ImageVector by lazy { build("route_down", Piece("M12 5V18"), Piece("M6 13L12 19L18 13")) }

    val chevronDown: ImageVector by lazy { build("route_chevron_down", Piece("M6 9L12 15L18 9")) }

    val chevronUp: ImageVector by lazy { build("route_chevron_up", Piece("M6 15L12 9L18 15")) }

    val chevronRight: ImageVector by lazy { build("route_chevron_right", Piece("M9 6L15 12L9 18")) }

    val check: ImageVector by lazy { build("route_check", Piece("M5 12.5L10 17.5L19 7")) }

    val play: ImageVector by lazy { build("route_play", Piece("M8 5.5L18.5 12L8 18.5Z")) }

    val car: ImageVector by lazy {
        build(
            "route_car",
            Piece("M4 16V12L6.2 7.5Q6.6 6.5 7.8 6.5H16.2Q17.4 6.5 17.8 7.5L20 12V16"),
            Piece("M3.5 16H20.5"), Piece("M4 12H20"),
            circle(7, 17, 2), circle(17, 17, 2),
        )
    }

    val foot: ImageVector by lazy {
        build(
            "route_foot",
            circle(13, 4, 2, filled = true),
            Piece("M13 8L10 12L13 15V21"), Piece("M10 12L7 14"), Piece("M13 9.5L16.5 12"), Piece("M10 12L10.5 21"),
        )
    }

    val bike: ImageVector by lazy {
        build(
            "route_bike",
            circle(6, 16, 3), circle(18, 16, 3),
            Piece("M6 16L10 9H15L18 16"), Piece("M10 9L12 16H6"), Piece("M8.5 6.5H11"), Piece("M15 9L14 6H16"),
        )
    }

    val transit: ImageVector by lazy {
        build(
            "route_transit",
            Piece("M6 4.5H18Q19 4.5 19 5.5V16Q19 17 18 17H6Q5 17 5 16V5.5Q5 4.5 6 4.5Z"),
            Piece("M5 11H19"), Piece("M7.5 20L9 17"), Piece("M16.5 20L15 17"),
            Piece("M8.5 14H8.6"), Piece("M15.4 14H15.5"),
        )
    }
}
