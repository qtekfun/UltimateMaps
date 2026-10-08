package com.qtekfun.ultimatemaps.bikeshare

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.ultimatemaps.core.bikeshare.BikeShareSettings
import com.qtekfun.ultimatemaps.core.bikeshare.BikeShareSettingsStore
import com.qtekfun.ultimatemaps.settings.backup.SettingsReloadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** [BikeShareSettingsStore] over SharedPreferences (`mapas_bikeshare`). Both switches are off by default. */
class PrefsBikeShareSettingsStore(private val prefs: SharedPreferences) : BikeShareSettingsStore, SettingsReloadable {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())
    override val settings: StateFlow<BikeShareSettings> = state

    /** Re-reads the file (after a settings restore wrote to it behind this store's back). */
    @Synchronized
    override fun reload() {
        state.value = read()
    }

    @Synchronized
    override fun update(transform: (BikeShareSettings) -> BikeShareSettings) {
        val next = transform(state.value)
        if (next == state.value) return
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putBoolean(KEY_LIVE_AVAILABILITY, next.liveAvailability)
            .apply()
        state.value = next
    }

    private fun read(): BikeShareSettings {
        val d = BikeShareSettings()
        return BikeShareSettings(
            enabled = runCatching { prefs.getBoolean(KEY_ENABLED, d.enabled) }.getOrDefault(d.enabled),
            liveAvailability = runCatching { prefs.getBoolean(KEY_LIVE_AVAILABILITY, d.liveAvailability) }.getOrDefault(d.liveAvailability),
        )
    }

    companion object {
        const val PREFS = "mapas_bikeshare"
        const val KEY_ENABLED = "enabled"
        const val KEY_LIVE_AVAILABILITY = "live_availability"
    }
}
