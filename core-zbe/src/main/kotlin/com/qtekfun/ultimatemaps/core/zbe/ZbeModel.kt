package com.qtekfun.ultimatemaps.core.zbe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One ring of a polygon: vertices as `lat0, lon0, lat1, lon1, ...` in degrees, NOT repeated at the end (the closing edge is
 * implied). Immutable by convention (the array is never exposed for writing outside this module).
 */
class ZbeRing(val coords: DoubleArray) {
    init {
        require(coords.size >= 6 && coords.size % 2 == 0) { "a ring needs at least three vertices" }
    }

    val size: Int get() = coords.size / 2
    fun lat(i: Int): Double = coords[2 * i]
    fun lon(i: Int): Double = coords[2 * i + 1]
}

/** Smallest rectangle around something, in degrees. */
class ZbeBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east

    /** True when the two rectangles share any point (touching counts). */
    fun intersects(o: ZbeBox) = o.west <= east && o.east >= west && o.south <= north && o.north >= south

    fun intersects(lat0: Double, lon0: Double, lat1: Double, lon1: Double): Boolean =
        minOf(lon0, lon1) <= east && maxOf(lon0, lon1) >= west && minOf(lat0, lat1) <= north && maxOf(lat0, lat1) >= south
}

/**
 * An outer ring and its holes. A point is inside when it is inside an odd number of rings (even-odd rule), so the orientation
 * of the rings does not matter.
 */
class ZbePolygon(val rings: List<ZbeRing>) {
    init {
        require(rings.isNotEmpty()) { "a polygon needs an outer ring" }
    }

    val box: ZbeBox = run {
        val outer = rings[0]
        var s = Double.MAX_VALUE; var w = Double.MAX_VALUE; var n = -Double.MAX_VALUE; var e = -Double.MAX_VALUE
        for (i in 0 until outer.size) {
            s = minOf(s, outer.lat(i)); n = maxOf(n, outer.lat(i)); w = minOf(w, outer.lon(i)); e = maxOf(e, outer.lon(i))
        }
        ZbeBox(s, w, n, e)
    }

    fun contains(lat: Double, lon: Double): Boolean {
        if (!box.contains(lat, lon)) return false
        var inside = false
        for (ring in rings) if (ringContains(ring, lat, lon)) inside = !inside
        return inside
    }

    private fun ringContains(r: ZbeRing, lat: Double, lon: Double): Boolean {
        var inside = false
        var j = r.size - 1
        for (i in 0 until r.size) {
            val yi = r.lat(i)
            val yj = r.lat(j)
            if ((yi > lat) != (yj > lat)) {
                val xAt = r.lon(i) + (lat - yi) / (yj - yi) * (r.lon(j) - r.lon(i))
                if (lon < xAt) inside = !inside
            }
            j = i
        }
        return inside
    }
}

/**
 * A low-emission zone as OpenStreetMap has it. [restriction] is free text taken from the OSM tags (a description, or the
 * raw conditional-access tag) and may be empty; it is shown as "tagged in OpenStreetMap", never interpreted.
 */
class ZbeZone(
    val id: String,
    val name: String,
    val city: String,
    val restriction: String,
    val polygons: List<ZbePolygon>,
) {
    init {
        require(polygons.isNotEmpty()) { "a zone needs a polygon" }
    }

    val box: ZbeBox = ZbeBox(
        polygons.minOf { it.box.south }, polygons.minOf { it.box.west }, polygons.maxOf { it.box.north }, polygons.maxOf { it.box.east },
    )

    fun contains(lat: Double, lon: Double): Boolean = box.contains(lat, lon) && polygons.any { it.contains(lat, lon) }

    /** "Name (City)", or whichever exists, or an empty string. */
    val label: String
        get() = when {
            name.isNotBlank() && city.isNotBlank() && !name.contains(city, ignoreCase = true) -> "$name ($city)"
            name.isNotBlank() -> name
            else -> city
        }
}

/** What a zone file holds. [generatedAtEpochSeconds] is the build time; [flags] bit 0 is set when the source is OSM. */
class ZbeDataset(val generatedAtEpochSeconds: Long, val flags: Int, val zones: List<ZbeZone>) {
    companion object {
        val EMPTY = ZbeDataset(0L, 0, emptyList())
    }
}

/** The zones in memory, for the layer, the route check and the navigation prompt. One writer ([install]), many readers. */
class ZbeRepository {
    private class Snapshot(val data: ZbeDataset, val index: ZbeIndex)

    @Volatile private var snapshot = Snapshot(ZbeDataset.EMPTY, ZbeIndex(emptyList()))
    private val updated = MutableStateFlow<Long?>(null)
    private val version = MutableStateFlow(0)

    /** Generation time of the data (epoch millis), or null without data. Shown to the user. */
    val generatedMillis: StateFlow<Long?> get() = updated

    /** Bumps on every [install], so the map layer knows to redraw. */
    val changes: StateFlow<Int> get() = version

    val data: ZbeDataset get() = snapshot.data
    val index: ZbeIndex get() = snapshot.index

    fun install(d: ZbeDataset?) {
        val data = d ?: ZbeDataset.EMPTY
        snapshot = Snapshot(data, ZbeIndex(data.zones))
        updated.value = data.generatedAtEpochSeconds.takeIf { it > 0 }?.times(1000)
        version.value = version.value + 1
    }
}
