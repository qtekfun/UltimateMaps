package com.qtekfun.ultimatemaps

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.io.File
import com.qtekfun.ultimatemaps.core.nav.NavStateStore
import com.qtekfun.ultimatemaps.core.nav.NavigationController
import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
import com.qtekfun.ultimatemaps.core.fuel.FuelCache
import com.qtekfun.ultimatemaps.core.fuel.FuelClient
import com.qtekfun.ultimatemaps.core.fuel.FuelDataManager
import com.qtekfun.ultimatemaps.core.fuel.FuelSettingsStore
import com.qtekfun.ultimatemaps.fuel.PrefsFuelSettingsStore
import com.qtekfun.ultimatemaps.cameras.AlertNavSink
import com.qtekfun.ultimatemaps.cameras.CameraAlerts
import com.qtekfun.ultimatemaps.cameras.PrefsCameraSettingsStore
import com.qtekfun.ultimatemaps.chargers.PrefsChargerSettingsStore
import com.qtekfun.ultimatemaps.core.chargers.ChargerAsset
import com.qtekfun.ultimatemaps.core.chargers.ChargerDataManager
import com.qtekfun.ultimatemaps.core.chargers.ChargerSettingsStore
import com.qtekfun.ultimatemaps.core.cameras.AlertBannerTracker
import com.qtekfun.ultimatemaps.core.cameras.AlertVoice
import com.qtekfun.ultimatemaps.core.cameras.ManeuverGuard
import com.qtekfun.ultimatemaps.core.cameras.CameraAsset
import com.qtekfun.ultimatemaps.core.cameras.CameraDataManager
import com.qtekfun.ultimatemaps.core.cameras.CameraSettingsStore
import com.qtekfun.ultimatemaps.core.cameras.IncidentBannerMachine
import com.qtekfun.ultimatemaps.core.cameras.IncidentCache
import com.qtekfun.ultimatemaps.core.cameras.IncidentDataManager
import com.qtekfun.ultimatemaps.regions.CatalogState
import com.qtekfun.ultimatemaps.voice.VoiceModule
import com.qtekfun.ultimatemaps.location.AndroidLocationSource
import com.qtekfun.ultimatemaps.nav.AndroidNavEnvironment
import com.qtekfun.ultimatemaps.nav.AndroidNavServiceControl
import com.qtekfun.ultimatemaps.nav.CoreRouteProvider
import com.qtekfun.ultimatemaps.nav.NavScreenController
import com.qtekfun.ultimatemaps.nav.NavSimulation
import com.qtekfun.ultimatemaps.nav.SharedNavUiPrefs
import com.qtekfun.ultimatemaps.nav.SimulationAwareEnvironment
import com.qtekfun.ultimatemaps.nav.SwitchableLocationSource
import com.qtekfun.ultimatemaps.regions.CoreLinks
import com.qtekfun.ultimatemaps.core.data.record.FileTrackJournal
import com.qtekfun.ultimatemaps.core.data.record.TrackRecorder
import com.qtekfun.ultimatemaps.places.openPlacesService
import com.qtekfun.ultimatemaps.recording.PrefsRecordingSettings
import com.qtekfun.ultimatemaps.recording.RecordingController
import com.qtekfun.ultimatemaps.recording.TapLocationSource
import java.text.DateFormat
import java.util.Date
import com.qtekfun.ultimatemaps.regions.RegionsController
import com.qtekfun.ultimatemaps.voice.VoiceNavSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MapasApp : Application() {
    private val policy = DefaultNetworkPolicy(
        endpoints = listOf(
            AllowedEndpoint("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES),
            AllowedEndpoint("maps.app.goo.gl", ConnectionPurpose.SHORT_LINK_RESOLVE),
            AllowedEndpoint("goo.gl", ConnectionPurpose.SHORT_LINK_RESOLVE),
        ),
    )

    /**
     * The single exit to the network. Every optional connection is listed (RF-12) but starts disabled, except
     * the map server the user typed in (and the hosts its catalog names), which is added for map downloads
     * only. The manifest requests INTERNET for that flow alone; with offline mode on, nothing connects.
     */
    val networkPolicy: NetworkPolicy = policy

    /** Region catalog, downloads and storage (the "Maps" screen and its foreground service). */
    val regions: RegionsController by lazy {
        RegionsController(this, policy, addEndpoint = policy::addEndpoint).also { it.restore() }
    }

    /** Petrol-station preferences (off by default). The map layer and the station card read these and [fuel]. */
    val fuelSettings: FuelSettingsStore by lazy { PrefsFuelSettingsStore(this) }

    /**
     * Petrol-station data: downloads (one file per configured fuel, through [networkPolicy]), local cache and in-memory
     * index ([FuelDataManager.repository]). Created lazily; nothing is downloaded when it is created or started.
     */
    val fuel: FuelDataManager by lazy {
        FuelDataManager(fuelSettings, policy, policy::addEndpoint, policy::removeEndpoint, FuelCache(File(filesDir, "fuel")), FuelClient(policy))
    }

    /** Speed-camera and traffic switches (all off by default). The map layer, the warner and Settings read these. */
    val cameraSettings: CameraSettingsStore by lazy { PrefsCameraSettingsStore(this) }

    /**
     * The static camera file (fixed cameras, average-speed sections, mobile-radar zones): fetched from the catalog's
     * `cameras` entry through [networkPolicy], cached, and absent-tolerant. Nothing is downloaded when it is created.
     */
    val cameraData: CameraDataManager by lazy {
        CameraDataManager(cameraSettings, policy, ::cameraAsset, File(filesDir, "cameras"), syncCatalog = { force -> regions.syncCatalog(force) })
    }

    /** EV-charger switch and filters (off by default). The map layer, the card and Settings read these. */
    val chargerSettings: ChargerSettingsStore by lazy { PrefsChargerSettingsStore(this) }

    /**
     * The static charger file: fetched from the catalog's `chargers` entry through [networkPolicy] (the same server and
     * purpose as the camera file), cached, and absent-tolerant. Nothing is downloaded when it is created.
     */
    val chargerData: ChargerDataManager by lazy {
        ChargerDataManager(chargerSettings, policy, ::chargerAsset, File(filesDir, "chargers"), syncCatalog = { force -> regions.syncCatalog(force) })
    }

    private fun chargerAsset(): ChargerAsset? =
        (regions.catalogState as? CatalogState.Loaded)?.catalog?.chargers?.let { ChargerAsset(it.url, it.sizeBytes, it.sha256) }

    private fun cameraAsset(): CameraAsset? =
        (regions.catalogState as? CatalogState.Loaded)?.catalog?.cameras?.let { CameraAsset(it.url, it.sizeBytes, it.sha256) }

    /**
     * Public-transport timetables (theoretical routes): per-city indexes listed in the catalog's optional `transit` block,
     * downloaded only when the user presses Download in Maps, through [networkPolicy]. Absent-tolerant.
     */
    val transit: com.qtekfun.ultimatemaps.transit.TransitRepository by lazy {
        val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "transit-io").also { it.isDaemon = true } }
        com.qtekfun.ultimatemaps.transit.TransitRepository(
            manager = com.qtekfun.ultimatemaps.core.transit.TransitDataManager(File(filesDir, "transit"), policy, { java.time.LocalDate.now() }),
            catalogAssets = { (regions.catalogState as? CatalogState.Loaded)?.catalog?.transit.orEmpty() },
            io = io,
        ).also { io.execute { it.refresh() } }
    }

    /** Live traffic incidents and V16 beacons: explicit opt-in, one national file through [networkPolicy], cached with a TTL. */
    val incidents: IncidentDataManager by lazy {
        IncidentDataManager(cameraSettings, policy, policy::addEndpoint, policy::removeEndpoint, IncidentCache(File(filesDir, "incidents")))
    }

    /** The visual alert ahead (chip on the map and the navigation screen); created with the app so the screens can observe it before any switch is on. */
    val alertBanner = AlertBannerTracker()

    /**
     * The temporary banner for incidents on the route ahead; created with the app (like [alertBanner]) so the navigation
     * screen can observe it before any switch is on. It reads the incident repository only when a position arrives.
     */
    val incidentBanner = IncidentBannerMachine({ incidents.repository }, { cameraSettings.settings.value }, System::currentTimeMillis)

    /** Alerts ahead (cameras, zones, incidents): route-based while navigating, free-driving while the app is on screen. */
    val cameraAlerts: CameraAlerts by lazy {
        val voice by lazy {
            AlertVoice(
                VoiceModule.guide(this), VoiceModule.settings(this).settings,
                maneuverImminent = { navigation.state.value?.let { ManeuverGuard.blocksVoice(it.nextManeuver?.distanceMeters, it.speedMps) } ?: false },
                modeFor = { cameraSettings.settings.value.modeFor(it) },
                alertsMuted = { cameraSettings.settings.value.alertsMuted },
                player = com.qtekfun.ultimatemaps.voice.AndroidAlertChimePlayer(this),
            )
        }
        CameraAlerts(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            settings = cameraSettings,
            cameras = cameraData.repository,
            incidents = incidents.repository,
            navigation = navigation,
            location = { AndroidLocationSource(this) },
            hasLocationPermission = {
                checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            },
            onAlert = { voice.onAlert(it) },
            banner = alertBanner,
            incidentBanner = incidentBanner,
        )
    }

    private var startedActivities = 0
    private var alertsStarted = false

    /**
     * Starts the camera/incident alerts the first time a switch is on (idempotent). Kept lazy so the cold start does not
     * build the navigation controller for users who never turn these features on. Call from the main thread.
     */
    fun ensureCameraAlerts() {
        if (alertsStarted || !cameraSettings.settings.value.anything) return
        alertsStarted = true
        cameraAlerts.start()
        cameraAlerts.onForeground(startedActivities > 0)
    }

    /** The location permission was answered or the switches changed outside the camera settings: re-evaluates the free-driving alerts. */
    fun refreshCameraAlerts() {
        if (alertsStarted) cameraAlerts.refreshFree()
    }

    /**
     * The one navigation in progress (see `docs/phase2/robustness.md`). It lives in the main process, outlives the
     * activity, and is kept running by [com.qtekfun.ultimatemaps.nav.NavigationService]. Its saved state is private and
     * expires on its own, so a process killed by the system can resume.
     */
    val navigation: NavigationController by lazy {
        NavigationController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            location = TapLocationSource(navLocation) { fix -> if (!navLocation.isSimulated) recording.onFix(fix) },
            store = NavStateStore(File(noBackupFilesDir, "navigation/state.bin")),
            environment = SimulationAwareEnvironment(AndroidNavEnvironment(this), navLocation),
            routes = CoreRouteProvider(this),
        )
    }

    /**
     * Track recording (RF-08 follow-up): fixes come from the map screen and from the navigation (which keeps running
     * in the foreground service), the points go to a private journal that is stored as a track on Stop. See
     * `docs/decisions.md`.
     */
    val recording: RecordingController by lazy {
        val places = lazy { openPlacesService(this) }
        RecordingController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            serial = Dispatchers.IO.limitedParallelism(1),
            recorder = TrackRecorder(
                journal = FileTrackJournal(File(noBackupFilesDir, "recording/current.journal")),
                store = { name, notes, segments, createdAt -> places.value.trackStore().save(name, notes, segments, createdAt) },
                name = { start -> getString(R.string.recording_track_name, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(start))) },
            ),
            settings = PrefsRecordingSettings(this),
            admin = { places.value.deleteRecordedTracks() },
        )
    }

    /** The navigation's location: the real one, or the simulated walk while a route simulation runs (RF-05). */
    private val navLocation: SwitchableLocationSource by lazy { SwitchableLocationSource(AndroidLocationSource(this)) }

    /**
     * The model of the navigation screen (start, stop, simulate, resume, arrival summary). Lives with the application
     * so the screen survives the activity. The voice plugs in with [NavScreenController.addSink] ([VoiceNavSink]).
     */
    val navScreen: NavScreenController by lazy {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        NavScreenController(
            scope = scope,
            controller = navigation,
            simulation = NavSimulation(scope, navLocation),
            location = navLocation,
            service = AndroidNavServiceControl(this),
            prefs = SharedNavUiPrefs(this),
            settings = com.qtekfun.ultimatemaps.voice.VoiceModule.settings(this), // the 2D/3D choice lives with the navigation settings
            cameraSettings = cameraSettings,
        ).also {
            it.addSink(VoiceNavSink(this))
            it.addSink(AlertNavSink { if (alertsStarted) cameraAlerts else null })
        }
    }

    /** How the step-by-step public-transport trip announces boarding, changes and getting off (Settings > Navigation). */
    val transitTripSettings: com.qtekfun.ultimatemaps.transit.follow.TransitTripSettingsStore by lazy {
        com.qtekfun.ultimatemaps.transit.follow.PrefsTransitTripSettings(this)
    }

    /**
     * The step-by-step public-transport trip (see `docs/phase2/transit.md`): the follower over its own location source, the
     * saved state (private, 3 h expiry), the prompts through the navigation voice, and the model of its screen. Kept running
     * in the background by [com.qtekfun.ultimatemaps.transit.follow.TransitTripService]. Re-planning only runs when the user presses
     * Re-plan: it asks the installed transit index for a trip from the current position.
     */
    val transitTrip: com.qtekfun.ultimatemaps.transit.follow.TransitTripHost by lazy {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val replanner = com.qtekfun.ultimatemaps.core.transit.follow.TransitReplanner { from, to, at ->
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val ready = transit.lookup(from, to) as? com.qtekfun.ultimatemaps.transit.TransitLookup.Ready
                (ready?.service?.plan(from, to, at) as? com.qtekfun.ultimatemaps.core.transit.TransitPlan.Found)?.itineraries?.firstOrNull()
            }
        }
        val controller = com.qtekfun.ultimatemaps.core.transit.follow.TransitTripController(
            scope = scope,
            location = AndroidLocationSource(this),
            store = com.qtekfun.ultimatemaps.core.transit.follow.TransitTripStore(File(noBackupFilesDir, "transit/trip.bin")),
            replanner = replanner,
        )
        val navSettings = VoiceModule.settings(this)
        val speaker = com.qtekfun.ultimatemaps.transit.follow.TransitTripSpeaker(
            guide = VoiceModule.guide(this),
            settings = navSettings.settings,
            mode = { transitTripSettings.promptMode.value },
            player = com.qtekfun.ultimatemaps.voice.AndroidAlertChimePlayer(this),
        )
        com.qtekfun.ultimatemaps.transit.follow.TransitTripHost(
            scope, controller, com.qtekfun.ultimatemaps.transit.follow.AndroidTripServiceControl(this), SharedNavUiPrefs(this), navSettings, speaker,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // The native core runs in its own process (`:core`), which also creates an Application: it must not start
        // the main process's housekeeping.
        if (!isMainProcess()) return
        // Offline mode is a persisted privacy setting: it must hold before anything can connect.
        policy.offlineMode = getSharedPreferences(RegionsController.PREFS, MODE_PRIVATE).getBoolean(RegionsController.KEY_OFFLINE, false)
        // Rebuild maps-core/<version>/ (links to the installed .mwm) for the search core; files only, off the main thread.
        Thread({ runCatching { CoreLinks.sync(this) } }, "mapas-core-links").start()
        // Petrol stations: only registers the host (when enabled) and reads the local cache; no connection here.
        fuel.start()
        // Cameras and incidents: only read local caches and follow the switches; nothing connects here.
        cameraData.start()
        chargerData.start()
        incidents.start()
        ensureCameraAlerts()
        // Points an interrupted recording left in its journal become a track (off the main thread, on the recorder's queue).
        recording.recoverInterrupted()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                // The app came to the foreground (first started activity): refresh only if enabled and data older than the TTL.
                if (startedActivities++ == 0) {
                    fuel.onForeground()
                    cameraData.onForeground()
                    chargerData.onForeground()
                    incidents.onForeground()
                    ensureCameraAlerts()
                    if (alertsStarted) cameraAlerts.onForeground(true)
                }
            }
            override fun onActivityStopped(activity: Activity) {
                if (--startedActivities == 0 && alertsStarted) cameraAlerts.onForeground(false)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun isMainProcess(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() == packageName else true
}
