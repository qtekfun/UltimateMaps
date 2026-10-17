package com.qtekfun.ultimatemaps.search

import java.io.File

/**
 * Lets the app skip `refreshMaps()` of the core when the maps on disk are exactly as at the last refresh. A refresh is not a
 * cheap check: the core rebuilds its routers, empties the search caches and reloads the city boundaries and the countries tree,
 * so doing it on every reroute also made the next search cold (see `docs/phase2/speed-plan.md`, F2).
 */
class MapsRefreshGate {
    private val lock = Any()
    private var last: String? = null

    /**
     * Runs [refresh] unless [fingerprint] equals the one of the last refresh that finished. A refresh that throws is not
     * remembered, so the next call tries again. Two callers racing on a change may both refresh, which is harmless.
     * Returns true when [refresh] ran.
     */
    fun refreshIfChanged(fingerprint: String, refresh: () -> Unit): Boolean {
        if (synchronized(lock) { fingerprint == last }) return false
        refresh()
        synchronized(lock) { last = fingerprint }
        return true
    }

    companion object {
        /**
         * A cheap identity of the `.mwm` files under [mapsDir] (`<version>/<Region>.mwm`): path, size and modification time of
         * each, in a stable order. A download, an update or a delete changes it; files that are not maps (partial downloads,
         * notes) do not.
         */
        fun fingerprint(mapsDir: File): String = buildString {
            val versions = mapsDir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
            for (version in versions) {
                val maps = version.listFiles { f -> f.isFile && f.extension == "mwm" }.orEmpty().sortedBy { it.name }
                for (map in maps) append(version.name).append('/').append(map.name).append(':').append(map.length()).append('@').append(map.lastModified()).append('\n')
            }
        }
    }
}
