package com.qtekfun.ultimatemaps.cameras

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.cameras.IncidentBannerMachine
import com.qtekfun.ultimatemaps.core.cameras.IncidentBannerState
import com.qtekfun.ultimatemaps.core.cameras.IncidentKind
import com.qtekfun.ultimatemaps.route.RouteFormat
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import java.util.Locale

/** The machine behind the incident banner, provided by the activity; absent in previews and screens without it. */
val LocalIncidentBanner = staticCompositionLocalOf<IncidentBannerMachine?> { null }

/**
 * The temporary banner for a traffic incident on the route ahead: kind icon, text, distance and a circular clock
 * with the seconds left. It goes by itself after five seconds and a tap dismisses it. Visual only: it appears
 * whether or not the voice is muted. Place it under the camera chip.
 */
@Composable
fun IncidentBanner(modifier: Modifier = Modifier, glove: Boolean = false) {
    val machine = LocalIncidentBanner.current ?: return
    val state by machine.state.collectAsState()
    IncidentBannerContent(state, machine::dismiss, modifier, glove)
}

private val WarnRed = Color(0xFFC5221F) // white on it: contrast above 5:1
private val WarnBrown = Color(0xFF9A4A00)
private val WeatherBlue = Color(0xFF0B57D0)

/** Stateless: the state comes in, the tap goes out, so tests need no machine and no time. */
@Composable
fun IncidentBannerContent(state: IncidentBannerState?, onDismiss: () -> Unit, modifier: Modifier = Modifier, glove: Boolean = false) {
    val s = state ?: return
    val locale: Locale = LocalConfiguration.current.locales[0]
    val title = stringResource(titleRes(s.kind))
    val distance = RouteFormat.distance(s.distanceMeters.toDouble(), locale)
    val description = stringResource(R.string.incident_banner_description, title, distance)
    val dismissLabel = stringResource(R.string.incident_banner_dismiss)
    Row(
        modifier
            .testTag("incident_banner")
            .defaultMinSize(minHeight = if (glove) 72.dp else 52.dp)
            .background(colorOf(s.kind), RoundedCornerShape(if (glove) 20.dp else 16.dp))
            .clickable(onClickLabel = dismissLabel, onClick = onDismiss)
            .padding(horizontal = if (glove) 18.dp else 14.dp, vertical = 8.dp)
            // One description for screen readers; the ticking seconds are not part of it, so it is not re-read every second.
            .semantics(mergeDescendants = true) {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        KindIcon(s.kind, Modifier.size(if (glove) 40.dp else 28.dp).testTag("incident_banner_icon"))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.defaultMinSize(minWidth = if (glove) 120.dp else 96.dp)) {
            Column(Modifier.clearAndSetSemantics { }) {
                BasicText(
                    title,
                    style = Mapas.typography.callout.copy(color = Color.White, fontSize = if (glove) 20.sp else 15.sp),
                    modifier = Modifier.testTag("incident_banner_title"),
                )
                BasicText(
                    distance,
                    style = Mapas.typography.title.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (glove) 32.sp else 22.sp),
                    modifier = Modifier.testTag("incident_banner_distance"),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        CountdownClock(s, glove, Modifier.testTag("incident_banner_clock"))
    }
}

/** A ring that empties as the time runs out, with the whole seconds left in the middle. */
@Composable
private fun CountdownClock(s: IncidentBannerState, glove: Boolean, modifier: Modifier) {
    val size = if (glove) 56.dp else 40.dp
    Box(modifier.size(size).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = (if (glove) 5.dp else 4.dp).toPx()
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.3f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(Color.White, -90f, 360f * s.fraction, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Butt))
        }
        BasicText(
            s.remainingSeconds.toString(),
            style = Mapas.typography.callout.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (glove) 24.sp else 17.sp),
            modifier = Modifier.testTag("incident_banner_seconds"),
        )
    }
}

private fun titleRes(k: IncidentKind) = when (k) {
    IncidentKind.V16 -> R.string.incident_banner_v16
    IncidentKind.ACCIDENT -> R.string.incident_banner_accident
    IncidentKind.CLOSURE -> R.string.incident_banner_closure
    IncidentKind.CONGESTION -> R.string.incident_banner_congestion
    IncidentKind.OBSTACLE -> R.string.incident_banner_obstacle
    IncidentKind.WEATHER -> R.string.incident_banner_weather
    IncidentKind.ROADWORKS -> R.string.incident_banner_roadworks
}

private fun colorOf(k: IncidentKind) = when (k) {
    IncidentKind.ACCIDENT, IncidentKind.CLOSURE, IncidentKind.V16 -> WarnRed
    IncidentKind.WEATHER -> WeatherBlue
    IncidentKind.CONGESTION, IncidentKind.OBSTACLE, IncidentKind.ROADWORKS -> WarnBrown
}

/** A white warning triangle; the shape (not only the colour) says "caution". The kind is also in the text. */
@Composable
private fun KindIcon(kind: IncidentKind, modifier: Modifier) {
    val ink = colorOf(kind)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w / 2, h * 0.06f); lineTo(w * 0.97f, h * 0.92f); lineTo(w * 0.03f, h * 0.92f); close()
        }
        drawPath(p, Color.White)
        drawRect(ink, Offset(w * 0.46f, h * 0.36f), Size(w * 0.08f, h * 0.28f))
        drawCircle(ink, h * 0.05f, Offset(w / 2, h * 0.76f))
    }
}
