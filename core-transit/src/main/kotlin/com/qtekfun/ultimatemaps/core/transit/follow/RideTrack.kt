package com.qtekfun.ultimatemaps.core.transit.follow

import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The line of one ride in a local flat metric plane, prepared once so that matching a fix allocates nothing: the vertices
 * (the leg's geometry, or the stops joined by chords when the index has none), the distance along the track at each vertex,
 * the vertex of each stop and, per hop (stop to next stop), whether it follows the real line.
 *
 * Progress is expressed as `hop + fraction` like everywhere else in the follower; [alongOf] / [progressAt] convert to and
 * from metres along the track, which is what distances and directions are decided on.
 */
internal class RideTrack(ride: ItineraryLeg.Ride) {
    val stopCount: Int = ride.stops.size
    private val kx: Double
    private val ky = METERS_PER_DEGREE
    private val originLat: Double
    private val originLon: Double

    /** Vertices in metres east / north of the first stop. */
    val vx: DoubleArray
    val vy: DoubleArray

    /** Metres along the track at each vertex. */
    val cum: DoubleArray

    /** Vertex index of each stop (non-decreasing). */
    val stopVertex: IntArray

    /** Metres along the track at each stop. */
    val stopAlong: DoubleArray

    /** Hop that vertex edge `v -> v + 1` belongs to. */
    private val edgeHop: IntArray

    /** True for hops that follow the line's real geometry (not the chord). */
    val hopOnShape: BooleanArray

    /** True when the ride has real geometry for at least one hop. */
    val hasShape: Boolean

    init {
        val g = ride.geometry?.takeIf { it.stopVertex.size == ride.stops.size }
        val pts = g?.points ?: ride.stops.map { it.point }
        originLat = ride.stops[0].point.lat
        originLon = ride.stops[0].point.lon
        kx = METERS_PER_DEGREE * cos(Math.toRadians(originLat))
        val n = pts.size
        vx = DoubleArray(n) { (pts[it].lon - originLon) * kx }
        vy = DoubleArray(n) { (pts[it].lat - originLat) * ky }
        cum = DoubleArray(n)
        for (i in 1 until n) {
            val dx = vx[i] - vx[i - 1]
            val dy = vy[i] - vy[i - 1]
            cum[i] = cum[i - 1] + sqrt(dx * dx + dy * dy)
        }
        stopVertex = g?.stopVertex?.copyOf() ?: IntArray(stopCount) { it }
        stopAlong = DoubleArray(stopCount) { cum[stopVertex[it]] }
        hopOnShape = g?.onShape?.copyOf() ?: BooleanArray(maxOf(0, stopCount - 1))
        hasShape = hopOnShape.any { it }
        edgeHop = IntArray(maxOf(0, n - 1))
        var hop = 0
        for (e in edgeHop.indices) {
            while (hop < stopCount - 2 && e >= stopVertex[hop + 1]) hop++
            edgeHop[e] = hop
        }
    }

    val edgeCount: Int get() = edgeHop.size

    fun edgeHopOf(e: Int): Int = edgeHop[e]

    fun xOf(lon: Double): Double = (lon - originLon) * kx
    fun yOf(lat: Double): Double = (lat - originLat) * ky

    /** Metres along the track at progress [p] (stop index plus fraction of the hop). */
    fun alongOf(p: Double): Double {
        if (stopCount < 2) return 0.0
        if (p >= stopCount - 1) return stopAlong[stopCount - 1]
        if (p <= 0.0) return 0.0
        val i = floor(p).toInt().coerceIn(0, stopCount - 2)
        return stopAlong[i] + (p - i) * (stopAlong[i + 1] - stopAlong[i])
    }

    /** Progress at [along] metres: the hop it falls in plus the fraction of that hop's length. */
    fun progressAt(along: Double): Double {
        if (stopCount < 2) return 0.0
        if (along <= 0.0) return 0.0
        if (along >= stopAlong[stopCount - 1]) return stopCount - 1.0
        var i = 0
        while (i < stopCount - 2 && along >= stopAlong[i + 1]) i++
        val len = stopAlong[i + 1] - stopAlong[i]
        return i + if (len < 1e-6) 0.0 else (along - stopAlong[i]) / len
    }

    private companion object {
        /** Metres per degree of latitude on the mean-radius sphere used by the rest of the code (6,371,008.8 m). */
        const val METERS_PER_DEGREE = 6_371_008.8 * Math.PI / 180.0
    }
}
