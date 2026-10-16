package com.qtekfun.ultimatemaps.core.traffic

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.math.floor

/** One stretch of road for the map: [level] 0 flowing, 1 slow, 2 heavy, 3 closed. */
class FlowLine(val level: Int, val points: List<LatLon>)

/** What the map layer needs from the flow tiles: the lines to draw, and which tiles cover a view. Pure. */
object FlowLines {
    const val MIN_MAP_ZOOM = 9.0
    const val MAX_TILE_ZOOM = 14
    const val MAX_TILES = 16

    /** Flowing at 75 % of free flow or more; heavy below 40 %. */
    const val SLOW_BELOW = 0.75
    const val HEAVY_BELOW = 0.40

    fun levelOf(ratio: Double, closed: Boolean): Int = when {
        closed -> 3
        ratio < HEAVY_BELOW -> 2
        ratio < SLOW_BELOW -> 1
        else -> 0
    }

    private val MAJOR = setOf("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link")
    private val MID = MAJOR + setOf("secondary", "secondary_link")
    private val MINOR = MID + setOf("tertiary", "tertiary_link")
    private val STREETS = MINOR + setOf("street")

    /** The road categories worth drawing at a tile zoom: only the big roads far out, more as you zoom in; never service roads or tracks. */
    fun categoriesAt(tileZoom: Int): Set<String> = when {
        tileZoom <= 10 -> MAJOR
        tileZoom <= 12 -> MID
        tileZoom == 13 -> MINOR
        else -> STREETS
    }

    /** Points closer than this (tile units of the 4096 extent, about a pixel) to the last kept one are dropped. */
    const val SIMPLIFY_UNITS = 6

    /** Never more lines than this per view: the worst levels first. A phone draws thousands of lines, not tens of thousands. */
    const val MAX_LINES = 4_000

    /**
     * The lines of the decoded "Traffic flow" layers of [tile], worst last so a jam is never hidden under the free flow of the
     * opposite direction. A `relative_speed` of 0 without a closure is "no data" and is left out. To keep the picture light:
     * only the road categories of [categoriesAt] (a feature without the tag is kept), free flow only on the major ones, and
     * the points a pixel apart are dropped.
     */
    fun of(tile: TileId, layers: List<MvtLayer>): List<FlowLine> {
        val allowed = categoriesAt(tile.z)
        val out = ArrayList<FlowLine>()
        for (layer in layers) {
            if (!layer.name.equals("Traffic flow", ignoreCase = true)) continue
            for (f in layer.features) {
                val ratio = (f.properties["relative_speed"] as? Number)?.toDouble() ?: continue
                val closed = f.properties["road_closure"] == true
                if (ratio <= 0.0 && !closed) continue
                val category = f.properties["road_category"] as? String
                if (category != null && category !in allowed) continue
                val level = levelOf(ratio, closed)
                if (level == 0 && category != null && category !in MAJOR) continue // free flow on side roads is only clutter
                for (line in f.lines) {
                    if (line.size < 2) continue
                    val kept = simplify(line)
                    if (kept.size < 2) continue
                    out += FlowLine(level, kept.map { TileMath.toLatLon(tile, it[0].toDouble(), it[1].toDouble(), layer.extent) })
                }
            }
        }
        return limit(out)
    }

    /** At most [MAX_LINES], the worst kept, ordered worst last. */
    fun limit(lines: List<FlowLine>): List<FlowLine> {
        val capped = if (lines.size <= MAX_LINES) lines else lines.sortedByDescending { it.level }.take(MAX_LINES)
        return capped.sortedBy { it.level }
    }

    private fun simplify(line: List<IntArray>): List<IntArray> {
        if (line.size <= 2) return line
        val out = ArrayList<IntArray>(line.size)
        var last = line[0]
        out += last
        for (i in 1 until line.size - 1) {
            val p = line[i]
            if (kotlin.math.abs(p[0] - last[0]) + kotlin.math.abs(p[1] - last[1]) >= SIMPLIFY_UNITS) {
                out += p
                last = p
            }
        }
        out += line.last()
        return out
    }

    /** The tile zoom for a map at [mapZoom]: its integer part, at most [MAX_TILE_ZOOM]. */
    fun tileZoom(mapZoom: Double): Int = floor(mapZoom).toInt().coerceIn(MIN_MAP_ZOOM.toInt(), MAX_TILE_ZOOM)

    /** The tiles covering the view, or empty when it is too far out ([MIN_MAP_ZOOM]) or would need more than [MAX_TILES]. */
    fun tilesFor(south: Double, west: Double, north: Double, east: Double, mapZoom: Double): List<TileId> {
        if (mapZoom < MIN_MAP_ZOOM) return emptyList()
        val z = tileZoom(mapZoom)
        val a = TileMath.tileOf(north.coerceIn(-85.0, 85.0), west.coerceIn(-180.0, 180.0), z)
        val b = TileMath.tileOf(south.coerceIn(-85.0, 85.0), east.coerceIn(-180.0, 180.0), z)
        val width = b.x - a.x + 1
        val height = b.y - a.y + 1
        if (width <= 0 || height <= 0 || width * height > MAX_TILES) return emptyList()
        return buildList { for (x in a.x..b.x) for (y in a.y..b.y) add(TileId(z, x, y)) }
    }
}
