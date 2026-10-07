package com.qtekfun.ultimatemaps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Map scale maths. MapLibre's world is 512 units wide at zoom 0 (the units are density-independent, so they are
 * dp on screen; this is the documented tile size of the style engine, not measured on a device).
 */
object MapScale {
    /** WGS84 equatorial circumference in metres. */
    const val EQUATOR_METERS = 40_075_016.686
    const val WORLD_SIZE_AT_ZOOM_0 = 512.0

    /** Ground metres covered by one screen unit (dp) at [latitude] and [zoom]. */
    fun metersPerDp(latitude: Double, zoom: Double): Double =
        EQUATOR_METERS * cos(latitude * PI / 180.0) / (WORLD_SIZE_AT_ZOOM_0 * 2.0.pow(zoom))

    /** The largest 1, 2 or 5 times a power of ten that is at most [maxMeters]; null below one metre. */
    fun niceMeters(maxMeters: Double): Int? {
        if (!maxMeters.isFinite() || maxMeters < 1.0) return null
        val power = 10.0.pow(floor(log10(maxMeters)))
        val unit = when {
            maxMeters >= 5 * power -> 5
            maxMeters >= 2 * power -> 2
            else -> 1
        }
        return (unit * power).toInt()
    }

    /** A scale bar: [meters] of ground drawn [widthDp] wide. */
    data class Bar(val meters: Int, val widthDp: Float)

    /** The bar for the view centre, at most [maxWidthDp] wide; null when the scale cannot be given (a pole, absurd zoom). */
    fun bar(latitude: Double, zoom: Double, maxWidthDp: Float): Bar? {
        val perDp = metersPerDp(latitude, zoom)
        if (!perDp.isFinite() || perDp <= 0.0) return null
        val meters = niceMeters(perDp * maxWidthDp) ?: return null
        return Bar(meters, (meters / perDp).toFloat())
    }
}

/** Distance label of a bar: "200 m" below a kilometre, "2 km" from there (metric, whole numbers: the bars are 1, 2, 5 steps). */
@Composable
private fun scaleLabel(meters: Int): String =
    if (meters >= 1000) stringResource(R.string.map_scale_kilometers, meters / 1000) else stringResource(R.string.map_scale_meters, meters)

/** Scale bar over the map. It follows the view centre; [latitude] and [zoom] come from the camera. */
@Composable
fun ScaleBar(latitude: Double, zoom: Double, modifier: Modifier = Modifier) {
    val bar = remember(latitude, zoom) { MapScale.bar(latitude, zoom, MAX_WIDTH_DP) } ?: return
    val label = scaleLabel(bar.meters)
    val description = stringResource(R.string.map_scale_description, label)
    val line = Mapas.colors.label
    Column(
        modifier
            .clip(Mapas.shapes.control)
            .background(Mapas.colors.control)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = description }
            .testTag("map_scale"),
    ) {
        BasicText(label, style = Mapas.typography.caption.copy(color = line), modifier = Modifier.testTag("map_scale_label"))
        Canvas(Modifier.width(bar.widthDp.dp).height(6.dp)) {
            val stroke = 1.5f * density
            val y = size.height
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            drawLine(line, Offset(0f, 0f), Offset(0f, y), strokeWidth = stroke)
            drawLine(line, Offset(size.width, 0f), Offset(size.width, y), strokeWidth = stroke)
        }
    }
}

private const val MAX_WIDTH_DP = 96f
