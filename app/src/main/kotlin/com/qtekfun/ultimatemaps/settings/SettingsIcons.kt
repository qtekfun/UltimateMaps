package com.qtekfun.ultimatemaps.settings

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.qtekfun.ultimatemaps.ui.drawLocateIcon

/*
 * The category icons of the Settings hub, drawn with Canvas on a square area (no image resources, no icon library).
 * Each one is a simple outline in the given tint so it follows the theme.
 */

private fun DrawScope.line() = Stroke(width = 2.2f * density, cap = StrokeCap.Round)

/** Navigation and voice: the "my location" arrow. */
fun DrawScope.drawNavigationIcon(tint: Color) = drawLocateIcon(tint, filled = true)

/** Alerts: a warning triangle with an exclamation mark. */
fun DrawScope.drawAlertsIcon(tint: Color) {
    val w = size.width
    val h = size.height
    val tri = Path().apply {
        moveTo(w * 0.5f, h * 0.08f)
        lineTo(w * 0.95f, h * 0.88f)
        lineTo(w * 0.05f, h * 0.88f)
        close()
    }
    drawPath(tri, tint, style = line())
    drawLine(tint, Offset(w * 0.5f, h * 0.38f), Offset(w * 0.5f, h * 0.62f), strokeWidth = 2.6f * density, cap = StrokeCap.Round)
    drawCircle(tint, radius = 1.5f * density, center = Offset(w * 0.5f, h * 0.76f))
}

/** Fuel stations: a pump (body, display and hose). */
fun DrawScope.drawFuelIcon(tint: Color) {
    val w = size.width
    val h = size.height
    drawRoundRect(tint, Offset(w * 0.12f, h * 0.1f), Size(w * 0.5f, h * 0.8f), CornerRadius(w * 0.06f), style = line())
    drawRect(tint, Offset(w * 0.22f, h * 0.2f), Size(w * 0.3f, h * 0.2f), style = line())
    drawLine(tint, Offset(w * 0.62f, h * 0.35f), Offset(w * 0.8f, h * 0.35f), strokeWidth = 2.2f * density, cap = StrokeCap.Round)
    drawLine(tint, Offset(w * 0.8f, h * 0.35f), Offset(w * 0.8f, h * 0.7f), strokeWidth = 2.2f * density, cap = StrokeCap.Round)
    drawLine(tint, Offset(w * 0.06f, h * 0.9f), Offset(w * 0.68f, h * 0.9f), strokeWidth = 2.2f * density, cap = StrokeCap.Round)
}

/** Maps and network: a globe (circle, meridian and equator). */
fun DrawScope.drawNetworkIcon(tint: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.minDimension * 0.42f
    drawCircle(tint, radius = r, center = c, style = line())
    drawOval(tint, Offset(c.x - r * 0.45f, c.y - r), Size(r * 0.9f, r * 2), style = line())
    drawLine(tint, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 2.2f * density)
}

/** Data: a stack of three discs (a database). */
fun DrawScope.drawDataIcon(tint: Color) {
    val w = size.width
    val h = size.height
    for (i in 0 until 3) {
        val y = h * (0.12f + 0.28f * i)
        drawRoundRect(tint, Offset(w * 0.1f, y), Size(w * 0.8f, h * 0.22f), CornerRadius(h * 0.11f), style = line())
    }
}

/** About: a circle with an "i". */
fun DrawScope.drawAboutIcon(tint: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    drawCircle(tint, radius = size.minDimension * 0.42f, center = c, style = line())
    drawLine(tint, Offset(c.x, c.y - size.height * 0.02f), Offset(c.x, c.y + size.height * 0.22f), strokeWidth = 2.6f * density, cap = StrokeCap.Round)
    drawCircle(tint, radius = 1.6f * density, center = Offset(c.x, c.y - size.height * 0.2f))
}
