package com.qtekfun.ultimatemaps.core.traffic

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * What the traffic does to a route's travel time. [adjustedSeconds] replaces the router's duration; [delaySeconds] is the
 * difference (never negative); [worstRatio] is the slowest stretch's speed as a share of free flow; [closedMeters] is how much
 * of the route lies on roads the feed reports closed; [coverage] is the share of the route the feed had data for (0..1).
 */
data class TrafficEstimate(
    val adjustedSeconds: Double,
    val delaySeconds: Double,
    val worstRatio: Double,
    val closedMeters: Double,
    val coverage: Double,
) {
    val hasClosure: Boolean get() = closedMeters > 0.0
    val delayMinutes: Int get() = Math.round(delaySeconds / 60.0).toInt()
}

object RouteTraffic {
    /** A stretch is never slower than this share of free flow in the sum (a standstill would otherwise be infinite). */
    const val MIN_RATIO = 0.1

    /** Closed roads count as this slow in the time: a route that needs one is last, not impossible. */
    const val CLOSED_RATIO = 0.05

    const val STEP_METERS = 200.0

    /** The zoom of the tiles read to estimate a route: about 40 km wide, so a long route needs few. */
    const val TILE_ZOOM = 10

    /**
     * The route's travel time with the traffic of [field]. The router's [coreSeconds] is spread over the route by
     * [baseSpeedMps] (the speed the router assumes at a distance along the route, for example from the speed limits; null: the
     * same everywhere) and each sample of [STEP_METERS] is lengthened by the share of free flow the feed gives there.
     */
    fun estimate(
        geometry: List<LatLon>,
        coreSeconds: Double,
        field: FlowField,
        baseSpeedMps: ((alongMeters: Double) -> Double)? = null,
    ): TrafficEstimate {
        if (geometry.size < 2 || coreSeconds <= 0.0 || field.segmentCount == 0) {
            return TrafficEstimate(coreSeconds, 0.0, 1.0, 0.0, 0.0)
        }
        val cum = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) cum[i] = cum[i - 1] + TileMath.metersBetween(geometry[i - 1], geometry[i])
        val total = cum.last()
        if (total <= 0.0) return TrafficEstimate(coreSeconds, 0.0, 1.0, 0.0, 0.0)
        // sample the route every STEP_METERS: the midpoint of each step, with the local heading
        val steps = maxOf(1, Math.ceil(total / STEP_METERS).toInt())
        val len = total / steps
        val weights = DoubleArray(steps)
        val ratios = DoubleArray(steps) { 1.0 }
        var closed = 0.0
        var covered = 0
        var worst = 1.0
        var seg = 1
        var sumWeights = 0.0
        for (k in 0 until steps) {
            val mid = (k + 0.5) * len
            while (seg < geometry.size - 1 && cum[seg] < mid) seg++
            val a = geometry[seg - 1]
            val b = geometry[seg]
            val t = ((mid - cum[seg - 1]) / (cum[seg] - cum[seg - 1]).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
            val lat = a.lat + (b.lat - a.lat) * t
            val lon = a.lon + (b.lon - a.lon) * t
            val sample = field.sampleAt(lat, lon, heading(a, b))
            val speed = baseSpeedMps?.invoke(mid)?.coerceAtLeast(1.0) ?: 1.0
            weights[k] = len / speed
            sumWeights += weights[k]
            if (sample != null) {
                covered++
                if (sample.closed) {
                    closed += len
                    ratios[k] = CLOSED_RATIO
                } else {
                    ratios[k] = sample.ratio.coerceIn(MIN_RATIO, 1.0)
                }
                worst = minOf(worst, ratios[k])
            }
        }
        var adjusted = 0.0
        for (k in 0 until steps) adjusted += coreSeconds * (weights[k] / sumWeights) / ratios[k]
        val delay = (adjusted - coreSeconds).coerceAtLeast(0.0)
        return TrafficEstimate(coreSeconds + delay, delay, worst, closed, covered.toDouble() / steps)
    }

    /** The part of [geometry] still ahead after [traveledMeters] along it (starting at the exact point); empty past the end. */
    fun remaining(geometry: List<LatLon>, traveledMeters: Double): List<LatLon> {
        if (geometry.size < 2) return emptyList()
        var along = 0.0
        for (i in 1 until geometry.size) {
            val seg = TileMath.metersBetween(geometry[i - 1], geometry[i])
            if (along + seg > traveledMeters) {
                val t = if (seg <= 0.0) 0.0 else ((traveledMeters - along) / seg).coerceIn(0.0, 1.0)
                val a = geometry[i - 1]
                val b = geometry[i]
                return listOf(LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)) + geometry.subList(i, geometry.size)
            }
            along += seg
        }
        return emptyList()
    }

    private fun heading(a: LatLon, b: LatLon): Double {
        val dy = b.lat - a.lat
        val dx = (b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2))
        return if (hypot(dx, dy) < 1e-12) 0.0 else (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }
}
