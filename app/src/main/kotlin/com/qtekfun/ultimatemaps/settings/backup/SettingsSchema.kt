package com.qtekfun.ultimatemaps.settings.backup

import com.qtekfun.ultimatemaps.cameras.PrefsCameraSettingsStore
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import com.qtekfun.ultimatemaps.core.cameras.CameraSettings
import com.qtekfun.ultimatemaps.core.cameras.MIN_INCIDENT_REFRESH_MINUTES
import com.qtekfun.ultimatemaps.chargers.PrefsChargerSettingsStore
import com.qtekfun.ultimatemaps.zbe.PrefsZbeSettingsStore
import com.qtekfun.ultimatemaps.weather.KeystoreApiKeyStore
import com.qtekfun.ultimatemaps.weather.PrefsWeatherAlertSettings
import com.qtekfun.ultimatemaps.core.weather.WeatherAlertSettings
import com.qtekfun.ultimatemaps.bikeshare.PrefsBikeShareSettingsStore
import com.qtekfun.ultimatemaps.core.bikeshare.BikeShareSettings
import com.qtekfun.ultimatemaps.core.zbe.ZbeSettings
import com.qtekfun.ultimatemaps.core.chargers.ChargerSettings
import com.qtekfun.ultimatemaps.core.chargers.MinPower
import com.qtekfun.ultimatemaps.core.chargers.SocketType
import com.qtekfun.ultimatemaps.core.routes.RouteSettings
import com.qtekfun.ultimatemaps.trails.PrefsRouteSettingsStore
import com.qtekfun.ultimatemaps.core.fuel.FuelSettings
import com.qtekfun.ultimatemaps.core.fuel.FuelTypes
import com.qtekfun.ultimatemaps.core.fuel.MIN_REFRESH_MINUTES
import com.qtekfun.ultimatemaps.core.routing.BikeCycleways
import com.qtekfun.ultimatemaps.core.search.PlaceLanguagePref
import com.qtekfun.ultimatemaps.core.voice.NavSettings
import com.qtekfun.ultimatemaps.core.voice.UnitsPref
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguagePref
import com.qtekfun.ultimatemaps.fuel.PrefsFuelSettingsStore
import com.qtekfun.ultimatemaps.nav.SharedNavUiPrefs
import com.qtekfun.ultimatemaps.recording.PrefsRecordingSettings
import com.qtekfun.ultimatemaps.regions.RegionsController
import com.qtekfun.ultimatemaps.search.PrefsHistorySettings
import com.qtekfun.ultimatemaps.search.PrefsPlaceLanguageStore
import com.qtekfun.ultimatemaps.settings.PrefsNavSettingsStore
import com.qtekfun.ultimatemaps.core.transit.TransitMode
import com.qtekfun.ultimatemaps.transit.follow.PrefsTransitTripSettings
import com.qtekfun.ultimatemaps.transit.follow.TransitPlanningDefaults

/** The value types a settings file can carry. [json] is the explicit type name written next to every value. */
enum class SettingType(val json: String) {
    BOOLEAN("bool"), INT("int"), STRING("string"), STRING_SET("string_set");

    fun matches(value: Any): Boolean = when (this) {
        BOOLEAN -> value is Boolean
        INT -> value is Int
        STRING -> value is String
        STRING_SET -> value is Set<*> && value.all { it is String }
    }

    companion object {
        fun ofJson(name: String): SettingType? = entries.firstOrNull { it.json == name }
    }
}

/** How a value is restored. */
enum class RestorePolicy {
    /** Written as it is. */
    DIRECT,

    /**
     * Turning it on needs the owner's acceptance on this phone (a legal notice or a network connection): an "on" value
     * is never written, it is remembered as an intent ([PendingRestore]) and shown for the owner to accept again.
     * An "off" value is written, because switching something off never needs consent.
     */
    NEEDS_CONSENT,
}

/**
 * One exported setting. [group] is the stable name used in the file; [prefsName] and [key] locate it on the device.
 * [sanitize] returns the value to restore, or null when the value is not acceptable (it is then skipped and counted).
 */
class SettingSpec(
    val group: String,
    val prefsName: String,
    val key: String,
    val type: SettingType,
    val default: Any,
    val policy: RestorePolicy = RestorePolicy.DIRECT,
    val sanitize: (Any) -> Any? = { it },
) {
    init {
        require(type.matches(default)) { "default of $group/$key does not match $type" }
    }

    val id: String get() = "$group/$key"
}

