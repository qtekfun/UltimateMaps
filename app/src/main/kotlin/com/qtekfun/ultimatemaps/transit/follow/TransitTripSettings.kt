package com.qtekfun.ultimatemaps.transit.follow

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import com.qtekfun.ultimatemaps.settings.backup.SettingsReloadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the step-by-step transit trip announces boarding, changes and getting off. The on-screen banner always shows. */
interface TransitTripSettingsStore {
    val promptMode: StateFlow<AlertSoundMode>
    fun setPromptMode(mode: AlertSoundMode)

    /**
     * "Cercanias real time" (opt-in, off by default): while on, the app asks Renfe's server for train delays while the user
     * uses public transport. Nothing is asked while it is off.
     */
    val realTimeEnabled: StateFlow<Boolean>
    fun setRealTimeEnabled(on: Boolean)
}

class InMemoryTransitTripSettings(initial: AlertSoundMode = AlertSoundMode.VOICE, realTime: Boolean = false) : TransitTripSettingsStore {
    private val state = MutableStateFlow(initial)
    private val rt = MutableStateFlow(realTime)
    override val promptMode: StateFlow<AlertSoundMode> = state
    override fun setPromptMode(mode: AlertSoundMode) {
        state.value = mode
    }

    override val realTimeEnabled: StateFlow<Boolean> = rt
    override fun setRealTimeEnabled(on: Boolean) {
        rt.value = on
    }
}

/** [TransitTripSettingsStore] over SharedPreferences (`mapas_transit_trip`). A damaged value falls back to the default. */
class PrefsTransitTripSettings(private val prefs: SharedPreferences) : TransitTripSettingsStore, SettingsReloadable {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())
    override val promptMode: StateFlow<AlertSoundMode> = state
    private val rt = MutableStateFlow(readRealTime())
    override val realTimeEnabled: StateFlow<Boolean> = rt

    @Synchronized
    override fun setRealTimeEnabled(on: Boolean) {
        if (on == rt.value) return
        prefs.edit().putBoolean(KEY_REAL_TIME, on).apply()
        rt.value = on
    }

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
        rt.value = readRealTime()
    }

    /** Only a stored boolean `true` turns it on; anything else (missing, damaged, another type) is off. */
    private fun readRealTime(): Boolean = try { prefs.getBoolean(KEY_REAL_TIME, false) } catch (_: ClassCastException) { false }

    private fun read(): AlertSoundMode =
        AlertSoundMode.entries.firstOrNull { it.name == prefs.getString(KEY_PROMPTS, null) } ?: DEFAULT

    companion object {
        const val PREFS = "mapas_transit_trip"
        const val KEY_PROMPTS = "prompts"
        const val KEY_REAL_TIME = "cercanias_real_time"
        val DEFAULT = AlertSoundMode.VOICE
    }
}
