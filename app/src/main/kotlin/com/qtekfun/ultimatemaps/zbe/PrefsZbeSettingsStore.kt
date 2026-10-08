package com.qtekfun.ultimatemaps.zbe

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import com.qtekfun.ultimatemaps.core.zbe.ZbeSettings
import com.qtekfun.ultimatemaps.core.zbe.ZbeSettingsStore
import com.qtekfun.ultimatemaps.settings.backup.SettingsReloadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** [ZbeSettingsStore] over SharedPreferences (`mapas_zbe`). Off by default. */
class PrefsZbeSettingsStore(private val prefs: SharedPreferences) : ZbeSettingsStore, SettingsReloadable {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())
    override val settings: StateFlow<ZbeSettings> = state

    /** Re-reads the file (after a settings restore wrote to it behind this store's back). */
    @Synchronized
    override fun reload() {
        state.value = read()
    }

    @Synchronized
    override fun update(transform: (ZbeSettings) -> ZbeSettings) {
        val next = transform(state.value)
        if (next == state.value) return
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putBoolean(KEY_SHOW_ON_MAP, next.showOnMap)
            .putString(KEY_PROMPT_MODE, next.promptMode.name)
            .apply()
        state.value = next
    }

    private fun read(): ZbeSettings {
        val d = ZbeSettings()
        return ZbeSettings(
            enabled = runCatching { prefs.getBoolean(KEY_ENABLED, d.enabled) }.getOrDefault(d.enabled),
            showOnMap = runCatching { prefs.getBoolean(KEY_SHOW_ON_MAP, d.showOnMap) }.getOrDefault(d.showOnMap),
            promptMode = runCatching { prefs.getString(KEY_PROMPT_MODE, null) }.getOrNull()
                ?.let { n -> AlertSoundMode.entries.firstOrNull { it.name == n } } ?: d.promptMode,
        )
    }

    companion object {
        const val PREFS = "mapas_zbe"
        const val KEY_ENABLED = "enabled"
        const val KEY_SHOW_ON_MAP = "show_on_map"
        const val KEY_PROMPT_MODE = "prompt_mode"
    }
}
