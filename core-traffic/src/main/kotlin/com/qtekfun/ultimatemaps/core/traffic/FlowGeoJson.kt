package com.qtekfun.ultimatemaps.core.traffic

/** The GeoJSON of the flow lines for the map, built by hand (a string builder, no feature objects) and meant to run off the main thread. */
object FlowGeoJson {
    const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"

    fun of(lines: List<FlowLine>): String {
        if (lines.isEmpty()) return EMPTY
        val b = StringBuilder(lines.sumOf { 70 + it.points.size * 20 })
        b.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (l in lines) {
            if (l.points.size < 2) continue
            if (!first) b.append(',')
            first = false
            b.append("{\"type\":\"Feature\",\"properties\":{\"level\":").append(l.level).append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            for ((i, p) in l.points.withIndex()) {
                if (i > 0) b.append(',')
                b.append('[').append(coord(p.lon)).append(',').append(coord(p.lat)).append(']')
            }
            b.append("]}}")
        }
        return b.append("]}").toString()
    }

    /** Five decimals (about a metre), without a trailing zero tail. */
    private fun coord(v: Double): String = (Math.round(v * 100_000.0) / 100_000.0).toString()
}
