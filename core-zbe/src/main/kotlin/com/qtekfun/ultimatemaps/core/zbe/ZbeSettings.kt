package com.qtekfun.ultimatemaps.core.zbe

import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the user configures in Settings. Everything is OFF by default and nothing is downloaded while [enabled] is off.
 *
 * - [enabled]: the switch. Turning it on downloads the small zone file once from the catalog's data server; it also turns on
 *   the warning on a car route and the "ahead" prompt while navigating by car.
 * - [showOnMap]: draw the zones on the map (only while [enabled]).
 * - [promptMode]: how the one-time "Low-emission zone ahead" prompt is announced (chime, voice or only the on-screen banner),
 *   with the same delivery rules as the camera alerts.
 */
data class ZbeSettings(
    val enabled: Boolean = false,
    val showOnMap: Boolean = true,
    val promptMode: AlertSoundMode = AlertSoundMode.SOUND,
) {
    /** The zones are drawn on the map. */
    val layerVisible: Boolean get() = enabled && showOnMap
}

interface ZbeSettingsStore {
    val settings: StateFlow<ZbeSettings>
    fun update(transform: (ZbeSettings) -> ZbeSettings)
}

/** In-memory store for tests and previews. */
class InMemoryZbeSettingsStore(initial: ZbeSettings = ZbeSettings()) : ZbeSettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<ZbeSettings> = state
    override fun update(transform: (ZbeSettings) -> ZbeSettings) {
        state.value = transform(state.value)
    }
}