/**
 * The whitelist of settings that go into a settings file. ONLY these keys are ever exported or restored; anything else
 * in a file is ignored. A new setting is added here on purpose; `SettingsSchemaCoverageTest` fails when a preference
 * key exists that is neither here nor in [excluded].
 */
object SettingsSchema {
    /** Version of the file format. Bump it only when an old reader could not understand a new file. */
    const val VERSION = 1

    const val GROUP_NAVIGATION = "navigation"
    const val GROUP_NAVIGATION_UI = "navigation_ui"
    const val GROUP_FUEL = "fuel"
    const val GROUP_CAMERAS = "cameras"
    const val GROUP_CHARGERS = "chargers"
    const val GROUP_ZBE = "zbe"
    const val GROUP_BIKESHARE = "bikeshare"
    const val GROUP_WEATHER = "weather"
    const val GROUP_TRAILS = "trails"
    const val GROUP_HISTORY = "history"
    const val GROUP_RECORDING = "recording"
    const val GROUP_REGIONS = "regions"
    const val GROUP_PLACE_LANGUAGE = "place_language"

    private fun bool(group: String, prefs: String, key: String, default: Boolean, policy: RestorePolicy = RestorePolicy.DIRECT) =
        SettingSpec(group, prefs, key, SettingType.BOOLEAN, default, policy)

    private fun int(group: String, prefs: String, key: String, default: Int, accepts: (Int) -> Boolean) =
        SettingSpec(group, prefs, key, SettingType.INT, default, sanitize = { v -> (v as Int).takeIf(accepts) })

    private fun <E : Enum<E>> enum(group: String, prefs: String, key: String, default: E, names: List<String>) =
        SettingSpec(group, prefs, key, SettingType.STRING, default.name, sanitize = { v -> (v as String).takeIf { it in names } })

    /** https URL with a host, or empty when [allowEmpty]. */
    private fun url(group: String, prefs: String, key: String, default: String, allowEmpty: Boolean) =
        SettingSpec(group, prefs, key, SettingType.STRING, default, sanitize = { v ->
            (v as String).trim().takeIf { (allowEmpty && it.isEmpty()) || isHttpsUrl(it) }
        })

    private fun isHttpsUrl(text: String): Boolean = runCatching {
        val u = java.net.URI(text)
        u.scheme.equals("https", ignoreCase = true) && !u.host.isNullOrEmpty()
    }.getOrDefault(false)

    private val nav = NavSettings()
    private val fuel = FuelSettings()
    private val cameras = CameraSettings()
    private val chargers = ChargerSettings()
    private val zbe = ZbeSettings()
    private val bikeShare = BikeShareSettings()
    private val trails = RouteSettings()
    private val weather = WeatherAlertSettings()

    private val navPrefs = PrefsNavSettingsStore.PREFS
    private val fuelPrefs = PrefsFuelSettingsStore.PREFS
    private val camPrefs = PrefsCameraSettingsStore.PREFS
    private val chargerPrefs = PrefsChargerSettingsStore.PREFS
    private val zbePrefs = PrefsZbeSettingsStore.PREFS
    private val bikePrefs = PrefsBikeShareSettingsStore.PREFS
    private val trailPrefs = PrefsRouteSettingsStore.PREFS

