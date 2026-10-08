package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.regions.TransitBounds

/** One transit index (installed or only offered by the catalog) seen by its coverage box. */
data class CoverageEntry(val id: String, val city: String, val bounds: TransitBounds?)

/**
 * Which of several city indexes serves a trip. Pure and deterministic: when boxes overlap the tighter one wins, and ties
 * go to the smaller id, so the same trip always picks the same index whatever the order of the lists.
 */
object TransitCoverage {
    private fun tightest(entries: List<CoverageEntry>, matches: (TransitBounds) -> Boolean): CoverageEntry? =
        entries.filter { e -> e.bounds != null && matches(e.bounds) }
            .minWithOrNull(compareBy<CoverageEntry>({ it.bounds!!.area }, { it.id }))

    /** The index whose box holds both [origin] and [destination], or null. */
    fun covering(entries: List<CoverageEntry>, origin: LatLon, destination: LatLon): CoverageEntry? =
        tightest(entries) { it.contains(origin.lat, origin.lon) && it.contains(destination.lat, destination.lon) }

    /** The index that serves one point (the tighter box), or null. */
    fun serving(entries: List<CoverageEntry>, point: LatLon): CoverageEntry? =
        tightest(entries) { it.contains(point.lat, point.lon) }

    /**
     * A trip whose ends are served by two different indexes while no single one serves both: (origin's, destination's).
     * Null when one index covers both, or when an end is covered by none.
     */
    fun across(entries: List<CoverageEntry>, origin: LatLon, destination: LatLon): Pair<CoverageEntry, CoverageEntry>? {
        if (covering(entries, origin, destination) != null) return null
        val a = serving(entries, origin) ?: return null
        val b = serving(entries, destination) ?: return null
        return if (a.id != b.id) a to b else null
    }
}
