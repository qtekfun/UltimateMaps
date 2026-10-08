package com.qtekfun.ultimatemaps.core.map

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Pure camera fit: the flat, north-up camera (centre and zoom) that shows every point of a route inside the part of the
 * screen that is not covered by something ([CameraPadding]: bottom sheet, top bar, button column, status bar).
 * Web-Mercator maths with 512 px tiles (what MapLibre uses). No Android types, so it is tested on the JVM.
 */
object RouteCameraFit {
    /** Never zoom in past this for a very short (or single point) route: the map would show only a street corner. */
    const val MAX_ZOOM = 17.0
    const val MIN_ZOOM = 0.0

    /** At least this share of the screen height stays free for the route, whatever the padding says. */
    const val MIN_FREE_FRACTION = 0.25

    private const val TILE = 512.0
    private const val MAX_LAT = 85.0511

    /**
     * Camera that frames [points] on a [widthPx] x [heightPx] screen with [padding] covered. Null when there is nothing
     * to frame or the screen has no size yet. A single point (or identical points) gets [maxZoom]. Padding that would
     * leave less than [MIN_FREE_FRACTION] of the height (or width) free is reduced proportionally.
     */
    fun fit(
        points: List<LatLon>,
        widthPx: Int,
        heightPx: Int,
        padding: CameraPadding,
        maxZoom: Double = MAX_ZOOM,
        minZoom: Double = MIN_ZOOM,
    ): CameraState? {
        if (points.isEmpty() || widthPx <= 0 || heightPx <= 0) return null
        val p = clampPadding(padding, widthPx, heightPx)
        val freeW = (widthPx - p.left - p.right).toDouble()
        val freeH = (heightPx - p.top - p.bottom).toDouble()

        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (pt in points) {
            val x = (pt.lon + 180.0) / 360.0
            val y = mercatorY(pt.lat)
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
        }
        val dx = maxX - minX
        val dy = maxY - minY
        val zx = if (dx <= 0.0) Double.POSITIVE_INFINITY else log2(freeW / (TILE * dx))
        val zy = if (dy <= 0.0) Double.POSITIVE_INFINITY else log2(freeH / (TILE * dy))
        val zoom = min(zx, zy).coerceIn(minZoom, maxZoom)

        // The box centre must land on the centre of the free rectangle, which is off the screen centre by this much.
        val scale = TILE * 2.0.pow(zoom)
        val offX = (p.left - p.right) / 2.0
        val offY = (p.top - p.bottom) / 2.0
        val cx = (minX + maxX) / 2.0 - offX / scale
        val cy = (minY + maxY) / 2.0 - offY / scale
        val lon = (cx * 360.0 - 180.0).coerceIn(-180.0, 180.0)
        val lat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * cy.coerceIn(0.0, 1.0))))).coerceIn(-MAX_LAT, MAX_LAT)
        return CameraState(LatLon(lat, lon), zoom)
    }

    /** Keeps [MIN_FREE_FRACTION] of each dimension free by shrinking the two paddings of that axis together. */
    fun clampPadding(padding: CameraPadding, widthPx: Int, heightPx: Int): CameraPadding {
        fun axis(a: Int, b: Int, total: Int): Pair<Int, Int> {
            val allowed = (total * (1.0 - MIN_FREE_FRACTION)).toInt()
            val sum = a + b
            if (sum <= allowed || sum == 0) return a to b
            val k = allowed.toDouble() / sum
            return (a * k).toInt() to (b * k).toInt()
        }
        val (l, r) = axis(padding.left, padding.right, widthPx)
        val (t, b) = axis(padding.top, padding.bottom, heightPx)
        return CameraPadding(l, t, r, b)
    }

    private fun mercatorY(lat: Double): Double {
        val c = lat.coerceIn(-MAX_LAT, MAX_LAT)
        return 0.5 - ln(tan(PI / 4.0 + Math.toRadians(c) / 2.0)) / (2.0 * PI)
    }

    private fun log2(v: Double) = ln(v) / ln(2.0)
}