    /** Every exported setting, sorted by group and key (the order of the file). */
    val specs: List<SettingSpec> = listOf(
        // Navigation: voice, units, route defaults, 3D.
        bool(GROUP_NAVIGATION, navPrefs, PrefsNavSettingsStore.KEY_VOICE, nav.voiceEnabled),
        bool(GROUP_NAVIGATION, navPrefs, PrefsNavSettingsStore.KEY_IMPORTANT, nav.importantOnly),
        int(GROUP_NAVIGATION, navPrefs, PrefsNavSettingsStore.KEY_VOLUME, nav.volumePercent) { it in NavSettings.MIN_VOLUME..100 },
        enum(GROUP_NAVIGATION, navPrefs, PrefsNavSettingsStore.KEY_UNITS, nav.units, UnitsPref.entries.map { it.name }),
        enum(GROUP_NAVIGATION, navPrefs, PrefsNavSettingsStore.KEY_LANGUAGE, nav.voiceLanguage, VoiceLanguagePref.entries.map { it.name }),
        bool(GROUP_NAVIGATION, navPrefs, PrefsNavSettingsStore.KEY_AVOID_MOTORWAYS, nav.avoidMotorways),
        bool(GROUP_NAVIGATION, navPrefs, PrefsNavSettingsStore.KEY_AVOID_TOLLS, nav.avoidTolls),
        bool(GROUP_NAVIGATION, navPrefs, PrefsNavSettingsStore.KEY_AVOID_FERRIES, nav.avoidFerries),
        bool(GROUP_NAVIGATION, navPrefs, PrefsNavSettingsStore.KEY_AVOID_UNPAVED, nav.avoidUnpaved),
        enum(GROUP_NAVIGATION, navPrefs, PrefsNavSettingsStore.KEY_BIKE_CYCLEWAYS, nav.bikeCycleways, BikeCycleways.entries.map { it.name }),
        bool(GROUP_NAVIGATION, navPrefs, PrefsNavSettingsStore.KEY_VIEW_3D, nav.view3d),
        bool(GROUP_NAVIGATION, navPrefs, PrefsNavSettingsStore.KEY_BUILDINGS_3D, nav.buildings3d),
        bool(GROUP_NAVIGATION, navPrefs, PrefsNavSettingsStore.KEY_LIVE_UPDATE_CHIP, nav.liveUpdateChip),
        bool(GROUP_NAVIGATION, navPrefs, PrefsNavSettingsStore.KEY_MOTION_TUNNELS, nav.motionSensorsInTunnels),
        bool(GROUP_NAVIGATION, navPrefs, PrefsNavSettingsStore.KEY_HIDE_STATUS_BAR, nav.hideStatusBar),
        bool(GROUP_NAVIGATION_UI, SharedNavUiPrefs.PREFS, SharedNavUiPrefs.KEY_GLOVE, false),
        // Public-transport trip prompts (sound, voice or silent).
        enum(GROUP_NAVIGATION, PrefsTransitTripSettings.PREFS, PrefsTransitTripSettings.KEY_PROMPTS, PrefsTransitTripSettings.DEFAULT, AlertSoundMode.entries.map { it.name }),
        // Cercanias real time: the switch makes the app contact Renfe's server, so it needs consent like the other online switches.
        bool(GROUP_NAVIGATION, PrefsTransitTripSettings.PREFS, PrefsTransitTripSettings.KEY_REAL_TIME, false, RestorePolicy.NEEDS_CONSENT),
        // Public-transport planner: allowed modes (the chips of the route panel) and the walking limits.
        SettingSpec(
            GROUP_NAVIGATION, PrefsTransitTripSettings.PREFS, PrefsTransitTripSettings.KEY_MODES, SettingType.STRING_SET,
            TransitMode.FILTERABLE.map { it.name }.toSet(),
            sanitize = { v ->
                val offered = TransitMode.FILTERABLE.map { it.name }.toSet()
                (v as Set<*>).filterIsInstance<String>().filter { it in offered }.toSet()
            },
        ),
        int(GROUP_NAVIGATION, PrefsTransitTripSettings.PREFS, PrefsTransitTripSettings.KEY_WALK_ALT, TransitPlanningDefaults.WALK_ALTERNATIVE_MIN) { it in 0..TransitPlanningDefaults.MAX_MINUTES },
        int(GROUP_NAVIGATION, PrefsTransitTripSettings.PREFS, PrefsTransitTripSettings.KEY_MIN_SAVING, TransitPlanningDefaults.MIN_SAVING_MIN) { it in 0..TransitPlanningDefaults.MAX_MINUTES },
        int(GROUP_NAVIGATION, PrefsTransitTripSettings.PREFS, PrefsTransitTripSettings.KEY_MAX_WALK, TransitPlanningDefaults.MAX_WALK_MIN) { it in 0..TransitPlanningDefaults.MAX_MINUTES },
        int(GROUP_NAVIGATION, PrefsTransitTripSettings.PREFS, PrefsTransitTripSettings.KEY_MAX_CHANGES, TransitPlanningDefaults.CHANGES_ANY) { it in TransitPlanningDefaults.CHANGES_NONE..TransitPlanningDefaults.CHANGES_ANY },

        // Petrol stations: the switch needs consent (it starts downloads); the rest is plain preference.
        bool(GROUP_FUEL, fuelPrefs, PrefsFuelSettingsStore.KEY_ENABLED, fuel.enabled, RestorePolicy.NEEDS_CONSENT),
        SettingSpec(
            GROUP_FUEL, fuelPrefs, PrefsFuelSettingsStore.KEY_FUELS, SettingType.STRING_SET, emptySet<String>(),
            sanitize = { v -> (v as Set<*>).filterIsInstance<String>().filter { FuelTypes.byId(it) != null }.toSet() },
        ),
        SettingSpec(
            GROUP_FUEL, fuelPrefs, PrefsFuelSettingsStore.KEY_MAP_FUEL, SettingType.STRING, "",
            sanitize = { v -> (v as String).takeIf { it.isEmpty() || FuelTypes.byId(it) != null } },
        ),
        int(GROUP_FUEL, fuelPrefs, PrefsFuelSettingsStore.KEY_REFRESH, fuel.refreshMinutes) { it >= MIN_REFRESH_MINUTES },
        url(GROUP_FUEL, fuelPrefs, PrefsFuelSettingsStore.KEY_URL, fuel.sourceUrl, allowEmpty = false),

        // Cameras and incidents. The acknowledgement itself is never exported (see [excluded]).
        bool(GROUP_CAMERAS, camPrefs, PrefsCameraSettingsStore.KEY_FIXED, cameras.fixedEnabled, RestorePolicy.NEEDS_CONSENT),
        bool(GROUP_CAMERAS, camPrefs, PrefsCameraSettingsStore.KEY_MOBILE, cameras.mobileZonesEnabled, RestorePolicy.NEEDS_CONSENT),
        bool(GROUP_CAMERAS, camPrefs, PrefsCameraSettingsStore.KEY_INCIDENTS, cameras.incidentsEnabled, RestorePolicy.NEEDS_CONSENT),
        bool(GROUP_CAMERAS, camPrefs, PrefsCameraSettingsStore.KEY_V16, cameras.v16Enabled, RestorePolicy.NEEDS_CONSENT),
        bool(GROUP_CAMERAS, camPrefs, PrefsCameraSettingsStore.KEY_ROADWORKS, cameras.roadworksEnabled, RestorePolicy.NEEDS_CONSENT),
        bool(GROUP_CAMERAS, camPrefs, PrefsCameraSettingsStore.KEY_ONLY_SPEEDING, cameras.warnOnlyIfSpeeding),
        enum(GROUP_CAMERAS, camPrefs, PrefsCameraSettingsStore.KEY_CAM_MODE, cameras.cameraAlertMode, AlertSoundMode.entries.map { it.name }),
        enum(GROUP_CAMERAS, camPrefs, PrefsCameraSettingsStore.KEY_INCIDENT_MODE, cameras.incidentAlertMode, AlertSoundMode.entries.map { it.name }),
        int(GROUP_CAMERAS, camPrefs, PrefsCameraSettingsStore.KEY_REFRESH, cameras.incidentRefreshMinutes) { it >= MIN_INCIDENT_REFRESH_MINUTES },

        // EV chargers: the switch starts a download, so it needs consent; the plug and power filters are plain preferences.
        bool(GROUP_CHARGERS, chargerPrefs, PrefsChargerSettingsStore.KEY_ENABLED, chargers.enabled, RestorePolicy.NEEDS_CONSENT),
        SettingSpec(
            GROUP_CHARGERS, chargerPrefs, PrefsChargerSettingsStore.KEY_SOCKETS, SettingType.STRING_SET, chargers.sockets.map { it.name }.toSet(),
            sanitize = { v ->
                val offered = SocketType.FILTERABLE.map { it.name }.toSet()
                (v as Set<*>).filterIsInstance<String>().filter { it in offered }.toSet().takeIf { it.isNotEmpty() }
            },
        ),
        enum(GROUP_CHARGERS, chargerPrefs, PrefsChargerSettingsStore.KEY_MIN_POWER, chargers.minPower, MinPower.entries.map { it.name }),

        // Low-emission zones: the switch starts a download, so it needs consent; the map toggle and the prompt mode are plain preferences.
        bool(GROUP_ZBE, zbePrefs, PrefsZbeSettingsStore.KEY_ENABLED, zbe.enabled, RestorePolicy.NEEDS_CONSENT),
        bool(GROUP_ZBE, zbePrefs, PrefsZbeSettingsStore.KEY_SHOW_ON_MAP, zbe.showOnMap),
        enum(GROUP_ZBE, zbePrefs, PrefsZbeSettingsStore.KEY_PROMPT_MODE, zbe.promptMode, AlertSoundMode.entries.map { it.name }),
        // Bike-share stations: the switch starts a download and the live switch contacts a third-party host, so both need consent.
        bool(GROUP_BIKESHARE, bikePrefs, PrefsBikeShareSettingsStore.KEY_ENABLED, bikeShare.enabled, RestorePolicy.NEEDS_CONSENT),
        bool(GROUP_BIKESHARE, bikePrefs, PrefsBikeShareSettingsStore.KEY_LIVE_AVAILABILITY, bikeShare.liveAvailability, RestorePolicy.NEEDS_CONSENT),
        // Weather alerts (AEMET): the switch contacts a third-party server, so it needs consent. The API key is NEVER exported (see [excluded]).
        bool(GROUP_WEATHER, PrefsWeatherAlertSettings.PREFS, PrefsWeatherAlertSettings.KEY_ENABLED, weather.enabled, RestorePolicy.NEEDS_CONSENT),
        bool(GROUP_WEATHER, PrefsWeatherAlertSettings.PREFS, PrefsWeatherAlertSettings.KEY_SHOW_YELLOW, weather.showYellow),
        // Hiking and cycling routes: the switch starts a download of several MB, so it needs consent; the kinds are plain preferences.
        bool(GROUP_TRAILS, trailPrefs, PrefsRouteSettingsStore.KEY_ENABLED, trails.enabled, RestorePolicy.NEEDS_CONSENT),
        bool(GROUP_TRAILS, trailPrefs, PrefsRouteSettingsStore.KEY_HIKING, trails.hiking),
        bool(GROUP_TRAILS, trailPrefs, PrefsRouteSettingsStore.KEY_CYCLING, trails.cycling),

        // Search history switch (not the history itself).
        bool(GROUP_HISTORY, PrefsHistorySettings.PREFS, PrefsHistorySettings.KEY_ENABLED, true),

        // Language of place information (names, address, categories) in search results and the place card.
        enum(
            GROUP_PLACE_LANGUAGE, PrefsPlaceLanguageStore.PREFS, PrefsPlaceLanguageStore.KEY_PREFERENCE, PlaceLanguagePref.AUTO,
            PlaceLanguagePref.entries.map { it.name },
        ),

        // Track recording switch (not the tracks).
        bool(GROUP_RECORDING, PrefsRecordingSettings.PREFS, PrefsRecordingSettings.KEY_ENABLED, false),

        // Privacy: offline mode and the region catalog address.
        bool(GROUP_REGIONS, RegionsController.PREFS, RegionsController.KEY_OFFLINE, false),
        url(GROUP_REGIONS, RegionsController.PREFS, RegionsController.KEY_URL, RegionsController.DEFAULT_CATALOG_URL, allowEmpty = true),
    ).sortedWith(compareBy({ it.group }, { it.key }))

