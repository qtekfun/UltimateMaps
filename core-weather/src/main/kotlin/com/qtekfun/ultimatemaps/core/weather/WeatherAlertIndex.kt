package com.qtekfun.ultimatemaps.core.weather

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * The warnings of the last national bundle, matched on the device against a position or a route. Nothing here knows the
 * network: the position never leaves the phone, the bundle is the same for everyone.
 */
class WeatherAlertIndex(val warnings: List<WeatherWarning>) {
    /**
     * Warnings in effect at [point] (or starting within [lookaheadMillis]) of at least [minLevel], most serious first, then
     * earliest start.
     */
    fun at(point: LatLon, nowMillis: Long, minLevel: AlertLevel = AlertLevel.ORANGE, lookaheadMillis: Long = 0L): List<WeatherWarning> =
        order(warnings.filter { it.level >= minLevel && it.isActive(nowMillis, lookaheadMillis) && it.covers(point.lat, point.lon) })

    /**
     * Warnings that cover any part of [geometry] (a route polyline), same filters as [at]. The line is sampled about every
     * [SAMPLE_METERS] so a polygon crossed between two far apart vertices is not missed.
     */
    fun along(geometry: List<LatLon>, nowMillis: Long, minLevel: AlertLevel = AlertLevel.ORANGE, lookaheadMillis: Long = 0L): List<WeatherWarning> {
        if (geometry.isEmpty()) return emptyList()
        val candidates = warnings.filter { it.level >= minLevel && it.isActive(nowMillis, lookaheadMillis) && it.polygons.isNotEmpty() }
        if (candidates.isEmpty()) return emptyList()
        val s = geometry.minOf { it.lat }
        val n = geometry.maxOf { it.lat }
        val w = geometry.minOf { it.lon }
        val e = geometry.maxOf { it.lon }
        val near = candidates.filter { c -> c.polygons.any { it.intersectsBox(s, w, n, e) } }
        if (near.isEmpty()) return emptyList()
        val hit = LinkedHashSet<WeatherWarning>()
        sample(geometry) { lat, lon ->
            for (c in near) if (c !in hit && c.covers(lat, lon)) hit += c
            hit.size < near.size
        }
        return order(hit.toList())
    }

    /** Every listed warning of at least [minLevel] still in effect or upcoming, whatever the place (the detail list). */
    fun all(nowMillis: Long, minLevel: AlertLevel): List<WeatherWarning> =
        order(warnings.filter { it.level >= minLevel && it.isActive(nowMillis, Long.MAX_VALUE / 4) })

    private fun order(list: List<WeatherWarning>): List<WeatherWarning> =
        list.sortedWith(compareByDescending<WeatherWarning> { it.level }.thenBy { it.onsetMillis ?: Long.MIN_VALUE })

    private inline fun sample(geometry: List<LatLon>, visit: (Double, Double) -> Boolean) {
        if (!visit(geometry[0].lat, geometry[0].lon)) return
        for (i in 1 until geometry.size) {
            val a = geometry[i - 1]
            val b = geometry[i]
            val metersLat = abs(b.lat - a.lat) * METERS_PER_DEGREE
            val metersLon = abs(b.lon - a.lon) * METERS_PER_DEGREE * cos(Math.toRadians((a.lat + b.lat) / 2))
            val steps = max(1, (hypot(metersLat, metersLon) / SAMPLE_METERS).toInt() + 1).coerceAtMost(MAX_STEPS_PER_SEGMENT)
            for (k in 1..steps) {
                val t = k.toDouble() / steps
                if (!visit(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)) return
            }
        }
    }

    companion object {
        const val SAMPLE_METERS = 1_000.0
        private const val METERS_PER_DEGREE = 111_320.0
        private const val MAX_STEPS_PER_SEGMENT = 2_000
        val EMPTY = WeatherAlertIndex(emptyList())
    }
}
