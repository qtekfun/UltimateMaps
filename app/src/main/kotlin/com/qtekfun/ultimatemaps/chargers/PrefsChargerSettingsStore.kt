package com.qtekfun.ultimatemaps.chargers

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.ultimatemaps.core.chargers.ChargerSettings
import com.qtekfun.ultimatemaps.core.chargers.ChargerSettingsStore
import com.qtekfun.ultimatemaps.core.chargers.MinPower
import com.qtekfun.ultimatemaps.core.chargers.SocketType
import com.qtekfun.ultimatemaps.core.chargers.normalized
import com.qtekfun.ultimatemaps.settings.backup.SettingsReloadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [ChargerSettingsStore] over SharedPreferences (`mapas_chargers`). Every value is normalized on the way in and out, so a
 * damaged or hand-edited file can never give an empty plug selection. Off by default.
 */
class PrefsChargerSettingsStore(private val prefs: SharedPreferences) : ChargerSettingsStore, SettingsReloadable {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())
    override val settings: StateFlow<ChargerSettings> = state

    /** Re-reads the file (after a settings restore wrote to it behind this store's back). */
    @Synchronized
    override fun reload() {
        state.value = read()
    }

    @Synchronized
    override fun update(transform: (ChargerSettings) -> ChargerSettings) {
        val next = transform(state.value).normalized()
        if (next == state.value) return
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putStringSet(KEY_SOCKETS, next.sockets.map { it.name }.toSet())
            .putString(KEY_MIN_POWER, next.minPower.name)
            .apply()
        state.value = next
    }

    private fun read(): ChargerSettings {
        val d = ChargerSettings()
        val names = runCatching { prefs.getStringSet(KEY_SOCKETS, null) }.getOrNull()
        return ChargerSettings(
            enabled = runCatching { prefs.getBoolean(KEY_ENABLED, d.enabled) }.getOrDefault(d.enabled),
            sockets = names?.mapNotNull { n -> SocketType.entries.firstOrNull { it.name == n } }?.toSet() ?: d.sockets,
            minPower = MinPower.ofName(runCatching { prefs.getString(KEY_MIN_POWER, null) }.getOrNull()),
        ).normalized()
    }

    companion object {
        const val PREFS = "mapas_chargers"
        const val KEY_ENABLED = "enabled"
        const val KEY_SOCKETS = "sockets"
        const val KEY_MIN_POWER = "min_power"
    }
}
