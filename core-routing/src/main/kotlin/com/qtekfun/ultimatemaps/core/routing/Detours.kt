package com.qtekfun.ultimatemaps.core.routing

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Helpers to look for genuinely different routes: a via point to the left or right of the middle of the main route, and a
 * measure of how much two routes share. Pure geometry; the router decides whether the detour is any good.
 */
object Detours {
    enum class Side { LEFT, RIGHT }

    private const val METERS_PER_DEGREE = 111_320.0

    /** The offset from the route is this share of its length, within [MIN_OFFSET_METERS]..[MAX_OFFSET_METERS]. */
    const val OFFSET_SHARE = 0.2
    const val MIN_OFFSET_METERS = 1_000.0
    const val MAX_OFFSET_METERS = 20_000.0

    /**
     * A point on [side] of the main [route] (looking in the travel direction), [OFFSET_SHARE] of its length away from the
     * middle of the route, perpendicular to it there; null for a route too short or degenerate to have a useful detour.
     */
    fun viaPoint(route: List<LatLon>, side: Side): LatLon? {
        if (route.size < 2) return null
        val cum = DoubleArray(route.size)
        for (i in 1 until route.size) cum[i] = cum[i - 1] + route[i - 1].distanceTo(route[i])
        val total = cum.last()
        if (total < 3_000.0) return null
        val mid = pointAt(route, cum, total / 2)
        // direction of travel around the middle: from 45 % to 55 % of the route
        val a = pointAt(route, cum, total * 0.45)
        val b = pointAt(route, cum, total * 0.55)
        val kx = METERS_PER_DEGREE * cos(Math.toRadians(mid.lat))
        val dx = (b.lon - a.lon) * kx
        val dy = (b.lat - a.lat) * METERS_PER_DEGREE
        val len = hypot(dx, dy)
        if (len < 1.0) return null
        // left of the direction (dx, dy) is (-dy, dx)
        val sign = if (side == Side.LEFT) 1.0 else -1.0
        val offset = (total * OFFSET_SHARE).coerceIn(MIN_OFFSET_METERS, MAX_OFFSET_METERS)
        val px = -dy / len * sign * offset
        val py = dx / len * sign * offset
        return LatLon.ofOrNull(mid.lat + py / METERS_PER_DEGREE, mid.lon + px / kx)
    }

    private fun pointAt(route: List<LatLon>, cum: DoubleArray, d: Double): LatLon {
        var i = 1
        while (i < route.size - 1 && cum[i] < d) i++
        val seg = max(cum[i] - cum[i - 1], 1e-6)
        val t = ((d - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
        val p = route[i - 1]
        val q = route[i]
        return LatLon(p.lat + t * (q.lat - p.lat), p.lon + t * (q.lon - p.lon))
    }

    /**
     * Which part of [other] runs along [route], 0..1: the share of its points (roughly every 100 m is enough, since the
     * geometry is dense) that have a point of [route] in the same or a neighbouring cell of about 100 m.
     */
    fun sharedFraction(route: List<LatLon>, other: List<LatLon>): Double {
        if (other.isEmpty() || route.isEmpty()) return 0.0
        val cells = HashSet<Long>(route.size * 2)
        for (p in route) cells.add(cell(p.lat, p.lon))
        var shared = 0
        for (p in other) {
            val cy = Math.floor(p.lat / CELL_DEGREES).toLong()
            val cx = Math.floor(p.lon / CELL_DEGREES).toLong()
            var hit = false
            loop@ for (dy in -1..1) for (dx in -1..1) {
                if (cells.contains(key(cy + dy, cx + dx))) {
                    hit = true
                    break@loop
                }
            }
            if (hit) shared++
        }
        return shared.toDouble() / other.size
    }

    private const val CELL_DEGREES = 0.001

    private fun cell(lat: Double, lon: Double) = key(Math.floor(lat / CELL_DEGREES).toLong(), Math.floor(lon / CELL_DEGREES).toLong())

    private fun key(cy: Long, cx: Long) = (cy shl 32) xor (cx and 0xFFFFFFFFL)
}
