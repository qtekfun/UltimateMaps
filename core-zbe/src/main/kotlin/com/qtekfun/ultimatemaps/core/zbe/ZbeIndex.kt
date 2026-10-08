package com.qtekfun.ultimatemaps.core.zbe

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * A stretch of a route inside a zone, in metres along the route (the same measure as `NavState.traveledMeters`: haversine
 * between the route points). [entryMeters] is 0 and [startsInside] true when the route begins inside the zone;
 * [endsInside] when it ends inside (the destination is in the zone).
 */
class ZbeCrossing(
    val zone: ZbeZone,
    /** Where the route enters the zone (its first point when it starts inside). */
    val entry: LatLon,
    val entryMeters: Double,
    val exitMeters: Double,
    val startsInside: Boolean,
    val endsInside: Boolean,
)

/**
 * Point and route queries over the zones (pure, no Android). The route test cuts every route segment at the polygon edges
 * and tests the middle of each piece, so a route that only touches a corner or a vertex, or that runs along the boundary
 * without going in, is not a crossing. Two guards keep the boundary-hugging roads (zone borders usually follow a street)
 * from raising false warnings: a stretch must reach at least [MIN_DEPTH_METERS] inside, and two stretches closer than
 * [MERGE_GAP_METERS] along the route are one. Both numbers are design choices, not measured.
 */
class ZbeIndex(val zones: List<ZbeZone>) {

    /** Zones that contain the point (they may overlap, e.g. a city zone and a special-protection core). */
    fun zonesAt(lat: Double, lon: Double): List<ZbeZone> = zones.filter { it.contains(lat, lon) }

    /** The crossings of [route] with every zone, ordered by where they start along the route. */
    fun crossings(route: List<LatLon>): List<ZbeCrossing> {
        if (zones.isEmpty() || route.size < 2) return emptyList()
        var s = Double.MAX_VALUE; var w = Double.MAX_VALUE; var n = -Double.MAX_VALUE; var e = -Double.MAX_VALUE
        for (p in route) {
            s = minOf(s, p.lat); n = maxOf(n, p.lat); w = minOf(w, p.lon); e = maxOf(e, p.lon)
        }
        val routeBox = ZbeBox(s, w, n, e)
        val candidates = zones.filter { it.box.intersects(routeBox) }
        if (candidates.isEmpty()) return emptyList()
        val cum = DoubleArray(route.size)
        for (i in 1 until route.size) cum[i] = cum[i - 1] + route[i - 1].distanceTo(route[i])
        val total = cum[route.size - 1]
        return candidates.flatMap { crossingsOf(it, route, cum, total) }.sortedBy { it.entryMeters }
    }

    private fun crossingsOf(zone: ZbeZone, route: List<LatLon>, cum: DoubleArray, total: Double): List<ZbeCrossing> {
        val k = cos(Math.toRadians((zone.box.south + zone.box.north) / 2)).coerceAtLeast(0.01)
        val out = ArrayList<ZbeCrossing>(1)
        var runStart = -1.0
        var runStartPoint = route[0]
        var runEnd = -1.0
        var runDepth = 0.0

        fun close() {
            if (runStart >= 0 && runDepth >= MIN_DEPTH_METERS) {
                out += ZbeCrossing(zone, runStartPoint, runStart, runEnd, startsInside = runStart <= EDGE_METERS, endsInside = runEnd >= total - EDGE_METERS)
            }
            runStart = -1.0
        }

        val ts = ArrayList<Double>(8)
        for (i in 0 until route.size - 1) {
            val a = route[i]; val b = route[i + 1]
            if (!zone.box.intersects(a.lat, a.lon, b.lat, b.lon)) continue
            ts.clear()
            ts.add(0.0)
            for (poly in zone.polygons) {
                if (!poly.box.intersects(a.lat, a.lon, b.lat, b.lon)) continue
                for (ring in poly.rings) collectCuts(ring, a, b, k, ts)
            }
            ts.add(1.0)
            ts.sort()
            val segLen = cum[i + 1] - cum[i]
            for (j in 0 until ts.size - 1) {
                val t0 = ts[j]; val t1 = ts[j + 1]
                if (t1 - t0 < MIN_PIECE) continue
                val tm = (t0 + t1) / 2
                val mLat = a.lat + (b.lat - a.lat) * tm
                val mLon = a.lon + (b.lon - a.lon) * tm
                if (!zone.contains(mLat, mLon)) continue
                val from = cum[i] + t0 * segLen
                val to = cum[i] + t1 * segLen
                if (runStart >= 0 && from - runEnd <= MERGE_GAP_METERS) {
                    runEnd = to
                } else {
                    close()
                    runStart = from; runEnd = to; runDepth = 0.0
                    runStartPoint = LatLon(a.lat + (b.lat - a.lat) * t0, a.lon + (b.lon - a.lon) * t0)
                }
                runDepth = maxOf(runDepth, depthMeters(zone, mLat, mLon, k))
            }
        }
        close()
        return out
    }

    /** Appends the parameters `t` in (0, 1) where segment a-b crosses an edge of [ring]. */
    private fun collectCuts(ring: ZbeRing, a: LatLon, b: LatLon, k: Double, ts: MutableList<Double>) {
        val ax = a.lon * k; val ay = a.lat
        val rx = (b.lon - a.lon) * k; val ry = b.lat - a.lat
        var j = ring.size - 1
        for (i in 0 until ring.size) {
            val cx = ring.lon(j) * k; val cy = ring.lat(j)
            val sx = ring.lon(i) * k - cx; val sy = ring.lat(i) - cy
            j = i
            val denom = rx * sy - ry * sx
            if (abs(denom) < 1e-18) continue // parallel: no single cut point
            val qx = cx - ax; val qy = cy - ay
            val t = (qx * sy - qy * sx) / denom
            val u = (qx * ry - qy * rx) / denom
            if (t > 0.0 && t < 1.0 && u >= 0.0 && u <= 1.0) ts.add(t)
        }
    }

    /** Distance in metres from an inside point to the nearest edge of the zone. */
    private fun depthMeters(zone: ZbeZone, lat: Double, lon: Double, k: Double): Double {
        var best = Double.MAX_VALUE
        val px = lon * k * METERS_PER_DEGREE; val py = lat * METERS_PER_DEGREE
        for (poly in zone.polygons) for (ring in poly.rings) {
            var j = ring.size - 1
            for (i in 0 until ring.size) {
                val ax = ring.lon(j) * k * METERS_PER_DEGREE; val ay = ring.lat(j) * METERS_PER_DEGREE
                val bx = ring.lon(i) * k * METERS_PER_DEGREE; val by = ring.lat(i) * METERS_PER_DEGREE
                j = i
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                best = minOf(best, hypot(px - (ax + t * dx), py - (ay + t * dy)))
            }
        }
        return best
    }

    companion object {
        /** A stretch must go at least this far in (metres from the nearest border) to count; border roads do not. */
        const val MIN_DEPTH_METERS = 15.0

        /** Stretches closer than this along the route are one crossing (the route brushes the border and comes back). */
        const val MERGE_GAP_METERS = 30.0

        private const val EDGE_METERS = 1.0
        private const val MIN_PIECE = 1e-9
        private const val METERS_PER_DEGREE = 111_195.0
    }
}
