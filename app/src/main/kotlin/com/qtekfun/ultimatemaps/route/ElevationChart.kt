package com.qtekfun.ultimatemaps.route

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.routing.ElevationProfile
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import kotlin.math.roundToInt

/**
 * "↑ 120 m ↓ 95 m" under the route figures; a tap opens or closes the elevation profile. Draws nothing when there is no
 * [profile] (the maps had no heights for the route), so a route without data looks exactly as before.
 */
@Composable
internal fun ElevationSummary(profile: ElevationProfile?) {
    if (profile == null) return
    var open by rememberSaveable { mutableStateOf(false) }
    val up = profile.roundedAscent
    val down = profile.roundedDescent
    val description = stringResource(R.string.route_elevation_toggle_cd, up, down)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clickable { open = !open }
                .semantics(mergeDescendants = true) { contentDescription = description }
                .testTag("route_elevation"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(
                stringResource(R.string.route_elevation_summary, up, down),
                style = Mapas.typography.callout.copy(color = Mapas.colors.label),
            )
            Icon(if (open) RouteIcons.chevronUp else RouteIcons.chevronDown, Mapas.colors.secondaryLabel, 16.dp)
        }
        if (open) {
            ElevationChart(profile, Modifier.testTag("route_elevation_chart"))
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The profile as a filled line chart (Canvas, no chart library), with the lowest and highest height and the steepest grades under it. */
@Composable
internal fun ElevationChart(profile: ElevationProfile, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val colors = Mapas.colors
    val line = colors.accent
    val fill = colors.accent.copy(alpha = 0.18f)
    val grid = colors.separator
    val low = profile.minMeters.roundToInt()
    val high = profile.maxMeters.roundToInt()
    val description = stringResource(R.string.route_elevation_chart_cd, low, high, profile.maxGradePercent.roundToInt())
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clearAndSetSemantics { contentDescription = description },
        ) {
            val s = profile.samples
            if (s.size < 2) return@Canvas
            val span = (profile.maxMeters - profile.minMeters).coerceAtLeast(MIN_SPAN_METERS)
            val pad = span * 0.08
            val bottom = profile.minMeters - pad
            val top = bottom + span + 2 * pad
            val w = size.width
            val h = size.height
            fun x(d: Double) = (d / profile.totalMeters * w).toFloat()
            fun y(a: Double) = (h - ((a - bottom) / (top - bottom) * h)).toFloat()

            drawLine(grid, Offset(0f, h - 0.5f), Offset(w, h - 0.5f), strokeWidth = 1.dp.toPx())
            drawLine(grid, Offset(0f, 0.5f), Offset(w, 0.5f), strokeWidth = 1.dp.toPx())
            val area = Path().apply {
                moveTo(x(s.first().distanceMeters), h)
                for (p in s) lineTo(x(p.distanceMeters), y(p.altitudeMeters))
                lineTo(x(s.last().distanceMeters), h)
                close()
            }
            drawPath(area, fill)
            val stroke = Path().apply {
                moveTo(x(s.first().distanceMeters), y(s.first().altitudeMeters))
                for (i in 1 until s.size) lineTo(x(s[i].distanceMeters), y(s[i].altitudeMeters))
            }
            drawPath(stroke, line, style = Stroke(width = 2.dp.toPx()))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(
                stringResource(R.string.route_elevation_range, low, high),
                style = Mapas.typography.caption.copy(color = colors.secondaryLabel),
            )
            BasicText(
                stringResource(
                    R.string.route_elevation_grade,
                    profile.maxGradePercent.roundToInt(),
                    (-profile.minGradePercent).roundToInt(),
                ),
                style = Mapas.typography.caption.copy(color = colors.secondaryLabel),
            )
        }
        BasicText(
            RouteFormat.distance(profile.totalMeters, locale),
            style = Mapas.typography.caption.copy(color = colors.secondaryLabel),
            modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {},
        )
    }
}

/** A flat road still gets a visible band instead of a line glued to the top of the chart. */
private const val MIN_SPAN_METERS = 20.0
