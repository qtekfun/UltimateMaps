package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.map.TrailLine

/**
 * How the optional hiking and cycling routes look, without any MapLibre type so it runs on the JVM in tests. Colour tells
 * the reach of a route (local, regional, national, international; a palette that stays apart for the common kinds of colour
 * blindness), the dash tells walking from cycling, so neither is carried by colour alone.
 */
object TrailStyle {
    /** ARGB per level code 0..3. */
    val LEVEL_COLORS: IntArray = intArrayOf(0xFFAA4499.toInt(), 0xFF009E73.toInt(), 0xFFE69F00.toInt(), 0xFFCC3311.toInt())

    /** Dash lengths in line widths: walking routes get long dashes, cycling routes dots. */
    val HIKING_DASH: FloatArray = floatArrayOf(3f, 1.5f)
    val CYCLING_DASH: FloatArray = floatArrayOf(1f, 1.8f)

    const val SOURCE = "mapas-trails-src"
    const val CASING_LAYER = "mapas-trails-casing"
    const val HIKING_LAYER = "mapas-trails-hiking"
    const val CYCLING_LAYER = "mapas-trails-cycling"
    const val ID = "id"
    const val LEVEL = "level"
    const val CYCLING = "cycling"

    /** Touch tolerance around a finger, in dp, when looking for a route line under a tap. */
    const val TOUCH_DP = 32f

    /** Line width in dp as (zoom, width) stops. */
    val WIDTH_STOPS: List<Pair<Int, Float>> = listOf(7 to 1.2f, 12 to 2.4f, 16 to 4f)

    fun colorOf(level: Int): Int = LEVEL_COLORS[level.coerceIn(0, LEVEL_COLORS.size - 1)]

    /** The attributes the engine puts on each GeoJSON feature of [line], by name (checked by tests). */
    fun properties(line: TrailLine): Map<String, Any> = mapOf(ID to line.id, LEVEL to line.level.coerceIn(0, 3), CYCLING to line.cycling)

    /** Lines too short to draw (a single point) are skipped. */
    fun drawable(lines: List<TrailLine>): List<TrailLine> = lines.filter { it.points.size >= 2 }
}
