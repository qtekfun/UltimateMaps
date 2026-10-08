package com.qtekfun.ultimatemaps.core.bikeshare

import com.qtekfun.ultimatemaps.core.geo.LatLon

/**
 * A bike-share system in the data file. [attribution] is the credit its open-data licence asks for (for example the owner
 * and "CC BY 4.0"); it is shown wherever the system's stations are shown.
 */
data class BikeSystem(val id: String, val name: String, val attribution: String)

/**
 * A docking station. [id] is unique across the whole file (`<system index>:<station id>`); [stationId] is the id the
 * system's own GBFS feed uses, needed to look the station up in the live `station_status`. [capacity] is the number of
 * docks, 0 when the feed does not say.
 */
data class BikeStation(
    val id: String,
    val system: BikeSystem,
    val stationId: String,
    val name: String,
    val location: LatLon,
    val capacity: Int,
)

class BikeShareDataset(val generatedAtEpochSeconds: Long, val systems: List<BikeSystem>, val stations: List<BikeStation>) {
    companion object {
        val EMPTY = BikeShareDataset(0, emptyList(), emptyList())
    }
}
