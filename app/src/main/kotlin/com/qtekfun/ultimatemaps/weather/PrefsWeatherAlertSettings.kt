package com.qtekfun.ultimatemaps.weather

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.ultimatemaps.core.weather.WeatherAlertSettings
import com.qtekfun.ultimatemaps.core.weather.WeatherAlertSettingsStore
import com.qtekfun.ultimatemaps.settings.backup.SettingsReloadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** [WeatherAlertSettingsStore] over SharedPreferences (`mapas_weather`). Both switches are off by default. */
class PrefsWeatherAlertSettings(private val prefs: SharedPreferences) : WeatherAlertSettingsStore, SettingsReloadable {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())
    override val settings: StateFlow<WeatherAlertSettings> = state

    /** Re-reads the file (after a settings restore wrote to it behind this store's back). */
    @Synchronized
    override fun reload() {
        state.value = read()
    }

    @Synchronized
    override fun update(transform: (WeatherAlertSettings) -> WeatherAlertSettings) {
        val next = transform(state.value)
        if (next == state.value) return
        prefs.edit().putBoolean(KEY_ENABLED, next.enabled).putBoolean(KEY_SHOW_YELLOW, next.showYellow).apply()
        state.value = next
    }

    private fun read(): WeatherAlertSettings {
        val d = WeatherAlertSettings()
        return WeatherAlertSettings(
            enabled = runCatching { prefs.getBoolean(KEY_ENABLED, d.enabled) }.getOrDefault(d.enabled),
            showYellow = runCatching { prefs.getBoolean(KEY_SHOW_YELLOW, d.showYellow) }.getOrDefault(d.showYellow),
        )
    }

    companion object {
        const val PREFS = "mapas_weather"
        const val KEY_ENABLED = "enabled"
        const val KEY_SHOW_YELLOW = "show_yellow"
    }
}
