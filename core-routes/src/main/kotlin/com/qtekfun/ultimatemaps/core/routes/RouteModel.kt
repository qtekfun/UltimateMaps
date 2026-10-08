package com.qtekfun.ultimatemaps.core.routes

import com.qtekfun.ultimatemaps.core.geo.LatLon

/** What walks or rides the route. The numbers are the values stored in the data file (`scripts/build-routes.py`). */
enum class TrailKind(val code: Int) {
    HIKING(0), CYCLING(1), MTB(2);

    /** Cycling and mountain-bike routes are drawn with the same dash, apart from the walking ones. */
    val isBike: Boolean get() = this != HIKING

    companion object {
        fun ofCode(code: Int): TrailKind? = entries.firstOrNull { it.code == code }
    }
}

/** How far a route reaches, from OSM `network` (`lwn`/`lcn` local, `rwn`/`rcn` regional, `nwn`/`ncn` national, `iwn`/`icn` international). */
enum class TrailLevel(val code: Int) {
    LOCAL(0), REGIONAL(1), NATIONAL(2), INTERNATIONAL(3);

    companion object {
        fun ofCode(code: Int): TrailLevel? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One route relation, simplified. [segments] are lines of interleaved latitude and longitude in millionths of a degree
 * (`lat0, lon0, lat1, lon1, ...`): a route is usually several lines, because the member ways do not always touch.
 * [lengthMeters] is 0 when unknown; [lengthFromTag] says whether it is the `distance` tag (true) or measured on the
 * simplified lines (false, a lower bound when the route has gaps).
 */
class Trail(
    val id: Int,
    val kind: TrailKind,
    val level: TrailLevel,
    val lengthMeters: Int,
    val lengthFromTag: Boolean,
    val name: String,
    val ref: String,
    val operator: String,
    val segments: List<IntArray>,
) {
    val minLat: Int
    val maxLat: Int
    val minLon: Int
    val maxLon: Int

    init {
        var a = Int.MAX_VALUE
        var b = Int.MIN_VALUE
        var c = Int.MAX_VALUE
        var d = Int.MIN_VALUE
        for (s in segments) {
            var i = 0
            while (i + 1 < s.size) {
                if (s[i] < a) a = s[i]
                if (s[i] > b) b = s[i]
                if (s[i + 1] < c) c = s[i + 1]
                if (s[i + 1] > d) d = s[i + 1]
                i += 2
            }
        }
        minLat = a; maxLat = b; minLon = c; maxLon = d
    }

    /** First point of the first line: where "Route to the start" goes. */
    val start: LatLon?
        get() = segments.firstOrNull()?.takeIf { it.size >= 2 }?.let { LatLon.ofOrNull(it[0] / 1e6, it[1] / 1e6) }

    /** Name for display: the name, else the ref, else empty. */
    val title: String get() = name.ifBlank { ref }
}

class TrailDataset(val generatedAtEpochSeconds: Long, val trails: List<Trail>) {
    companion object {
        val EMPTY = TrailDataset(0, emptyList())
    }
}
