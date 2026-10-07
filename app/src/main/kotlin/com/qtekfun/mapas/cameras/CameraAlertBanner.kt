package com.qtekfun.mapas.cameras

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.cameras.AlertBannerState
import com.qtekfun.mapas.core.cameras.AlertCategory
import com.qtekfun.mapas.route.RouteFormat
import com.qtekfun.mapas.ui.theme.Mapas
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** The state of the visual alert, provided by the activity; absent in previews and in screens that have no alerts. */
val LocalAlertBanner = staticCompositionLocalOf<StateFlow<AlertBannerState?>?> { null }

/**
 * The visual alert: a chip with the camera icon, what is ahead, the distance (counting down) and the posted limit when
 * known. It appears whether or not the voice is muted, draws nothing when there is no alert, and is announced politely to
 * screen readers when it changes. Unobtrusive: one line, no buttons, no animation. [glove] makes it bigger.
 */
@Composable
fun CameraAlertBanner(modifier: Modifier = Modifier, glove: Boolean = false) {
    val flow = LocalAlertBanner.current ?: return
    val state by flow.collectAsState()
    CameraAlertChip(state, modifier, glove)
}

private val CameraRed = Color(0xFFC5221F) // white text on it: contrast above 5:1
private val WarnBrown = Color(0xFF9A4A00) // same, for incidents

@Composable
fun CameraAlertChip(state: AlertBannerState?, modifier: Modifier = Modifier, glove: Boolean = false) {
    val s = state ?: return
    val context = LocalContext.current
    val locale: Locale = LocalConfiguration.current.locales[0]
    val title = stringResource(titleRes(s.category))
    val distance = RouteFormat.distance(s.distanceMeters.toDouble(), locale)
    val limit = s.limitKmh
    val description = if (limit != null) {
        context.getString(R.string.camalert_description_limit, title, distance, limit)
    } else {
        context.getString(R.string.camalert_description, title, distance)
    }
    val background = if (s.category.isCamera) CameraRed else WarnBrown
    val iconSize = if (glove) 40.dp else 28.dp
    Row(
        modifier
            .testTag("camera_alert")
            .defaultMinSize(minHeight = if (glove) 72.dp else 52.dp)
            .background(background, RoundedCornerShape(if (glove) 20.dp else 16.dp))
            .padding(horizontal = if (glove) 18.dp else 14.dp, vertical = 8.dp)
            .clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        AlertIcon(s.category, Modifier.size(iconSize).testTag("camera_alert_icon"))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.defaultMinSize(minWidth = if (glove) 120.dp else 96.dp)) {
            androidx.compose.foundation.layout.Column {
                BasicText(
                    title,
                    style = Mapas.typography.callout.copy(color = Color.White, fontSize = if (glove) 20.sp else 15.sp),
                    modifier = Modifier.testTag("camera_alert_title"),
                )
                BasicText(
                    distance,
                    style = Mapas.typography.title.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (glove) 32.sp else 22.sp),
                    modifier = Modifier.testTag("camera_alert_distance"),
                )
            }
        }
        if (limit != null) {
            Spacer(Modifier.width(12.dp))
            val sign = if (glove) 56.dp else 40.dp
            Box(
                Modifier
                    .size(sign)
                    .background(Color.White, CircleShape)
                    .border(BorderStroke(if (glove) 5.dp else 4.dp, Color(0xFFD90000)), CircleShape)
                    .testTag("camera_alert_limit"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    limit.toString(),
                    style = Mapas.typography.callout.copy(color = Color.Black, fontWeight = FontWeight.Bold, fontSize = if (glove) 22.sp else 16.sp),
                )
            }
        }
    }
}

private fun titleRes(c: AlertCategory) = when (c) {
    AlertCategory.FIXED_CAMERA -> R.string.camalert_fixed
    AlertCategory.SECTION -> R.string.camalert_section
    AlertCategory.MOBILE_ZONE -> R.string.camalert_zone
    AlertCategory.V16 -> R.string.camalert_v16
    AlertCategory.ACCIDENT -> R.string.camalert_accident
    AlertCategory.CLOSURE -> R.string.camalert_closure
    AlertCategory.CONGESTION -> R.string.camalert_congestion
    AlertCategory.OBSTACLE -> R.string.camalert_obstacle
}

/** A camera for the camera categories (body, lens, viewfinder bump); a warning triangle for the rest. White on the chip. */
@Composable
private fun AlertIcon(category: AlertCategory, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (category.isCamera) {
            val body = Size(w, h * 0.62f)
            val top = h * 0.30f
            drawRoundRect(Color.White, Offset(0f, top), body, CornerRadius(h * 0.10f))
            drawRoundRect(Color.White, Offset(w * 0.25f, top - h * 0.16f), Size(w * 0.30f, h * 0.20f), CornerRadius(h * 0.05f))
            drawCircle(if (category == AlertCategory.MOBILE_ZONE) Color(0xFF9A4A00) else CameraRed, h * 0.19f, Offset(w * 0.5f, top + body.height / 2))
            drawCircle(Color.White, h * 0.10f, Offset(w * 0.5f, top + body.height / 2))
        } else {
            val p = Path().apply {
                moveTo(w / 2, h * 0.06f); lineTo(w * 0.97f, h * 0.92f); lineTo(w * 0.03f, h * 0.92f); close()
            }
            drawPath(p, Color.White)
            drawRect(WarnBrown, Offset(w * 0.46f, h * 0.36f), Size(w * 0.08f, h * 0.28f))
            drawCircle(WarnBrown, h * 0.05f, Offset(w / 2, h * 0.76f))
        }
    }
}
