package com.qtekfun.ultimatemaps.core.fuel

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlinx.coroutines.flow.StateFlow

/**
 * A fuel the source publishes a price for. [id] is our stable key (stored in settings, used as the key of
 * [FuelStation.prices]); [sourceProductId] is the id the data service uses for it (null until verified).
 */
data class FuelType(val id: String, val displayName: String, val sourceProductId: Int? = null)

/** A petrol station with the prices of every fuel it sells that we downloaded, keyed by [FuelType.id], in euros per litre (or kg). */
data class FuelStation(
    val id: String,
    val brand: String,
    val address: String,
    val municipality: String,
    val province: String,
    val location: LatLon,
    val schedule: String? = null,
    val prices: Map<String, Double> = emptyMap(),
)

/** A visible map rectangle. `west > east` is not supported (the data is Spanish; no antimeridian crossing). */
data class LatLonBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(p: LatLon) = p.lat in south..north && p.lon in west..east
}

/** Read side used by the map and the station card. Implementations must be cheap to call from the UI thread (in-memory index). */
interface FuelRepository {
    /** Stations inside [bounds] that sell [fuel], at most [limit] (the cheapest first when more than [limit]). */
    fun stationsIn(bounds: LatLonBounds, fuel: FuelType, limit: Int = 500): List<FuelStation>

    fun station(id: String): FuelStation?

    /** When the data was last downloaded (epoch millis), or null when there is none. Shown to the user. */
    val lastUpdateMillis: StateFlow<Long?>
}

/** What the user configures in Settings for gasolineras (RF-15, RF-17). Off by default; nothing is downloaded when [enabled] is false. */
data class FuelSettings(
    val enabled: Boolean = false,
    /** Ids of the fuels to download (one request per fuel, so the server never learns the user's area). */
    val downloadedFuels: Set<String> = emptySet(),
    /** The fuel whose price is drawn over each station on the map; must be one of [downloadedFuels]. */
    val mapFuel: String? = null,
    /** Minimum minutes between downloads of the same fuel. The service itself updates every 30 minutes. */
    val refreshMinutes: Int = 60,
    /** Base URL of the service (editable). */
    val sourceUrl: String = DEFAULT_SOURCE_URL,
) {
    companion object {
        const val DEFAULT_SOURCE_URL = "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/"
    }
}

interface FuelSettingsStore {
    val settings: StateFlow<FuelSettings>
    fun update(transform: (FuelSettings) -> FuelSettings)
}

/** Simple in-memory implementations for tests and previews. */
class InMemoryFuelRepository(stations: List<FuelStation> = emptyList(), updated: Long? = null) : FuelRepository {
    private val all = stations
    override val lastUpdateMillis = kotlinx.coroutines.flow.MutableStateFlow(updated)
    override fun stationsIn(bounds: LatLonBounds, fuel: FuelType, limit: Int) =
        all.filter { bounds.contains(it.location) && fuel.id in it.prices }.sortedBy { it.prices[fuel.id] }.take(limit)
    override fun station(id: String) = all.firstOrNull { it.id == id }
}
