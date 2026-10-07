package com.qtekfun.mapas.settings

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.mapas.core.voice.NavSettings
import com.qtekfun.mapas.core.voice.NavSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [NavSettingsStore] over SharedPreferences (`mapas_nav`). Every value is normalized on the way in and out, and an
 * unknown or damaged value falls back to its default, so a hand-edited file cannot break navigation. Voice on,
 * metric/imperial by region, nothing avoided by default.
 */
class PrefsNavSettingsStore(private val prefs: SharedPreferences) : NavSettingsStore {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())
    override val settings: StateFlow<NavSettings> = state

    @Synchronized
    override fun update(transform: (NavSettings) -> NavSettings) {
        val next = transform(state.value).normalized()
        if (next == state.value) return
        prefs.edit()
            .putBoolean(KEY_VOICE, next.voiceEnabled)
            .putBoolean(KEY_IMPORTANT, next.importantOnly)
            .putInt(KEY_VOLUME, next.volumePercent)
            .putString(KEY_UNITS, next.units.name)
            .putString(KEY_LANGUAGE, next.voiceLanguage.name)
            .putBoolean(KEY_AVOID_MOTORWAYS, next.avoidMotorways)
            .putBoolean(KEY_AVOID_TOLLS, next.avoidTolls)
            .putBoolean(KEY_AVOID_FERRIES, next.avoidFerries)
            .putBoolean(KEY_AVOID_UNPAVED, next.avoidUnpaved)
            .putBoolean(KEY_VIEW_3D, next.view3d)
            .putBoolean(KEY_BUILDINGS_3D, next.buildings3d)
            .apply()
        state.value = next
    }

    private fun read(): NavSettings {
        val d = NavSettings()
        return NavSettings(
            voiceEnabled = bool(KEY_VOICE, d.voiceEnabled),
            importantOnly = bool(KEY_IMPORTANT, d.importantOnly),
            volumePercent = runCatching { prefs.getInt(KEY_VOLUME, d.volumePercent) }.getOrDefault(d.volumePercent),
            units = enumValue(KEY_UNITS, d.units),
            voiceLanguage = enumValue(KEY_LANGUAGE, d.voiceLanguage),
            avoidMotorways = bool(KEY_AVOID_MOTORWAYS, d.avoidMotorways),
            avoidTolls = bool(KEY_AVOID_TOLLS, d.avoidTolls),
            avoidFerries = bool(KEY_AVOID_FERRIES, d.avoidFerries),
            avoidUnpaved = bool(KEY_AVOID_UNPAVED, d.avoidUnpaved),
            view3d = bool(KEY_VIEW_3D, d.view3d),
            buildings3d = bool(KEY_BUILDINGS_3D, d.buildings3d),
        ).normalized()
    }

    private fun bool(key: String, default: Boolean) = runCatching { prefs.getBoolean(key, default) }.getOrDefault(default)

    private inline fun <reified E : Enum<E>> enumValue(key: String, default: E): E =
        runCatching { prefs.getString(key, null) }.getOrNull()?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    companion object {
        const val PREFS = "mapas_nav"
        const val KEY_VOICE = "voice_enabled"
        const val KEY_IMPORTANT = "voice_important_only"
        const val KEY_VOLUME = "voice_volume"
        const val KEY_UNITS = "units"
        const val KEY_LANGUAGE = "voice_language"
        const val KEY_AVOID_MOTORWAYS = "avoid_motorways"
        const val KEY_AVOID_TOLLS = "avoid_tolls"
        const val KEY_AVOID_FERRIES = "avoid_ferries"
        const val KEY_AVOID_UNPAVED = "avoid_unpaved"
        const val KEY_VIEW_3D = "view_3d"
        const val KEY_BUILDINGS_3D = "buildings_3d"
    }
}
