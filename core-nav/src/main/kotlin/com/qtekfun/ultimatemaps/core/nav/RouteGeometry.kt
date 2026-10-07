package com.qtekfun.ultimatemaps.core.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

private const val METERS_PER_DEGREE = 111_194.9266 // 2*pi*R/360 with the radius used by distanceTo

/** Smallest angle between two bearings, in `[0, 180]`. */
internal fun angleDiff(a: Double, b: Double): Double = abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)

/** Result of [RouteGeometry.search]; owned by the caller and reused so the hot loop does not allocate. */
class RouteMatch {
    var segment = 0
    var along = 0.0
    var distance = Double.MAX_VALUE
    /** Bearing of the matched segment, or NaN for a degenerate one. */
    var segmentBearing = Double.NaN
}

/**
 * Immutable, thread-safe measured polyline. Distances along the route use haversine; the perpendicular
 * distance to a segment uses a local flat projection (error below 0.1 % for the tens of metres that matter).
 */
class RouteGeometry(points: List<LatLon>) {
    val size: Int = points.size
    private val lat = DoubleArray(size) { points[it].lat }
    private val lon = DoubleArray(size) { points[it].lon }
    private val cum = DoubleArray(size)
    private val segCos = DoubleArray(size - 1)
    private val segX = DoubleArray(size - 1)
    private val segY = DoubleArray(size - 1)
    private val segLen = DoubleArray(size - 1)
    private val segBearing = DoubleArray(size - 1)

    val totalMeters: Double

    init {
        require(size >= 2) { "a route needs at least two points" }
        for (i in 0 until size - 1) {
            cum[i + 1] = cum[i] + points[i].distanceTo(points[i + 1])
            val c = cos(Math.toRadians((lat[i] + lat[i + 1]) / 2))
            segCos[i] = c
            segX[i] = (lon[i + 1] - lon[i]) * c * METERS_PER_DEGREE
            segY[i] = (lat[i + 1] - lat[i]) * METERS_PER_DEGREE
            segLen[i] = hypot(segX[i], segY[i])
            segBearing[i] = if (segLen[i] < 0.01) Double.NaN else (Math.toDegrees(atan2(segX[i], segY[i])) + 360.0) % 360.0
        }
        totalMeters = cum[size - 1]
    }

    fun alongOfIndex(index: Int): Double = cum[index.coerceIn(0, size - 1)]

    /** Index of the segment `[i, i+1]` containing [along] (binary search, no allocation). */
    fun segmentAt(along: Double): Int {
        var lo = 0
        var hi = size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cum[mid] <= along) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun pointAt(along: Double): LatLon {
        val a = along.coerceIn(0.0, totalMeters)
        val s = segmentAt(a)
        val t = if (cum[s + 1] > cum[s]) (a - cum[s]) / (cum[s + 1] - cum[s]) else 0.0
        return LatLon(lat[s] + (lat[s + 1] - lat[s]) * t, lon[s] + (lon[s + 1] - lon[s]) * t)
    }

    /** Route heading at [along]; looks ahead past zero-length segments. */
    fun bearingAt(along: Double): Float {
        var s = segmentAt(along.coerceIn(0.0, totalMeters))
        while (s < size - 2 && segBearing[s].isNaN()) s++
        while (s > 0 && segBearing[s].isNaN()) s--
        return if (segBearing[s].isNaN()) 0f else segBearing[s].toFloat()
    }

    /**
     * Best segment for the fix `(pLat, pLon)` among those overlapping the along-route window
     * `[fromAlong, toAlong]`. The score is the distance plus up to [headingWeight] metres when [heading]
     * (NaN = unknown) disagrees with the segment direction; that separates overlapping or opposite legs.
     * Ties go to the lowest along-route position. Writes the winner (with its true distance) into [out].
     */
    fun search(
        pLat: Double, pLon: Double, fromAlong: Double, toAlong: Double,
        heading: Double, headingWeight: Double, out: RouteMatch,
    ) {
        var best = Double.MAX_VALUE
        val first = segmentAt(fromAlong.coerceIn(0.0, totalMeters))
        var s = first
        val last = size - 2
        while (s <= last) {
            if (s > first && cum[s] > toAlong) break
            val dx = (pLon - lon[s]) * segCos[s] * METERS_PER_DEGREE
            val dy = (pLat - lat[s]) * METERS_PER_DEGREE
            val len2 = segX[s] * segX[s] + segY[s] * segY[s]
            val t = if (len2 < 1e-6) 0.0 else ((dx * segX[s] + dy * segY[s]) / len2).coerceIn(0.0, 1.0)
            val ex = dx - t * segX[s]
            val ey = dy - t * segY[s]
            val dist = sqrt(ex * ex + ey * ey)
            var score = dist
            val sb = segBearing[s]
            if (!heading.isNaN() && !sb.isNaN()) score += headingWeight * angleDiff(heading, sb) / 180.0
            if (score < best) {
                best = score
                out.segment = s
                out.along = cum[s] + t * (cum[s + 1] - cum[s])
                out.distance = dist
                out.segmentBearing = sb
            }
            s++
        }
    }
}
