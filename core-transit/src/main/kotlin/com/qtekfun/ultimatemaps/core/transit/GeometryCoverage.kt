package com.qtekfun.ultimatemaps.core.transit

/** How much of the rail-bound patterns of an index has real line geometry (the rest is drawn and followed as the stop-to-stop chord). */
data class GeometryCoverage(
    val patterns: Int,
    /** Patterns with a polyline (at least two stops placed on the feed's shape). */
    val withGeometry: Int,
    /** Patterns whose every hop follows the shape. */
    val fullyOnShape: Int,
    val hops: Int,
    val hopsOnShape: Int,
)

/** Coverage per mode, only for the modes of [isRailLike] route types (buses are not given geometry). Empty modes are omitted. */
fun TransitIndex.geometryCoverage(): Map<TransitMode, GeometryCoverage> {
    val acc = LinkedHashMap<TransitMode, IntArray>() // patterns, withGeometry, full, hops, hopsOn
    val hop = patternHopOnShape
    for (p in 0 until patternCount) {
        val type = lineType[patternLine[p]]
        if (!isRailLike(type)) continue
        val a = acc.getOrPut(TransitMode.ofRouteType(type)) { IntArray(5) }
        a[0]++
        val hops = patternStopCount(p) - 1
        a[3] += hops
        if (hasShape(p) && hop != null) {
            a[1]++
            var on = 0
            for (i in 0 until hops) if (hop[patternStopOffset[p] + i]) on++
            a[4] += on
            if (on == hops) a[2]++
        }
    }
    return acc.mapValues { (_, a) -> GeometryCoverage(a[0], a[1], a[2], a[3], a[4]) }
}
