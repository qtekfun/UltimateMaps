package com.qtekfun.mapas.cameras

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.mapas.core.cameras.CameraSettings
import com.qtekfun.mapas.core.cameras.CameraSettingsStore
import com.qtekfun.mapas.core.cameras.normalized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [CameraSettingsStore] over SharedPreferences (`mapas_cameras`). Every value is normalized on the way in and out, so a
 * damaged or hand-edited file can never turn a camera layer on without the acknowledgement. Everything is off by default.
 */
class PrefsCameraSettingsStore(private val prefs: SharedPreferences) : CameraSettingsStore {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())
    override val settings: StateFlow<CameraSettings> = state

    @Synchronized
    override fun update(transform: (CameraSettings) -> CameraSettings) {
        val next = transform(state.value).normalized()
        if (next == state.value) return
        prefs.edit()
            .putBoolean(KEY_FIXED, next.fixedEnabled)
            .putBoolean(KEY_MOBILE, next.mobileZonesEnabled)
            .putBoolean(KEY_INCIDENTS, next.incidentsEnabled)
            .putBoolean(KEY_V16, next.v16Enabled)
            .putBoolean(KEY_ROADWORKS, next.roadworksEnabled)
            .putBoolean(KEY_ONLY_SPEEDING, next.warnOnlyIfSpeeding)
            .putBoolean(KEY_ACK, next.acknowledged)
            .putBoolean(KEY_VOICE, next.voiceEnabled)
            .putInt(KEY_REFRESH, next.incidentRefreshMinutes)
            .apply()
        state.value = next
    }

    private fun read(): CameraSettings {
        val d = CameraSettings()
        fun bool(key: String, def: Boolean) = runCatching { prefs.getBoolean(key, def) }.getOrDefault(def)
        return CameraSettings(
            fixedEnabled = bool(KEY_FIXED, d.fixedEnabled),
            mobileZonesEnabled = bool(KEY_MOBILE, d.mobileZonesEnabled),
            incidentsEnabled = bool(KEY_INCIDENTS, d.incidentsEnabled),
            v16Enabled = bool(KEY_V16, d.v16Enabled),
            roadworksEnabled = bool(KEY_ROADWORKS, d.roadworksEnabled),
            warnOnlyIfSpeeding = bool(KEY_ONLY_SPEEDING, d.warnOnlyIfSpeeding),
            acknowledged = bool(KEY_ACK, d.acknowledged),
            voiceEnabled = bool(KEY_VOICE, d.voiceEnabled),
            incidentRefreshMinutes = runCatching { prefs.getInt(KEY_REFRESH, d.incidentRefreshMinutes) }.getOrDefault(d.incidentRefreshMinutes),
        ).normalized()
    }

    companion object {
        const val PREFS = "mapas_cameras"
        const val KEY_FIXED = "fixed"
        const val KEY_MOBILE = "mobile_zones"
        const val KEY_INCIDENTS = "incidents"
        const val KEY_V16 = "v16"
        const val KEY_ROADWORKS = "roadworks"
        const val KEY_ONLY_SPEEDING = "only_if_speeding"
        const val KEY_ACK = "acknowledged"
        const val KEY_VOICE = "voice_alerts"
        const val KEY_REFRESH = "incident_refresh_minutes"
    }
}
