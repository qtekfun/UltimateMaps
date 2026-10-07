package com.qtekfun.ultimatemaps.core.chargers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Smallest output a station must offer to be shown. */
enum class MinPower(val kw: Double) {
    ANY(0.0), KW_22(22.0), KW_50(50.0), KW_100(100.0);

    companion object {
        fun ofName(name: String?): MinPower = entries.firstOrNull { it.name == name } ?: ANY
    }
}

/**
 * What the user configures in Settings. The layer is OFF by default and nothing is downloaded while it is off.
 *
 * - [sockets]: the plugs the user can use. All four offered types selected (the default) means "no plug filter": stations
 *   with other plugs or with no plug information are shown too. A narrower selection shows only stations with a known plug
 *   of a selected type.
 * - [minPower]: stations whose known output is below it are hidden; stations with no known output are hidden too once a
 *   minimum is chosen (we cannot tell they are fast enough).
 */
data class ChargerSettings(
    val enabled: Boolean = false,
    val sockets: Set<SocketType> = SocketType.FILTERABLE,
    val minPower: MinPower = MinPower.ANY,
) {
    /** True when the plug selection narrows the stations shown. */
    val socketFilterActive: Boolean get() = sockets != SocketType.FILTERABLE

    /** Whether [c] passes the plug and power filters. The on/off switch is not looked at here. */
    fun accepts(c: Charger): Boolean {
        val usable = if (socketFilterActive) c.sockets.filter { it.type in sockets } else c.sockets
        if (socketFilterActive && usable.isEmpty()) return false
        if (minPower != MinPower.ANY) {
            val best = usable.mapNotNull { it.powerKw }.maxOrNull() ?: return false
            if (best < minPower.kw) return false
        }
        return true
    }
}

/** Makes settings consistent: only offered plug types, and never an empty selection (it would hide every station). */
fun ChargerSettings.normalized(): ChargerSettings {
    val kept = sockets.intersect(SocketType.FILTERABLE)
    return copy(sockets = if (kept.isEmpty()) SocketType.FILTERABLE else kept)
}

interface ChargerSettingsStore {
    val settings: StateFlow<ChargerSettings>
    fun update(transform: (ChargerSettings) -> ChargerSettings)
}

/** In-memory store for tests and previews. Always keeps the settings [normalized]. */
class InMemoryChargerSettingsStore(initial: ChargerSettings = ChargerSettings()) : ChargerSettingsStore {
    private val state = MutableStateFlow(initial.normalized())
    override val settings: StateFlow<ChargerSettings> = state
    override fun update(transform: (ChargerSettings) -> ChargerSettings) {
        state.value = transform(state.value).normalized()
    }
}
