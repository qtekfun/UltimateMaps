package com.qtekfun.ultimatemaps.core.traffic

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan
import kotlin.math.cos

/** A map tile of the Web Mercator grid. */
data class TileId(val z: Int, val x: Int, val y: Int)

object TileMath {
    private const val MAX_LAT = 85.05112878

    fun tileOf(lat: Double, lon: Double, z: Int): TileId {
        val n = 1 shl z
        val la = lat.coerceIn(-MAX_LAT, MAX_LAT)
        val fx = (lon + 180.0) / 360.0 * n
        val fy = (1.0 - ln(tan(Math.toRadians(la)) + 1.0 / cos(Math.toRadians(la))) / PI) / 2.0 * n
        return TileId(z, floor(fx).toInt().coerceIn(0, n - 1), floor(fy).toInt().coerceIn(0, n - 1))
    }

    /** The position of the point (px, py) of a tile whose coordinates run 0..[extent] (and beyond, in the margin). */
    fun toLatLon(tile: TileId, px: Double, py: Double, extent: Int): LatLon {
        val n = (1 shl tile.z).toDouble()
        val fx = tile.x + px / extent
        val fy = tile.y + py / extent
        val lon = fx / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * fy / n))))
        return LatLon(lat, lon)
    }

    /** The tiles of zoom [z] that a polyline passes through, each with its neighbours within [margin] tiles' worth is not needed: only the tiles touched. */
    fun tilesAlong(points: List<LatLon>, z: Int, stepMeters: Double = 2_000.0): List<TileId> {
        val out = LinkedHashSet<TileId>()
        if (points.isEmpty()) return emptyList()
        out += tileOf(points.first().lat, points.first().lon, z)
        var carried = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val seg = metersBetween(a, b)
            if (seg <= 0.0) continue
            var d = stepMeters - carried
            while (d < seg) {
                val t = d / seg
                out += tileOf(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, z)
                d += stepMeters
            }
            carried = (carried + seg) % stepMeters
            out += tileOf(b.lat, b.lon, z)
        }
        return out.toList()
    }

    internal fun metersBetween(a: LatLon, b: LatLon): Double {
        val ky = 111_195.0
        val kx = ky * cos(Math.toRadians((a.lat + b.lat) / 2))
        val dx = (b.lon - a.lon) * kx
        val dy = (b.lat - a.lat) * ky
        return Math.sqrt(dx * dx + dy * dy)
    }
}
