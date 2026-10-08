package com.qtekfun.ultimatemaps.transit

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.transit.TransitMode

/**
 * One small glyph per vehicle mode, drawn from scratch as strokes on a 24 x 24 grid (own work, same licence as the app) and
 * tinted by the caller. They sit at the start of a line badge so that two lines with the same short name but different modes
 * (a bus "C2" and a train "C2") can be told apart.
 */
internal object TransitModeIcons {
    private fun build(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private val bus by lazy {
        build(
            "mode_bus", "M5 4H19Q20 4 20 5V17Q20 18 19 18H5Q4 18 4 17V5Q4 4 5 4Z", "M4 11H20", "M7 21V18", "M17 21V18",
            "M7.5 14.5H7.6", "M16.5 14.5H16.6",
        )
    }
    private val tram by lazy {
        build(
            "mode_tram", "M6 6H18Q19 6 19 7V17Q19 18 18 18H6Q5 18 5 17V7Q5 6 6 6Z", "M5 12H19", "M9 6L10 3H14L15 6", "M8 21L9.5 18",
            "M16 21L14.5 18",
        )
    }
    private val train by lazy {
        build(
            "mode_train", "M8 3H16Q18 3 18 5V15Q18 17 16 17H8Q6 17 6 15V5Q6 3 8 3Z", "M6 10H18", "M8 21L10 17", "M16 21L14 17",
            "M9 13.5H9.1", "M15 13.5H15.1",
        )
    }
    private val metro by lazy { build("mode_metro", "M4 19V7L12 15L20 7V19") }
    private val ferry by lazy {
        build("mode_ferry", "M3 15L5 20H19L21 15Z", "M7 15V9H17V15", "M12 9V4", "M3 22Q6 20 9 22Q12 20 15 22Q18 20 21 22")
    }
    private val other by lazy { build("mode_other", "M12 12m-8 0a8 8 0 1 0 16 0a8 8 0 1 0-16 0Z", "M12 8V12L15 14") }

    fun of(mode: TransitMode): ImageVector = when (mode) {
        TransitMode.BUS -> bus
        TransitMode.TRAM -> tram
        TransitMode.TRAIN -> train
        TransitMode.METRO -> metro
        TransitMode.FERRY -> ferry
        TransitMode.OTHER -> other
    }

    /** "Bus line %1$s" and its siblings: the spoken form of a badge. */
    @StringRes
    fun description(mode: TransitMode): Int = when (mode) {
        TransitMode.BUS -> R.string.transit_line_bus
        TransitMode.TRAM -> R.string.transit_line_tram
        TransitMode.TRAIN -> R.string.transit_line_train
        TransitMode.METRO -> R.string.transit_line_metro
        TransitMode.FERRY -> R.string.transit_line_ferry
        TransitMode.OTHER -> R.string.transit_line_other
    }
}
