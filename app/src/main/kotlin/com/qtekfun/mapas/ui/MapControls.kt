package com.qtekfun.mapas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.ui.theme.Mapas

/** Round map button with a drawn icon. Minimum touch target comes from the theme (56 dp in glove mode). */
@Composable
fun MapButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "",
    icon: DrawScope.(tint: Color) -> Unit,
) {
    val tint = Mapas.colors.accent
    Box(
        modifier = modifier
            .size(Mapas.dimens.touchTarget)
            .clip(Mapas.shapes.control)
            .background(Mapas.colors.control)
            .border(0.5.dp, Mapas.colors.separator, Mapas.shapes.control)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) { icon(tint) }
    }
}

/** Arrow used by the "my location" button. */
fun DrawScope.drawLocateIcon(tint: Color, filled: Boolean) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.92f, h * 0.08f)
        lineTo(w * 0.60f, h * 0.92f)
        lineTo(w * 0.47f, h * 0.53f)
        lineTo(w * 0.08f, h * 0.40f)
        close()
    }
    if (filled) drawPath(path, tint) else drawPath(path, tint, style = Stroke(width = 2.2f * density))
}

/** Compass needle (north red). Rotated by the caller so it points to the map's north. */
fun DrawScope.drawCompassIcon(tint: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val north = Path().apply {
        moveTo(c.x, c.y - size.height * 0.46f)
        lineTo(c.x + size.width * 0.17f, c.y)
        lineTo(c.x - size.width * 0.17f, c.y)
        close()
    }
    val south = Path().apply {
        moveTo(c.x, c.y + size.height * 0.46f)
        lineTo(c.x + size.width * 0.17f, c.y)
        lineTo(c.x - size.width * 0.17f, c.y)
        close()
    }
    drawPath(north, Color(0xFFFF3B30))
    drawPath(south, tint.copy(alpha = 0.55f))
}

/** How far the heading is from north, 0 to 180 degrees: 359.9 (how the map engine reports it) is 0.1 away, not 359.9. */
fun degreesFromNorth(bearingDegrees: Float): Float {
    val b = ((bearingDegrees % 360f) + 360f) % 360f
    return if (b > 180f) 360f - b else b
}

/** The compass shows when the map is rotated or tilted: tapping it brings the map back to north-up and flat. */
fun compassVisible(bearingDegrees: Float, tiltDegrees: Float): Boolean =
    degreesFromNorth(bearingDegrees) > COMPASS_THRESHOLD || tiltDegrees > COMPASS_THRESHOLD

private const val COMPASS_THRESHOLD = 0.5f

/**
 * Column of map buttons (top-right, like Apple Maps): my location, then the compass (only when the map is rotated or
 * tilted), then Settings (when [onSettings] is given). Every button has the same chip style and touch target.
 */
@Composable
fun MapButtons(
    bearingDegrees: Float,
    locating: Boolean,
    locateDescription: String,
    compassDescription: String,
    onLocate: () -> Unit,
    onResetNorth: () -> Unit,
    modifier: Modifier = Modifier,
    tiltDegrees: Float = 0f,
    settingsDescription: String = "",
    onSettings: (() -> Unit)? = null,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        MapButton(locateDescription, onLocate, tag = "btn_locate") { tint -> drawLocateIcon(tint, filled = locating) }
        if (compassVisible(bearingDegrees, tiltDegrees)) {
            Spacer(Modifier.height(BUTTON_GAP))
            MapButton(compassDescription, onResetNorth, tag = "btn_compass") { tint ->
                // Rotation is applied through the Canvas transform below to keep the icon crisp.
                rotate(-bearingDegrees) { drawCompassIcon(tint) }
            }
        }
        if (onSettings != null) {
            Spacer(Modifier.height(BUTTON_GAP))
            SettingsGear(settingsDescription, onSettings)
        }
    }
}

private val BUTTON_GAP = 10.dp

/**
 * OpenStreetMap attribution (RF-13): always drawn over the map, never hidden by the sheet.
 * Tapping it opens the licence notice ([onClick]).
 */
@Composable
fun AttributionLabel(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        style = Mapas.typography.caption.copy(color = Mapas.colors.label),
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Mapas.colors.control)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("attribution"),
    )
}

/** Gear icon of the Settings button, drawn with the same 22 dp canvas as the other map buttons. */
fun DrawScope.drawGearIcon(tint: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.minDimension * 0.30f
    drawCircle(tint, radius = r, center = c, style = Stroke(width = 2.2f * density))
    for (i in 0 until 8) {
        rotate(i * 45f, pivot = c) {
            drawLine(tint, Offset(c.x, c.y - r), Offset(c.x, c.y - size.minDimension * 0.46f), strokeWidth = 3.2f * density)
        }
    }
}

@Composable
fun SettingsGear(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Same chip as the other map buttons (background, border, accent icon) so it stays visible over any map colour.
    val tint = Mapas.colors.accent
    Box(
        modifier = modifier
            .size(Mapas.dimens.touchTarget)
            .clip(Mapas.shapes.control)
            .background(Mapas.colors.control)
            .border(0.5.dp, Mapas.colors.separator, Mapas.shapes.control)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("btn_settings"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) { drawGearIcon(tint) }
    }
}
