package com.qtekfun.ultimatemaps.core.chargers

import com.qtekfun.ultimatemaps.core.cameras.LatLonBounds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The chargers in memory, for the map layer and the card. Records are kept sorted by latitude, so a viewport query
 * binary-searches the latitude band and only looks at the longitudes inside it. Thread-safe for one writer ([install])
 * and many readers: a query reads one immutable snapshot.
 */
class ChargerRepository {
    private class Snapshot(val dataset: ChargerDataset) {
        val byLat: List<Charger> = dataset.chargers.sortedBy { it.location.lat }
        val byId: Map<String, Charger> = dataset.chargers.associateBy { it.id }
    }

    @Volatile private var snapshot = Snapshot(ChargerDataset.EMPTY)
    private val updated = MutableStateFlow<Long?>(null)

    /** Generation time of the data in the file (epoch millis), or null without data. Shown to the user. */
    val generatedMillis: StateFlow<Long?> get() = updated

    val data: ChargerDataset get() = snapshot.dataset

    fun install(d: ChargerDataset?) {
        snapshot = Snapshot(d ?: ChargerDataset.EMPTY)
        updated.value = d?.generatedAtEpochSeconds?.takeIf { it > 0 }?.times(1000)
    }

    /**
     * The chargers inside [bounds] that pass [settings]' plug and power filters, at most [limit] of them. When more match,
     * the ones with the highest known output are kept (then the northernmost, to be deterministic).
     */
    fun chargersIn(bounds: LatLonBounds, settings: ChargerSettings, limit: Int): List<Charger> {
        val list = snapshot.byLat
        var lo = 0
        var hi = list.size
        while (lo < hi) { // first index with lat >= south
            val mid = (lo + hi) ushr 1
            if (list[mid].location.lat < bounds.south) lo = mid + 1 else hi = mid
        }
        val found = ArrayList<Charger>()
        var i = lo
        while (i < list.size && list[i].location.lat <= bounds.north) {
            val c = list[i]
            if (c.location.lon in bounds.west..bounds.east && settings.accepts(c)) found += c
            i++
        }
        if (found.size <= limit) return found
        return found.sortedWith(compareByDescending<Charger> { it.maxPowerKw ?: 0.0 }.thenByDescending { it.location.lat }).take(limit)
    }

    fun charger(id: String): Charger? = snapshot.byId[id]
}