    private val byGroupAndKey: Map<Pair<String, String>, SettingSpec> = specs.associateBy { it.group to it.key }

    init {
        require(byGroupAndKey.size == specs.size) { "duplicate setting in the schema" }
    }

    fun find(group: String, key: String): SettingSpec? = byGroupAndKey[group to key]

    fun byId(id: String): SettingSpec? = specs.firstOrNull { it.id == id }

    /**
     * Preference keys that exist on the device and are deliberately NOT exported, as `prefsName/key` to the reason.
     * Everything here is private, device-specific, or must be accepted again by the owner.
     */
    val excluded: Map<String, String> = mapOf(
        "${PrefsCameraSettingsStore.PREFS}/${PrefsCameraSettingsStore.KEY_MUTED}" to
            "transient quick mute of the navigation screen: restoring it on a new phone would leave the alerts silent with no obvious reason",
        "${PrefsCameraSettingsStore.PREFS}/${PrefsCameraSettingsStore.KEY_VOICE_LEGACY}" to
            "old shared voice flag, only read to migrate to the per-category alert modes (which are exported)",
        "${PrefsCameraSettingsStore.PREFS}/${PrefsCameraSettingsStore.KEY_ACK}" to
            "consent: the camera notice must be accepted again on the new phone",
        "${RegionsController.PREFS}/install_location" to "device-specific storage id (card or internal)",
        "camera/lat" to "a position (the last map camera)",
        "camera/lon" to "a position (the last map camera)",
        "camera/zoom" to "tied to the last map camera position",
        "camera/bearing" to "tied to the last map camera position",
        "camera/tilt" to "tied to the last map camera position",
        "places/default_list_id" to "row id of the local database; the places backup carries the lists themselves",
        "core/isolated" to "debug valve of the native core, not a user setting",
        "${KeystoreApiKeyStore.PREFS}/${KeystoreApiKeyStore.KEY_API_KEY}" to
            "the user's AEMET API key (a credential that identifies them to AEMET): encrypted with a Keystore key that cannot leave the phone, never exported, logged or backed up",
        "${PrefsPendingRestore.PREFS}/${PrefsPendingRestore.KEY_CONSENT}" to "derived by an import, never exported",
        "${PrefsPendingRestore.PREFS}/${PrefsPendingRestore.KEY_REGIONS}" to "derived by an import, never exported",
    )
}
