package com.qtekfun.ultimatemaps.core.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.map.SimulatedLocationSource
import java.util.Random
import kotlin.math.cos

private const val METERS_PER_DEGREE = 111_194.9266

/**
 * Generates the fixes of someone walking (or driving) a route at a constant [speedMps], for tests and for the
 * app's route simulation. Reproducible: the same [seed] gives the same noise. [noiseMeters] is the standard
 * deviation of the Gaussian error per axis; the reported accuracy is [accuracyMeters] (default: the noise,
 * at least 5 m). [gaps] are time ranges, in milliseconds since the start, with no fix (a tunnel).
 */
class RouteSimulator(
    points: List<LatLon>,
    val speedMps: Double,
    val intervalMillis: Long = 1_000L,
    val noiseMeters: Double = 0.0,
    val seed: Long = 1L,
    val gaps: List<LongRange> = emptyList(),
    val startMillis: Long = 1_000L,
    val startAlongMeters: Double = 0.0,
    val accuracyMeters: Float = noiseMeters.toFloat().coerceAtLeast(5f),
) {
    private val geometry = RouteGeometry(points)

    init {
        require(speedMps > 0 && intervalMillis > 0) { "speed and interval must be positive" }
    }

    /** Total time to walk the rest of the route, in milliseconds. */
    val durationMillis: Long get() = ((geometry.totalMeters - startAlongMeters) / speedMps * 1000).toLong()

    /** Fixes from the start to the end of the route, in time order (a fresh, identical sequence each call). */
    fun fixes(): Sequence<LocationFix> = sequence {
        val random = Random(seed)
        var elapsed = 0L
        while (true) {
            val along = (startAlongMeters + speedMps * elapsed / 1000.0).coerceAtMost(geometry.totalMeters)
            val inGap = gaps.any { elapsed in it }
            // Draw the noise even inside a gap so a gap does not change the noise of later fixes.
            val ex = random.nextGaussian() * noiseMeters
            val ey = random.nextGaussian() * noiseMeters
            if (!inGap) yield(fixAt(elapsed, along, ex, ey))
            if (along >= geometry.totalMeters) break
            elapsed += intervalMillis
        }
    }

    private fun fixAt(elapsed: Long, along: Double, east: Double, north: Double): LocationFix {
        val p = geometry.pointAt(along)
        val lat = p.lat + north / METERS_PER_DEGREE
        val lon = p.lon + east / (METERS_PER_DEGREE * cos(Math.toRadians(p.lat)))
        return LocationFix(
            point = LatLon(lat.coerceIn(-90.0, 90.0), lon.coerceIn(-180.0, 180.0)),
            accuracyMeters = accuracyMeters,
            bearingDegrees = geometry.bearingAt(along),
            speedMps = speedMps.toFloat(),
            timeMillis = startMillis + elapsed,
        )
    }

    /** Pushes every fix into [source]; [beforeEach] runs before each one (e.g. to advance a test clock). */
    fun emitAll(source: SimulatedLocationSource, beforeEach: (LocationFix) -> Unit = {}) {
        for (fix in fixes()) {
            beforeEach(fix)
            source.emit(fix)
        }
    }
}
