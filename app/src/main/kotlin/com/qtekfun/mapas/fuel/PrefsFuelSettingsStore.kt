package com.qtekfun.mapas.fuel

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.mapas.core.fuel.FuelSettings
import com.qtekfun.mapas.core.fuel.FuelSettingsStore
import com.qtekfun.mapas.core.fuel.normalized
import com.qtekfun.mapas.settings.backup.SettingsReloadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [FuelSettingsStore] over SharedPreferences (`mapas_fuel`). Every value is normalized on the way in and out, so a
 * damaged or hand-edited file can never give inconsistent settings (for instance a map fuel that is not downloaded).
 * Off by default.
 */
class PrefsFuelSettingsStore(private val prefs: SharedPreferences) : FuelSettingsStore, SettingsReloadable {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())
    override val settings: StateFlow<FuelSettings> = state

    /** Re-reads the file (after a settings restore wrote to it behind this store's back). */
    @Synchronized
    override fun reload() {
        state.value = read()
    }

    @Synchronized
    override fun update(transform: (FuelSettings) -> FuelSettings) {
        val next = transform(state.value).normalized()
        if (next == state.value) return
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putStringSet(KEY_FUELS, next.downloadedFuels.toSet())
            .putString(KEY_MAP_FUEL, next.mapFuel)
            .putInt(KEY_REFRESH, next.refreshMinutes)
            .putString(KEY_URL, next.sourceUrl)
            .apply()
        state.value = next
    }

    private fun read(): FuelSettings {
        val d = FuelSettings()
        return FuelSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, d.enabled),
            downloadedFuels = runCatching { prefs.getStringSet(KEY_FUELS, emptySet()).orEmpty().toSet() }.getOrDefault(emptySet()),
            mapFuel = runCatching { prefs.getString(KEY_MAP_FUEL, null) }.getOrNull(),
            refreshMinutes = runCatching { prefs.getInt(KEY_REFRESH, d.refreshMinutes) }.getOrDefault(d.refreshMinutes),
            sourceUrl = runCatching { prefs.getString(KEY_URL, d.sourceUrl) }.getOrNull() ?: d.sourceUrl,
        ).normalized()
    }

    companion object {
        const val PREFS = "mapas_fuel"
        const val KEY_ENABLED = "enabled"
        const val KEY_FUELS = "fuels"
        const val KEY_MAP_FUEL = "map_fuel"
        const val KEY_REFRESH = "refresh_minutes"
        const val KEY_URL = "source_url"
    }
}
