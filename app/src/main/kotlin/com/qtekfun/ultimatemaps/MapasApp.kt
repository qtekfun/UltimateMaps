package com.qtekfun.ultimatemaps

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.io.File
import com.qtekfun.ultimatemaps.core.nav.NavStateStore
import com.qtekfun.ultimatemaps.core.nav.LearnedTunnelStore
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
import com.qtekfun.ultimatemaps.bikeshare.PrefsBikeShareSettingsStore
import com.qtekfun.ultimatemaps.core.bikeshare.BikeAvailabilityRepository
import com.qtekfun.ultimatemaps.core.bikeshare.BikeShareAsset
import com.qtekfun.ultimatemaps.core.bikeshare.BikeShareDataManager
import com.qtekfun.ultimatemaps.core.bikeshare.BikeShareSettingsStore
import com.qtekfun.ultimatemaps.core.zbe.ZbeAheadSpeaker
import com.qtekfun.ultimatemaps.core.zbe.ZbeAsset
import com.qtekfun.ultimatemaps.core.zbe.ZbeDataManager
import com.qtekfun.ultimatemaps.core.zbe.ZbeSettingsStore
import com.qtekfun.ultimatemaps.zbe.PrefsZbeSettingsStore
import com.qtekfun.ultimatemaps.zbe.ZbeBannerState
import com.qtekfun.ultimatemaps.zbe.ZbePrompter
import com.qtekfun.ultimatemaps.core.routes.RouteAsset
import com.qtekfun.ultimatemaps.core.routes.RouteDataManager
import com.qtekfun.ultimatemaps.core.routes.RouteSettingsStore
import com.qtekfun.ultimatemaps.trails.PrefsRouteSettingsStore
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
import com.qtekfun.ultimatemaps.transit.follow.planOptions
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

    /** Hiking and cycling route overlay switch and kinds (off by default). The map layer, the card and Settings read these. */
    val routeSettings: RouteSettingsStore by lazy { PrefsRouteSettingsStore(this) }

    /**
     * The route file (several MB): fetched from the catalog's `routes` entry through [networkPolicy] (same server and purpose
     * as the camera file), cached, and absent-tolerant. Nothing is downloaded when it is created or while the switch is off.
     */
    val routeData: RouteDataManager by lazy {
        RouteDataManager(routeSettings, policy, ::routeAsset, File(filesDir, "routes"), syncCatalog = { force -> regions.syncCatalog(force) })
    }

    private fun routeAsset(): RouteAsset? =
        (regions.catalogState as? CatalogState.Loaded)?.catalog?.routes?.let { RouteAsset(it.url, it.sizeBytes, it.sha256) }

    private fun chargerAsset(): ChargerAsset? =
        (regions.catalogState as? CatalogState.Loaded)?.catalog?.chargers?.let { ChargerAsset(it.url, it.sizeBytes, it.sha256) }

    /** Bike-share switches (the stations and, separately, live availability; both off by default). */
    val bikeShareSettings: BikeShareSettingsStore by lazy { PrefsBikeShareSettingsStore(this) }

    /**
     * The static bike-share station file: fetched from the catalog's `bikeshare` entry through [networkPolicy] (the same
     * server and purpose as the camera file), cached, and absent-tolerant. Nothing is downloaded when it is created.
     */
    val bikeShareData: BikeShareDataManager by lazy {
        BikeShareDataManager(bikeShareSettings, policy, ::bikeShareAsset, File(filesDir, "bikeshare"), syncCatalog = { force -> regions.syncCatalog(force) })
    }

    /**
     * Optional live bike and dock counts of a station: only while its card is open and only when both bike-share switches
     * are on; the host is listed in the policy only then. Nothing connects when it is created or started.
     */
    val bikeAvailability: BikeAvailabilityRepository by lazy {
        BikeAvailabilityRepository(bikeShareSettings.settings, policy, policy::addEndpoint, policy::removeEndpoint)
    }

    /** Weather-alert switches (the switch and the yellow level; both off by default). */
    val weatherSettings: com.qtekfun.ultimatemaps.core.weather.WeatherAlertSettingsStore by lazy {
        com.qtekfun.ultimatemaps.weather.PrefsWeatherAlertSettings(this)
    }

    /**
     * Optional AEMET weather warnings (opt-in, off by default): the user's own API key sits in the Android Keystore, the one
     * national bundle is fetched through [networkPolicy] only while the switch is on and a key is present, and everything is
     * matched on the phone. Nothing connects when it is created or started (see `docs/phase2/weather-alerts.md`).
     */
    val weatherAlerts: com.qtekfun.ultimatemaps.weather.WeatherAlertsController by lazy {
        val keys = com.qtekfun.ultimatemaps.core.weather.CachedApiKeyStore(com.qtekfun.ultimatemaps.weather.KeystoreApiKeyStore(this))
        com.qtekfun.ultimatemaps.weather.WeatherAlertsController(
            settings = weatherSettings,
            keys = keys,
            repository = com.qtekfun.ultimatemaps.core.weather.WeatherAlertRepository(
                source = com.qtekfun.ultimatemaps.core.weather.HttpAemetSource(policy),
                enabled = { weatherSettings.settings.value.enabled },
                apiKey = keys::read,
                language = { java.util.Locale.getDefault().language },
            ),
            addEndpoint = policy::addEndpoint,
            removeEndpoint = policy::removeEndpoint,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    private fun bikeShareAsset(): BikeShareAsset? =
        (regions.catalogState as? CatalogState.Loaded)?.catalog?.bikeshare?.let { BikeShareAsset(it.url, it.sizeBytes, it.sha256) }

    /** Low-emission-zone switch and options (off by default). The map layer, the route warning, the prompt and Settings read these. */
    val zbeSettings: ZbeSettingsStore by lazy { PrefsZbeSettingsStore(this) }

    /**
     * The static low-emission-zone file: fetched from the catalog's `zbe` entry through [networkPolicy] (the same server and
     * purpose as the camera file), cached, and absent-tolerant. Nothing is downloaded when it is created.
     */
    val zbeData: ZbeDataManager by lazy {
        ZbeDataManager(zbeSettings, policy, ::zbeAsset, File(filesDir, "zbe"), syncCatalog = { force -> regions.syncCatalog(force) })
    }

    private fun zbeAsset(): ZbeAsset? =
        (regions.catalogState as? CatalogState.Loaded)?.catalog?.zbe?.let { ZbeAsset(it.url, it.sizeBytes, it.sha256) }

    /** The "Low-emission zone ahead" banner of the navigation screen; owned here so the screens can observe it before the prompter exists. */
    val zbeBanner = kotlinx.coroutines.flow.MutableStateFlow<ZbeBannerState?>(null)

    private var zbePrompter: ZbePrompter? = null

    /**
     * Starts the one-time "ahead" prompt of car navigations the first time the zone switch is on (idempotent). Kept lazy so the
     * cold start does not build the navigation controller for users who never turn the feature on. Main thread.
     */
    fun ensureZbePrompter() {
        if (zbePrompter != null || !zbeSettings.settings.value.enabled) return
        zbePrompter = ZbePrompter(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            settings = zbeSettings.settings,
            repository = zbeData.repository,
            state = navigation.state,
            route = navigation.route,
            profile = { navigation.tripProfile },
            speaker = ZbeAheadSpeaker(
                VoiceModule.guide(this), VoiceModule.settings(this).settings,
                mode = { zbeSettings.settings.value.promptMode },
                maneuverImminent = { navigation.state.value?.let { ManeuverGuard.blocksVoice(it.nextManeuver?.distanceMeters, it.speedMps) } ?: false },
                player = com.qtekfun.ultimatemaps.voice.AndroidAlertChimePlayer(this),
            ),
            bannerState = zbeBanner,
        ).also { it.start() }
    }

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
            location = { com.qtekfun.ultimatemaps.platform.PlatformServices.locationSource(this) },
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
            // Tunnels learned from signal losses on this device; in memory only (see docs/phase2/tunnel-positioning.md).
            tunnelSpans = LearnedTunnelStore(),
            // Accelerometer stop/go, registered only while a known tunnel is being crossed without GPS (Settings > Navigation).
            stopGo = com.qtekfun.ultimatemaps.nav.AndroidStopGoSignal(
                source = com.qtekfun.ultimatemaps.nav.SensorManagerSampleSource(this),
                enabled = { VoiceModule.settings(this).settings.value.motionSensorsInTunnels },
            ),
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
    private val navLocation: SwitchableLocationSource by lazy { SwitchableLocationSource(com.qtekfun.ultimatemaps.platform.PlatformServices.locationSource(this)) }

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
     * Cercanías real time (opt-in, off by default): Renfe's GTFS-RT delays for the itinerary cards and the live follower. While
     * the switch is off nothing here connects and the host is not even in the policy. Asking is always through [networkPolicy].
     */
    val cercaniasRealTime: com.qtekfun.ultimatemaps.transit.CercaniasRealTime by lazy {
        com.qtekfun.ultimatemaps.transit.CercaniasRealTime(
            settings = transitTripSettings,
            addEndpoint = policy::addEndpoint,
            removeEndpoint = policy::removeEndpoint,
            repository = com.qtekfun.ultimatemaps.core.transit.rt.RealTimeRepository(com.qtekfun.ultimatemaps.core.transit.rt.HttpRealTimeFetcher(policy)),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    /**
     * The step-by-step public-transport trip (see `docs/phase2/transit.md`): the follower over its own location source, the
     * saved state (private, 3 h expiry), the prompts through the navigation voice, and the model of its screen. Kept running
     * in the background by [com.qtekfun.ultimatemaps.transit.follow.TransitTripService]. Re-planning only runs when the user presses
     * Re-plan: it asks the installed transit index for a trip from the current position.
     */
    /** The system's movement hint for the transit trip (activity recognition in the `play` flavor, none in `foss`). */
    val movementHint: com.qtekfun.ultimatemaps.core.map.MovementHint by lazy { com.qtekfun.ultimatemaps.platform.PlatformServices.movementHint(this) }

    /** The movement permission was just granted while a trip runs: start listening to the hint now. */
    fun transitTripRestartHint() {
        if (transitTrip.ui.value.active) movementHint.start()
    }

    val transitTrip: com.qtekfun.ultimatemaps.transit.follow.TransitTripHost by lazy {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val replanner = com.qtekfun.ultimatemaps.core.transit.follow.TransitReplanner { from, to, at ->
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val ready = transit.lookup(from, to) as? com.qtekfun.ultimatemaps.transit.TransitLookup.Ready
                // the same mode chips and walking limits as the route panel
                val options = transitTripSettings.planOptions()
                (ready?.service?.plan(from, to, at, options = options) as? com.qtekfun.ultimatemaps.core.transit.TransitPlan.Found)?.itineraries?.firstOrNull()
            }
        }
        val controller = com.qtekfun.ultimatemaps.core.transit.follow.TransitTripController(
            scope = scope,
            location = com.qtekfun.ultimatemaps.platform.PlatformServices.locationSource(this),
            movementHint = movementHint,
            store = com.qtekfun.ultimatemaps.core.transit.follow.TransitTripStore(File(noBackupFilesDir, "transit/trip.bin")),
            replanner = replanner,
            realTime = cercaniasRealTime,
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
            realTime = cercaniasRealTime,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Local diagnostic notes of an uncaught exception (class and stack only; Settings, About shows them). Each process
        // writes its own file, and the previous handler still ends the process as before.
        if (isMainProcess()) {
            com.qtekfun.ultimatemaps.diagnostics.Diagnostics.notes(this).installAsUncaughtHandler()
        } else {
            com.qtekfun.ultimatemaps.diagnostics.Diagnostics.coreNotes(this).installAsUncaughtHandler()
        }
        // The native core runs in its own process (`:core`), which also creates an Application: it must not start
        // the main process's housekeeping.
        if (!isMainProcess()) return
        // Offline mode is a persisted privacy setting: it must hold before anything can connect.
        policy.offlineMode = getSharedPreferences(RegionsController.PREFS, MODE_PRIVATE).getBoolean(RegionsController.KEY_OFFLINE, false)
        // Rebuild maps-core/<version>/ (links to the installed .mwm) for the search core; files only, off the main thread.
        Thread({ runCatching { CoreLinks.sync(this) } }, "mapas-core-links").start()
        // Petrol stations: only registers the host (when enabled) and reads the local cache; no connection here.
        fuel.start()
        // Cercanias real time: only follows its switch (and lists the Renfe host while it is on); no connection here.
        cercaniasRealTime.start()
        // Cameras and incidents: only read local caches and follow the switches; nothing connects here.
        cameraData.start()
        chargerData.start()
        zbeData.start()
        bikeShareData.start()
        bikeAvailability.start()
        // Weather alerts: only follows the switch and the key (lists the AEMET host while both are in place); no connection unless both are.
        weatherAlerts.start()
        routeData.start()
        incidents.start()
        ensureCameraAlerts()
        ensureZbePrompter()
        // Points an interrupted recording left in its journal become a track (off the main thread, on the recorder's queue).
        recording.recoverInterrupted()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                // The app came to the foreground (first started activity): refresh only if enabled and data older than the TTL.
                if (startedActivities++ == 0) {
                    fuel.onForeground()
                    cameraData.onForeground()
                    chargerData.onForeground()
                    zbeData.onForeground()
                    bikeShareData.onForeground()
                    weatherAlerts.ensureFresh()
                    routeData.onForeground()
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
