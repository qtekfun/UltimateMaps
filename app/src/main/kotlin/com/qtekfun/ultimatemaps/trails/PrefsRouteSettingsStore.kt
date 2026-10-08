package com.qtekfun.ultimatemaps.trails

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.ultimatemaps.core.routes.RouteSettings
import com.qtekfun.ultimatemaps.core.routes.RouteSettingsStore
import com.qtekfun.ultimatemaps.core.routes.normalized
import com.qtekfun.ultimatemaps.settings.backup.SettingsReloadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [RouteSettingsStore] over SharedPreferences (`mapas_routes`). Every value is normalized on the way in and out, so a damaged
 * or hand-edited file can never hide both kinds of route. Off by default.
 */
class PrefsRouteSettingsStore(private val prefs: SharedPreferences) : RouteSettingsStore, SettingsReloadable {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())
    override val settings: StateFlow<RouteSettings> = state

    /** Re-reads the file (after a settings restore wrote to it behind this store's back). */
    @Synchronized
    override fun reload() {
        state.value = read()
    }

    @Synchronized
    override fun update(transform: (RouteSettings) -> RouteSettings) {
        val next = transform(state.value).normalized()
        if (next == state.value) return
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putBoolean(KEY_HIKING, next.hiking)
            .putBoolean(KEY_CYCLING, next.cycling)
            .apply()
        state.value = next
    }

    private fun read(): RouteSettings {
        val d = RouteSettings()
        return RouteSettings(
            enabled = runCatching { prefs.getBoolean(KEY_ENABLED, d.enabled) }.getOrDefault(d.enabled),
            hiking = runCatching { prefs.getBoolean(KEY_HIKING, d.hiking) }.getOrDefault(d.hiking),
            cycling = runCatching { prefs.getBoolean(KEY_CYCLING, d.cycling) }.getOrDefault(d.cycling),
        ).normalized()
    }

    companion object {
        const val PREFS = "mapas_routes"
        const val KEY_ENABLED = "enabled"
        const val KEY_HIKING = "hiking"
        const val KEY_CYCLING = "cycling"
    }
}
