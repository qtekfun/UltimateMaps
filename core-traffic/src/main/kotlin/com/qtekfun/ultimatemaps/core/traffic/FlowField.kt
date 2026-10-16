package com.qtekfun.ultimatemaps.core.traffic

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/** What the traffic says about one place: the speed as a share of the free-flow speed (0..1), and whether the road is closed. */
data class FlowSample(val ratio: Double, val closed: Boolean)

/**
 * The traffic flow of an area: the road segments of one or more TomTom Orbis vector flow tiles, with their
 * `relative_speed` (the current speed as a share of the free-flow speed). Pure.
 *
 * Reading the tiles (TomTom Orbis Maps v2 Vector Flow Tiles): the layer is "Traffic flow"; each feature has `road_category`,
 * `relative_speed` and optionally `road_closure`. Two-way roads come as two geometries, one per direction, each oriented in
 * the direction its value applies to, so every segment is directional. A `relative_speed` of 0 without `road_closure` is
 * taken as "no data": it says nothing and is ignored; a closure is reported as closed.
 */
class FlowField private constructor(private val cells: Map<Long, List<Segment>>, val segmentCount: Int) {
    class Segment(val aLat: Double, val aLon: Double, val bLat: Double, val bLon: Double, val ratio: Double, val oneSide: Boolean, val closed: Boolean)

    /**
     * The flow at ([lat], [lon]) for a vehicle heading [headingDeg] (null: unknown), from the nearest road segment within
     * [maxMeters] whose direction agrees (a `one_side` segment only counts when the heading is within 70 degrees of it), or null.
     */
    fun sampleAt(lat: Double, lon: Double, headingDeg: Double?, maxMeters: Double = DEFAULT_MAX_METERS): FlowSample? {
        val cx = cellX(lon)
        val cy = cellY(lat)
        var best: Segment? = null
        var bestD = maxMeters
        val kx = METERS_PER_DEGREE * cos(Math.toRadians(lat))
        for (dy in -1..1) for (dx in -1..1) {
            val list = cells[key(cx + dx, cy + dy)] ?: continue
            for (s in list) {
                if (s.oneSide && headingDeg != null && angleDiff(headingDeg, bearing(s)) > ONE_SIDE_TOLERANCE_DEG) continue
                val d = distanceToSegment(lat, lon, s, kx)
                if (d < bestD) {
                    bestD = d
                    best = s
                }
            }
        }
        return best?.let { FlowSample(it.ratio, it.closed) }
    }

    class Builder {
        private val cells = HashMap<Long, MutableList<Segment>>()
        private var count = 0

        /** Adds the lines of a decoded "Traffic flow" layer of [tile]. Features without a usable level are skipped. */
        fun addTile(tile: TileId, layers: List<MvtLayer>): Builder {
            for (layer in layers) {
                if (!layer.name.equals("Traffic flow", ignoreCase = true)) continue
                for (f in layer.features) {
                    val level = (f.properties["relative_speed"] as? Number)?.toDouble() ?: continue
                    val closed = f.properties["road_closure"] == true
                    if (level <= 0.0 && !closed) continue // no data
                    val oneSide = true
                    for (line in f.lines) {
                        var prev: LatLon? = null
                        for (pt in line) {
                            val ll = TileMath.toLatLon(tile, pt[0].toDouble(), pt[1].toDouble(), layer.extent)
                            if (prev != null) add(Segment(prev.lat, prev.lon, ll.lat, ll.lon, level.coerceIn(0.0, 1.0), oneSide, closed))
                            prev = ll
                        }
                    }
                }
            }
            return this
        }

        fun addSegment(s: Segment): Builder {
            add(s)
            return this
        }

        private fun add(s: Segment) {
            val x0 = cellX(minOf(s.aLon, s.bLon))
            val x1 = cellX(maxOf(s.aLon, s.bLon))
            val y0 = cellY(minOf(s.aLat, s.bLat))
            val y1 = cellY(maxOf(s.aLat, s.bLat))
            for (x in x0..x1) for (y in y0..y1) cells.getOrPut(key(x, y)) { ArrayList() }.add(s)
            count++
        }

        fun build() = FlowField(cells, count)
    }

    companion object {
        const val DEFAULT_MAX_METERS = 25.0
        private const val ONE_SIDE_TOLERANCE_DEG = 70.0
        private const val METERS_PER_DEGREE = 111_195.0
        private const val CELL_DEGREES = 0.005
        val EMPTY: FlowField = Builder().build()

        private fun cellX(lon: Double) = floor(lon / CELL_DEGREES).toInt()
        private fun cellY(lat: Double) = floor(lat / CELL_DEGREES).toInt()
        private fun key(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)

        private fun bearing(s: Segment): Double {
            val dy = s.bLat - s.aLat
            val dx = (s.bLon - s.aLon) * cos(Math.toRadians((s.aLat + s.bLat) / 2))
            return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
        }

        private fun angleDiff(a: Double, b: Double): Double {
            val d = abs(a - b) % 360.0
            return if (d > 180.0) 360.0 - d else d
        }

        private fun distanceToSegment(lat: Double, lon: Double, s: Segment, kx: Double): Double {
            val ax = (s.aLon - lon) * kx
            val ay = (s.aLat - lat) * METERS_PER_DEGREE
            val bx = (s.bLon - lon) * kx
            val by = (s.bLat - lat) * METERS_PER_DEGREE
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 1e-9) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            return hypot(ax + t * dx, ay + t * dy)
        }
    }
}
