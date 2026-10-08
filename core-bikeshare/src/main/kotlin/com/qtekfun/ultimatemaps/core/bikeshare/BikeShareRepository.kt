package com.qtekfun.ultimatemaps.core.bikeshare

import com.qtekfun.ultimatemaps.core.cameras.LatLonBounds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The stations in memory, for the map layer and the card. Records are kept sorted by latitude, so a viewport query
 * binary-searches the latitude band and only looks at the longitudes inside it. Thread-safe for one writer ([install])
 * and many readers: a query reads one immutable snapshot.
 */
class BikeShareRepository {
    private class Snapshot(val dataset: BikeShareDataset) {
        val byLat: List<BikeStation> = dataset.stations.sortedBy { it.location.lat }
        val byId: Map<String, BikeStation> = dataset.stations.associateBy { it.id }
    }

    @Volatile private var snapshot = Snapshot(BikeShareDataset.EMPTY)
    private val updated = MutableStateFlow<Long?>(null)

    /** Generation time of the data in the file (epoch millis), or null without data. Shown to the user. */
    val generatedMillis: StateFlow<Long?> get() = updated

    val data: BikeShareDataset get() = snapshot.dataset

    fun install(d: BikeShareDataset?) {
        snapshot = Snapshot(d ?: BikeShareDataset.EMPTY)
        updated.value = d?.generatedAtEpochSeconds?.takeIf { it > 0 }?.times(1000)
    }

    /** The stations inside [bounds], at most [limit] of them (the northernmost ones when more match, to be deterministic). */
    fun stationsIn(bounds: LatLonBounds, limit: Int): List<BikeStation> {
        val list = snapshot.byLat
        var lo = 0
        var hi = list.size
        while (lo < hi) { // first index with lat >= south
            val mid = (lo + hi) ushr 1
            if (list[mid].location.lat < bounds.south) lo = mid + 1 else hi = mid
        }
        val found = ArrayList<BikeStation>()
        var i = lo
        while (i < list.size && list[i].location.lat <= bounds.north) {
            val s = list[i]
            if (s.location.lon in bounds.west..bounds.east) found += s
            i++
        }
        if (found.size <= limit) return found
        return found.sortedByDescending { it.location.lat }.take(limit)
    }

    fun station(id: String): BikeStation? = snapshot.byId[id]
}
