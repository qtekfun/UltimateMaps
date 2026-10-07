package com.qtekfun.ultimatemaps.cameras

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import com.qtekfun.ultimatemaps.core.cameras.CameraSettings
import com.qtekfun.ultimatemaps.core.cameras.CameraSettingsStore
import com.qtekfun.ultimatemaps.core.cameras.normalized
import com.qtekfun.ultimatemaps.settings.backup.SettingsReloadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [CameraSettingsStore] over SharedPreferences (`mapas_cameras`). Every value is normalized on the way in and out, so a
 * damaged or hand-edited file can never turn a camera layer on without the acknowledgement. Everything is off by default.
 */
class PrefsCameraSettingsStore(private val prefs: SharedPreferences) : CameraSettingsStore, SettingsReloadable {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())
    override val settings: StateFlow<CameraSettings> = state

    /** Re-reads the file (after a settings restore wrote to it behind this store's back). */
    @Synchronized
    override fun reload() {
        state.value = read()
    }

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
            .putString(KEY_CAM_MODE, next.cameraAlertMode.name)
            .putString(KEY_INCIDENT_MODE, next.incidentAlertMode.name)
            .putBoolean(KEY_MUTED, next.alertsMuted)
            .putInt(KEY_REFRESH, next.incidentRefreshMinutes)
            .apply()
        state.value = next
    }

    private fun read(): CameraSettings {
        val d = CameraSettings()
        fun bool(key: String, def: Boolean) = runCatching { prefs.getBoolean(key, def) }.getOrDefault(def)
        // Migration: before the alert modes there was one shared voice flag. Read only (it is never written any more):
        // true meant alerts on (it was the default, so most people have it), false meant none: on becomes the new default, a chime. Without it, or when a mode key holds nothing valid, the default applies.
        val legacy = runCatching { if (prefs.contains(KEY_VOICE_LEGACY)) prefs.getBoolean(KEY_VOICE_LEGACY, true) else null }.getOrNull()
            ?.let { if (it) AlertSoundMode.SOUND else AlertSoundMode.SILENT }
        fun mode(key: String, fallback: AlertSoundMode?): AlertSoundMode =
            runCatching { prefs.getString(key, null) }.getOrNull()?.let { n -> AlertSoundMode.entries.firstOrNull { it.name == n } }
                ?: fallback ?: d.cameraAlertMode
        return CameraSettings(
            fixedEnabled = bool(KEY_FIXED, d.fixedEnabled),
            mobileZonesEnabled = bool(KEY_MOBILE, d.mobileZonesEnabled),
            incidentsEnabled = bool(KEY_INCIDENTS, d.incidentsEnabled),
            v16Enabled = bool(KEY_V16, d.v16Enabled),
            roadworksEnabled = bool(KEY_ROADWORKS, d.roadworksEnabled),
            warnOnlyIfSpeeding = bool(KEY_ONLY_SPEEDING, d.warnOnlyIfSpeeding),
            acknowledged = bool(KEY_ACK, d.acknowledged),
            cameraAlertMode = mode(KEY_CAM_MODE, legacy),
            incidentAlertMode = mode(KEY_INCIDENT_MODE, legacy),
            alertsMuted = bool(KEY_MUTED, d.alertsMuted),
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
        const val KEY_CAM_MODE = "cam_alert_mode"
        const val KEY_INCIDENT_MODE = "incident_alert_mode"
        const val KEY_MUTED = "alerts_muted"

        /** The old shared voice flag; only read, to migrate it (true: VOICE, false: SILENT, for both categories). */
        const val KEY_VOICE_LEGACY = "voice_alerts"
        const val KEY_REFRESH = "incident_refresh_minutes"
    }
}
