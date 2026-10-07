package com.qtekfun.mapas.core.fuel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Smallest interval between downloads of one fuel: the service itself updates every 30 minutes. */
const val MIN_REFRESH_MINUTES = 30

/** Intervals the settings screen offers (minutes). */
val REFRESH_CHOICES_MINUTES = listOf(30, 60, 180, 360, 1440)

/** True when [url] is an https URL with a host: the only kind we accept as a source. */
fun isValidSourceUrl(url: String): Boolean = runCatching {
    val u = java.net.URI(url.trim())
    u.scheme.equals("https", ignoreCase = true) && !u.host.isNullOrEmpty()
}.getOrDefault(false)

/** Host of a source URL (lowercase), or null when it is not a valid https URL. */
fun sourceHost(url: String): String? =
    if (isValidSourceUrl(url)) java.net.URI(url.trim()).host.lowercase() else null

/**
 * Makes settings consistent: unknown fuels dropped, the map fuel one of the downloaded ones (the first when it is
 * not), the interval at least [MIN_REFRESH_MINUTES], and an invalid or blank URL replaced by the default.
 */
fun FuelSettings.normalized(): FuelSettings {
    val fuels = downloadedFuels.filter { FuelTypes.byId(it) != null }.toSet()
    val map = when {
        mapFuel != null && mapFuel in fuels -> mapFuel
        else -> FuelTypes.all.firstOrNull { it.id in fuels }?.id
    }
    val url = sourceUrl.trim().let { if (isValidSourceUrl(it)) it else FuelSettings.DEFAULT_SOURCE_URL }
    return copy(downloadedFuels = fuels, mapFuel = map, refreshMinutes = refreshMinutes.coerceAtLeast(MIN_REFRESH_MINUTES), sourceUrl = url)
}

/** In-memory store for tests and previews. Always keeps the settings [normalized]. */
class InMemoryFuelSettingsStore(initial: FuelSettings = FuelSettings(), private val normalize: Boolean = true) : FuelSettingsStore {
    private val state = MutableStateFlow(if (normalize) initial.normalized() else initial)
    override val settings: StateFlow<FuelSettings> = state
    override fun update(transform: (FuelSettings) -> FuelSettings) {
        state.value = transform(state.value).let { if (normalize) it.normalized() else it }
    }
}
