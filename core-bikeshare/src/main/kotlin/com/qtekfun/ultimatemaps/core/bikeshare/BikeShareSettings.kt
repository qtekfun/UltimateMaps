package com.qtekfun.ultimatemaps.core.bikeshare

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the user configures in Settings. Everything is OFF by default.
 *
 * - [enabled]: the bike-share layer. Turning it on downloads the small station file once from the catalog's data server.
 * - [liveAvailability]: the second, independent opt-in. Only while a station card is open (and only while [enabled]) the
 *   app asks the bike-share system's public GBFS `station_status` feed how many bikes and docks are free. It contacts a
 *   third-party host, which is why it is off even when [enabled] is on.
 */
data class BikeShareSettings(
    val enabled: Boolean = false,
    val liveAvailability: Boolean = false,
) {
    /** Live data may be fetched: both switches are on. */
    val liveActive: Boolean get() = enabled && liveAvailability
}

interface BikeShareSettingsStore {
    val settings: StateFlow<BikeShareSettings>
    fun update(transform: (BikeShareSettings) -> BikeShareSettings)
}

/** In-memory store for tests and previews. */
class InMemoryBikeShareSettingsStore(initial: BikeShareSettings = BikeShareSettings()) : BikeShareSettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<BikeShareSettings> = state
    override fun update(transform: (BikeShareSettings) -> BikeShareSettings) {
        state.value = transform(state.value)
    }
}
