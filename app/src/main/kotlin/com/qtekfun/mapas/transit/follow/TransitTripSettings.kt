package com.qtekfun.mapas.transit.follow

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.mapas.core.cameras.AlertSoundMode
import com.qtekfun.mapas.settings.backup.SettingsReloadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the step-by-step transit trip announces boarding, changes and getting off. The on-screen banner always shows. */
interface TransitTripSettingsStore {
    val promptMode: StateFlow<AlertSoundMode>
    fun setPromptMode(mode: AlertSoundMode)
}

class InMemoryTransitTripSettings(initial: AlertSoundMode = AlertSoundMode.VOICE) : TransitTripSettingsStore {
    private val state = MutableStateFlow(initial)
    override val promptMode: StateFlow<AlertSoundMode> = state
    override fun setPromptMode(mode: AlertSoundMode) {
        state.value = mode
    }
}

/** [TransitTripSettingsStore] over SharedPreferences (`mapas_transit_trip`). A damaged value falls back to the default. */
class PrefsTransitTripSettings(private val prefs: SharedPreferences) : TransitTripSettingsStore, SettingsReloadable {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())
    override val promptMode: StateFlow<AlertSoundMode> = state

    @Synchronized
    override fun setPromptMode(mode: AlertSoundMode) {
        if (mode == state.value) return
        prefs.edit().putString(KEY_PROMPTS, mode.name).apply()
        state.value = mode
    }

    /** Re-reads the file (after a settings restore wrote to it behind this store's back). */
    @Synchronized
    override fun reload() {
        state.value = read()
    }

    private fun read(): AlertSoundMode =
        AlertSoundMode.entries.firstOrNull { it.name == prefs.getString(KEY_PROMPTS, null) } ?: DEFAULT

    companion object {
        const val PREFS = "mapas_transit_trip"
        const val KEY_PROMPTS = "prompts"
        val DEFAULT = AlertSoundMode.VOICE
    }
}
