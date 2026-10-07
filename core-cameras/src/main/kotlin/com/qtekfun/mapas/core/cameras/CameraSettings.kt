package com.qtekfun.mapas.core.cameras

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Smallest interval between downloads of the national incident feed: the DGT refreshes it every minute but it weighs ~200 KB gzipped. */
const val MIN_INCIDENT_REFRESH_MINUTES = 5

/** Intervals the settings screen offers (minutes). */
val INCIDENT_REFRESH_CHOICES_MINUTES = listOf(5, 10, 30, 60)

/**
 * What the user configures in Settings (RF-17). EVERYTHING is off by default and nothing is downloaded while the
 * switches are off.
 *
 * - [fixedEnabled]: fixed speed cameras and average-speed sections (static file from the data release).
 * - [mobileZonesEnabled]: roads where the DGT says mobile radars MAY operate (same file; zones, never points).
 * - [incidentsEnabled]: accidents, closures, slow traffic, obstacles, bad weather (live feed, explicit opt-in).
 * - [v16Enabled]: stopped vehicles with a connected V16 beacon (same live feed).
 * - [roadworksEnabled]: also show roadworks (only with [incidentsEnabled]); there can be hundreds.
 * - [cameraAlertMode] / [incidentAlertMode]: how each category is announced (chime, voice or only the visual chip).
 * - [alertsMuted]: the quick mute of the navigation screen; silences both categories without changing their modes.
 * - [acknowledged]: the user read and accepted the notice about camera data (required for the two camera switches).
 */
data class CameraSettings(
    val fixedEnabled: Boolean = false,
    val mobileZonesEnabled: Boolean = false,
    val incidentsEnabled: Boolean = false,
    val v16Enabled: Boolean = false,
    val roadworksEnabled: Boolean = false,
    /** Warn about a camera only when the known limit is exceeded (cameras with an unknown limit always warn). */
    val warnOnlyIfSpeeding: Boolean = false,
    val acknowledged: Boolean = false,
    /** How speed cameras, sections and mobile zones are announced; the chip shows in every mode. A chime by default. */
    val cameraAlertMode: AlertSoundMode = AlertSoundMode.SOUND,
    /** How incidents (V16, roadworks, weather included) are announced; the chip or banner shows in every mode. */
    val incidentAlertMode: AlertSoundMode = AlertSoundMode.SOUND,
    /**
     * The quick mute (navigation screen button): both categories stay silent while it is on, the chosen modes are kept
     * and take effect again when it is turned off. Independent of the navigation Mute, which still silences everything.
     */
    val alertsMuted: Boolean = false,
    val incidentRefreshMinutes: Int = 10,
) {
    val anyCamera: Boolean get() = fixedEnabled || mobileZonesEnabled
    val anyIncident: Boolean get() = incidentsEnabled || v16Enabled
    val anything: Boolean get() = anyCamera || anyIncident

    /** The mode of the category [c] belongs to. */
    fun modeFor(c: AlertCategory): AlertSoundMode = if (c.isCamera) cameraAlertMode else incidentAlertMode

    /** The incident kinds the map shows and the warner may announce. */
    fun incidentKinds(): Set<IncidentKind> = buildSet {
        if (v16Enabled) add(IncidentKind.V16)
        if (incidentsEnabled) {
            addAll(listOf(IncidentKind.ACCIDENT, IncidentKind.CLOSURE, IncidentKind.CONGESTION, IncidentKind.OBSTACLE, IncidentKind.WEATHER))
            if (roadworksEnabled) add(IncidentKind.ROADWORKS)
        }
    }
}

/**
 * Makes settings consistent: camera switches need the acknowledgement, roadworks need incidents, and the interval is
 * at least [MIN_INCIDENT_REFRESH_MINUTES].
 */
fun CameraSettings.normalized(): CameraSettings = copy(
    fixedEnabled = fixedEnabled && acknowledged,
    mobileZonesEnabled = mobileZonesEnabled && acknowledged,
    roadworksEnabled = roadworksEnabled && incidentsEnabled,
    incidentRefreshMinutes = incidentRefreshMinutes.coerceAtLeast(MIN_INCIDENT_REFRESH_MINUTES),
)

interface CameraSettingsStore {
    val settings: StateFlow<CameraSettings>
    fun update(transform: (CameraSettings) -> CameraSettings)
}

/** In-memory store for tests and previews. Always keeps the settings [normalized]. */
class InMemoryCameraSettingsStore(initial: CameraSettings = CameraSettings()) : CameraSettingsStore {
    private val state = MutableStateFlow(initial.normalized())
    override val settings: StateFlow<CameraSettings> = state
    override fun update(transform: (CameraSettings) -> CameraSettings) {
        state.value = transform(state.value).normalized()
    }
}
