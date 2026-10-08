package com.qtekfun.ultimatemaps.search

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.lifecycleScope
import com.qtekfun.ultimatemaps.MapasApp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.recording.RecordingPanel
import com.qtekfun.ultimatemaps.core.fuel.FuelRepository
import com.qtekfun.ultimatemaps.core.fuel.FuelSettingsStore
import com.qtekfun.ultimatemaps.core.geo.CoordinateQuery
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.cameras.HazardCardController
import com.qtekfun.ultimatemaps.bikeshare.BikeCardController
import com.qtekfun.ultimatemaps.bikeshare.BikeCardHost
import com.qtekfun.ultimatemaps.bikeshare.BikeCardState
import com.qtekfun.ultimatemaps.bikeshare.BikeShareEnv
import com.qtekfun.ultimatemaps.map.BikeMapLayer
import com.qtekfun.ultimatemaps.chargers.ChargerCardController
import com.qtekfun.ultimatemaps.chargers.ChargerCardHost
import com.qtekfun.ultimatemaps.chargers.ChargerCardState
import com.qtekfun.ultimatemaps.chargers.ChargersEnv
import com.qtekfun.ultimatemaps.map.ChargerMapLayer
import com.qtekfun.ultimatemaps.map.TrailMapLayer
import com.qtekfun.ultimatemaps.trails.TrailCardController
import com.qtekfun.ultimatemaps.trails.TrailCardHost
import com.qtekfun.ultimatemaps.trails.TrailCardState
import com.qtekfun.ultimatemaps.trails.TrailsEnv
import com.qtekfun.ultimatemaps.cameras.HazardCardState
import com.qtekfun.ultimatemaps.cameras.HazardsEnv
import com.qtekfun.ultimatemaps.map.HazardMapLayer
import com.qtekfun.ultimatemaps.fuel.FuelCardController
import com.qtekfun.ultimatemaps.fuel.FuelCardState
import com.qtekfun.ultimatemaps.map.FuelMapLayer
import com.qtekfun.ultimatemaps.map.NoFuelData
import com.qtekfun.ultimatemaps.map.StaticFuelSettings
import com.qtekfun.ultimatemaps.core.map.CameraPadding
import com.qtekfun.ultimatemaps.core.map.CameraState
import com.qtekfun.ultimatemaps.core.map.MapEngine
import com.qtekfun.ultimatemaps.places.DocumentLaunchers
import com.qtekfun.ultimatemaps.places.GeoFormat
import com.qtekfun.ultimatemaps.places.PlaceInfo
import com.qtekfun.ultimatemaps.places.PlaceShare
import com.qtekfun.ultimatemaps.places.PanelMode
import com.qtekfun.ultimatemaps.places.PlacesController
import com.qtekfun.ultimatemaps.places.QuickPlacesController
import com.qtekfun.ultimatemaps.emergency.EmergencyActivity
import com.qtekfun.ultimatemaps.shortcuts.AppShortcuts
import com.qtekfun.ultimatemaps.shortcuts.ShortcutTarget
import com.qtekfun.ultimatemaps.places.TrackLayerController
import com.qtekfun.ultimatemaps.places.openPlacesService
import com.qtekfun.ultimatemaps.places.toPlaceInfo
import com.qtekfun.ultimatemaps.core.search.SearchResult
import com.qtekfun.ultimatemaps.nav.CoMapsGuidedRouteBackend
import com.qtekfun.ultimatemaps.nav.NavLauncher
import com.qtekfun.ultimatemaps.nav.NavScreenController
import com.qtekfun.ultimatemaps.nav.NavStartHost
import com.qtekfun.ultimatemaps.nav.RouteRunner
import com.qtekfun.ultimatemaps.route.CoMapsRouteBackend
import com.qtekfun.ultimatemaps.route.LogcatRouteLog
import com.qtekfun.ultimatemaps.route.RouteFraming
import com.qtekfun.ultimatemaps.route.RoutePadding
import com.qtekfun.ultimatemaps.route.RoutePreviewController
import com.qtekfun.ultimatemaps.ui.MapScreenState
import com.qtekfun.ultimatemaps.ui.sheet.SheetDetent
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File

/**
 * Wires search, place card, saved places and the document picker to the activity and the map engine, so
 * `MainActivity` only needs to create it, call [onStart] and show [Content] in the sheet.
 * Create it while the activity is being constructed (the document launchers register for results).
 *
 * @param regions where the installed offline maps are; the regions module can pass its own implementation.
 */
class PanelHost(
    private val activity: ComponentActivity,
    private val engine: MapEngine,
    private val screen: MapScreenState,
    regions: InstalledRegions = DirectoryInstalledRegions(File(activity.filesDir, "maps-core")),
    /** Petrol-station data and settings; empty / switched off until the data module is wired in. */
    private val fuelRepository: FuelRepository = NoFuelData,
    private val fuelSettings: FuelSettingsStore = StaticFuelSettings(),
    /** Display name of a fuel id, for the station card. */
    private val fuelName: (String) -> String = { it },
    /** Optional speed-camera and traffic layers (null: none). */
    private val hazards: HazardsEnv? = null,
    /** Optional EV-charger layer (null: none). */
    private val chargers: ChargersEnv? = null,
    /** Optional hiking and cycling route layer (null: none). */
    private val trails: TrailsEnv? = null,
    /** Optional bike-share station layer (null: none). */
    private val bikeShare: BikeShareEnv? = null,
    /** The navigation model; with it the route card offers "Start" and "Simulate". Null: preview only. */
    private val navScreen: NavScreenController? = null,
) {
    /** Last known user position, in memory only; used to sort saved places by distance. */
    var userLocation: LatLon? = null
        set(value) {
            field = value
            route.onUserLocation()
            quick.onUserLocation()
        }

    /** Asks the activity for the location permission and a fix (used by the route preview). */
    var onRequestLocation: () -> Unit = {}

    /** One lock for every native call (search and routing share the one core of the process). */
    private val coreLock = Mutex()

    val search = SearchCoordinator(
        scope = activity.lifecycleScope,
        io = Dispatchers.IO,
        regions = regions,
        backend = CoMapsSearchBackend(activity),
        near = { engine.cameraState().center },
        clock = ::elapsedMillis,
        log = LogcatSearchLog,
        mutex = coreLock,
        categoryOrigin = { userLocation ?: engine.cameraState().center },
        onCategoryResults = { engine.showCategoryPins(it, fit = it.size >= 2) },
        coordinateLabels = CoordinateLabels { kind ->
            activity.getString(
                if (kind == CoordinateQuery.Kind.DECIMAL || kind == CoordinateQuery.Kind.DMS) R.string.search_result_coordinates
                else R.string.search_result_plus_code,
            )
        },
    )

    /**
     * Frames the whole route (origin and destination included) above the sheet whenever a route or itinerary is drawn,
     * and again when the sheet changes height unless the user moved the map. Silent during a navigation.
     */
    private val routeFraming = RouteFraming(
        navigating = { navScreen?.ui?.value?.active == true },
        detent = { screen.detent },
        frame = { points, detent ->
            val dm = activity.resources.displayMetrics
            val status = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top ?: 0
            engine.frameRoute(points, RoutePadding.compute(detent, dm.heightPixels, dm.density, status))
        },
    ).also { framing ->
        engine.addCameraGestureListener { framing.onUserMovedMap() }
        activity.lifecycleScope.launch {
            snapshotFlow { screen.detent }.drop(1).collect { framing.onDetentChanged() }
        }
    }

    /** Draws a transit itinerary with its origin marker and frames all of it, walking legs included. */
    private fun showItinerary(legs: List<com.qtekfun.ultimatemaps.core.map.TransitMapLeg>) {
        val points = legs.flatMap { it.points }
        engine.showTransitItinerary(legs, fit = false)
        engine.showRouteOrigin(points.firstOrNull())
        if (points.isEmpty()) routeFraming.clear() else routeFraming.show(points)
    }

    /** Public-transport mode of the route panel; null when the application object is not the real one (tests). */
    private val transitController: com.qtekfun.ultimatemaps.transit.TransitController? =
        (activity.application as? MapasApp)?.transit?.let { repo ->
            com.qtekfun.ultimatemaps.transit.TransitController(
                scope = activity.lifecycleScope,
                io = Dispatchers.IO,
                source = repo,
                showItinerary = ::showItinerary,
                onStartTrip = { itinerary, zone -> (activity.application as MapasApp).transitTrip.start(itinerary, zone) },
                realTime = (activity.application as MapasApp).cercaniasRealTime,
                settings = (activity.application as MapasApp).transitTripSettings,
            )
        }

    val route = RoutePreviewController(
        scope = activity.lifecycleScope,
        io = Dispatchers.IO,
        regions = regions,
        backend = CoMapsRouteBackend(activity),
        userLocation = { userLocation },
        showRoute = { points ->
            engine.showRoute(points, fit = false)
            engine.showRouteOrigin(points.firstOrNull())
            routeFraming.show(points)
        },
        clearRoute = { engine.clearRoute(); routeFraming.clear() },
        clock = ::elapsedMillis,
        log = LogcatRouteLog,
        mutex = coreLock,
        defaultBikeCycleways = { com.qtekfun.ultimatemaps.voice.VoiceModule.settings(activity).settings.value.bikeCycleways },
        showAlternatives = engine::showAlternativeRoutes,
        transit = transitController,
        lowEmissionZones = { geometry ->
            val app = activity.application as MapasApp
            if (app.zbeSettings.settings.value.enabled) app.zbeData.repository.index.crossings(geometry) else emptyList()
        },
        weatherWarnings = { geometry -> (activity.application as MapasApp).weatherAlerts.forRoute(geometry) },
    )

    /** "Start" / "Simulate" on the route card: the guided route goes through the same shared core and lock. */
    val navLauncher: NavLauncher? = navScreen?.let { screenModel ->
        NavLauncher(
            scope = activity.lifecycleScope,
            io = Dispatchers.IO,
            regions = regions,
            backend = CoMapsGuidedRouteBackend(activity),
            runner = RouteRunner(Dispatchers.IO),
            screen = screenModel,
            mutex = coreLock,
            request = route::currentRequest,
            onStarted = {
                route.close() // the preview is replaced by the navigation screen
                screen.detent = SheetDetent.COLLAPSED
            },
        )
    }

    private val placesService = lazy { openPlacesService(activity) }

    val places = PlacesController(
        scope = activity.lifecycleScope,
        io = Dispatchers.IO,
        service = placesService,
        near = { userLocation ?: engine.cameraState().center },
        onMarkers = engine::showMarkers,
        onReloaded = { tracks.refresh() },
    )

    /** Imported GPX tracks drawn as map lines (toggle and fit per track in the lists overview). */
    val tracks = TrackLayerController(
        scope = activity.lifecycleScope,
        io = Dispatchers.IO,
        service = placesService,
        render = engine::showTracks,
        fit = { points ->
            val d = activity.resources.displayMetrics.density
            // The bottom sheet covers roughly the lower half of the screen.
            engine.frameRoute(points, CameraPadding((40 * d).toInt(), (80 * d).toInt(), (40 * d).toInt(), (300 * d).toInt()))
        },
        recording = (activity.application as? MapasApp)?.recording?.let { RecordingPanel(it) { onRequestLocation() } },
    )

    /** Home, Work and the parked car (on this device only). */
    val quick = QuickPlacesController(
        scope = activity.lifecycleScope,
        io = Dispatchers.IO,
        service = placesService,
        location = { userLocation },
        requestLocation = { onRequestLocation() },
        onParking = engine::showParking,
        parkingName = { activity.getString(R.string.parking_name) },
    )

    /** Recent searches; remembered only while the Settings switch is on. */
    val history = SearchHistory(
        scope = activity.lifecycleScope,
        io = Dispatchers.IO,
        service = placesService,
        settings = PrefsHistorySettings(activity),
    )

    /** A navigation is running (the sheet is hidden then, except for the station and hazard cards). */
    val navigating: Boolean get() = navScreen?.ui?.value?.active == true

    /** A petrol-station card is open (it is shown over the navigation screen too). */
    val fuelCardOpen: Boolean get() = fuelCard.card.station != null

    /** A camera or incident card is open (it is shown over the navigation screen too). */
    val hazardCardOpen: Boolean get() = hazardCard.card.info != null

    /** An EV charger card is open (it is shown over the navigation screen too). */
    val chargerCardOpen: Boolean get() = chargerCard.card.charger != null

    /** A bike-share station card is open (it is shown over the navigation screen too). */
    val bikeCardOpen: Boolean get() = bikeCard.card.station != null

    /** The sheet may be drawn above the navigation screen: a station, charger or hazard card is open. */
    val cardOverNavigation: Boolean get() = fuelCardOpen || hazardCardOpen || chargerCardOpen || bikeCardOpen

    val fuelCard = FuelCardController(
        repository = fuelRepository,
        route = route,
        places = places,
        card = FuelCardState(),
        category = { activity.getString(R.string.fuel_category) },
        onOpened = { screen.notice = null; screen.detent = SheetDetent.MEDIUM; chargerCard.card.close(); trailCard.card.close(); bikeCard.close() },
        // "Go" replaces the destination: the running navigation ends and the route preview takes over.
        beforeGo = { navScreen?.takeIf { it.ui.value.active }?.stop() },
        // "Add stop" while navigating re-plans the trip in progress instead of editing a preview.
        navigating = { navigating },
        navStops = navScreen?.let { n -> { point -> n.addStop(point) } },
        scope = activity.lifecycleScope,
    )

    private val fuelLayer = FuelMapLayer(
        scope = activity.lifecycleScope,
        io = Dispatchers.Default,
        repository = fuelRepository,
        settings = fuelSettings.settings,
        render = engine::showFuel,
    )

    val hazardCard: HazardCardController = HazardCardController(
        describer = { id -> hazards?.describer?.describe(id) },
        card = HazardCardState(),
        onOpened = { screen.notice = null; screen.detent = SheetDetent.MEDIUM; chargerCard.card.close(); trailCard.card.close(); bikeCard.close() },
    )

    val chargerCard: ChargerCardController = ChargerCardController(
        repository = chargers?.repository ?: com.qtekfun.ultimatemaps.core.chargers.ChargerRepository(),
        route = route,
        card = ChargerCardState(),
        category = { activity.getString(R.string.ev_category) },
        onOpened = { screen.notice = null; screen.detent = SheetDetent.MEDIUM; fuelCard.card.close(); hazardCard.card.close(); trailCard.card.close(); bikeCard.close() },
        beforeGo = { navScreen?.takeIf { it.ui.value.active }?.stop() },
        navigating = { navigating },
        navStops = navScreen?.let { n -> { point -> n.addStop(point) } },
        scope = activity.lifecycleScope,
    )

    val bikeCard: BikeCardController = BikeCardController(
        repository = bikeShare?.repository ?: com.qtekfun.ultimatemaps.core.bikeshare.BikeShareRepository(),
        route = route,
        card = BikeCardState(),
        category = { activity.getString(R.string.bike_category) },
        onOpened = { screen.notice = null; screen.detent = SheetDetent.MEDIUM; fuelCard.card.close(); hazardCard.card.close(); chargerCard.card.close(); trailCard.card.close() },
        beforeGo = { navScreen?.takeIf { it.ui.value.active }?.stop() },
        navigating = { navigating },
        navStops = navScreen?.let { n -> { point -> n.addStop(point) } },
        scope = activity.lifecycleScope,
        live = bikeShare?.live ?: { null },
    )

    val trailCard: TrailCardController = TrailCardController(
        repository = trails?.repository ?: com.qtekfun.ultimatemaps.core.routes.RouteRepository(),
        route = route,
        card = TrailCardState(),
        category = { activity.getString(R.string.trail_category) },
        onOpened = { screen.notice = null; screen.detent = SheetDetent.MEDIUM; fuelCard.card.close(); hazardCard.card.close(); chargerCard.card.close(); bikeCard.close() },
        beforeGo = { navScreen?.takeIf { it.ui.value.active }?.stop() },
        navigating = { navigating },
    )

    private val trailLayer: TrailMapLayer? = trails?.let { t ->
        TrailMapLayer(
            scope = activity.lifecycleScope,
            io = Dispatchers.Default,
            repository = t.repository,
            settings = t.settings,
            render = engine::showTrails,
        )
    }

    private val bikeLayer: BikeMapLayer? = bikeShare?.let { b ->
        BikeMapLayer(
            scope = activity.lifecycleScope,
            io = Dispatchers.Default,
            repository = b.repository,
            settings = b.settings,
            render = engine::showBikeStations,
        )
    }

    private val chargerLayer: ChargerMapLayer? = chargers?.let { c ->
        ChargerMapLayer(
            scope = activity.lifecycleScope,
            io = Dispatchers.Default,
            repository = c.repository,
            settings = c.settings,
            render = engine::showChargers,
        )
    }

    private val hazardLayer: HazardMapLayer? = hazards?.let { h ->
        HazardMapLayer(
            scope = activity.lifecycleScope,
            io = Dispatchers.Default,
            cameras = h.cameras,
            incidents = h.incidents,
            settings = h.settings,
            render = engine::showHazards,
        )
    }

    private val documents: DocumentLaunchers = DocumentLaunchers(
        activity,
        onImport = { uri -> places.import({ input(uri) }, documents.displayName(uri)) },
        onExport = { format, uri -> places.export(format) { output(uri) } },
    )

    /** Call from `onStart`: re-scans the regions (they may have changed) and redraws the saved markers. */
    fun onStart() {
        search.refreshRegions()
        route.invalidate()
        places.reload()
        quick.refresh()
        history.refresh()
    }

    /** A launcher shortcut (see `AppShortcuts`) asked for a screen; unknown actions are ignored. */
    fun handleShortcut(action: String?) {
        when (AppShortcuts.parse(action) ?: return) {
            ShortcutTarget.SEARCH -> {
                places.closeCard()
                places.showMode(PanelMode.SEARCH)
                screen.detent = SheetDetent.FULL
            }
            ShortcutTarget.SAVED -> {
                places.closeCard()
                places.showMode(PanelMode.LISTS)
                screen.detent = SheetDetent.FULL
            }
            ShortcutTarget.MAPS -> screen.onOpenMaps()
            ShortcutTarget.EMERGENCY -> openEmergency()
        }
    }

    fun openEmergency() = activity.startActivity(Intent(activity, EmergencyActivity::class.java))

    fun onDestroy() {
        fuelLayer.stop()
        chargerLayer?.stop()
        bikeLayer?.stop()
        trailLayer?.stop()
        hazardLayer?.stop()
        search.close()
        navLauncher?.reset()
        route.close()
    }

    private fun hasLocationPermission() =
        activity.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            activity.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun input(uri: Uri) = activity.contentResolver.openInputStream(uri) ?: error("cannot open document")
    private fun output(uri: Uri) = activity.contentResolver.openOutputStream(uri, "wt") ?: error("cannot open document")

    init {
        engine.setMapTapListener { route.pickOrigin(it, null) }
        engine.setFuelTapListener(fuelCard::onStationTap)
        engine.setHazardTapListener(hazardCard::onTap)
        engine.setChargerTapListener(chargerCard::onChargerTap)
        engine.setBikeTapListener(bikeCard::onStationTap)
        engine.setTrailTapListener(trailCard::onTrailTap)
        engine.setViewportListener { bounds, zoom ->
            fuelLayer.onViewport(bounds, zoom)
            hazardLayer?.onViewport(bounds, zoom)
            chargerLayer?.onViewport(bounds, zoom)
            bikeLayer?.onViewport(bounds, zoom)
            trailLayer?.onViewport(bounds, zoom)
        }
        fuelLayer.start()
        chargerLayer?.start()
        bikeLayer?.start()
        trailLayer?.start()
        hazardLayer?.start()
        // A recording was saved or tracks were deleted: refresh the tracks list.
        (tracks.recording?.controller)?.let { r -> activity.lifecycleScope.launch { r.stored.collect { tracks.refresh() } } }
    }

    private fun show(info: PlaceInfo, zoom: Double = PLACE_ZOOM) {
        screen.notice = null
        screen.detent = SheetDetent.MEDIUM
        trailCard.card.close()
        engine.showPin(info.point)
        engine.animateTo(CameraState(info.point, zoom))
        places.showCard(info)
    }

    /** A `geo:` or map link with a point: the same place card as a search result. */
    fun showLinkPlace(info: PlaceInfo, zoom: Double) = show(info, zoom)

    /** A map link with search text: shows the Search tab and runs the text through the search box's coordinator. */
    fun runLinkSearch(query: String) {
        places.closeCard()
        places.showMode(PanelMode.SEARCH)
        search.onQueryChange(query)
        screen.detent = SheetDetent.FULL
    }

    /** A search result: the route origin while one is being picked, otherwise the place card. */
    private fun pick(result: SearchResult) {
        // A typed position is not a search worth remembering (and would store a location in the history).
        if (!search.state.coordinateQuery) history.record(search.state.query)
        quick.dismissMessage()
        if (route.state.pickingOrigin) route.pickOrigin(result.point, result.name) else show(result.toPlaceInfo())
    }

    private fun share(info: PlaceInfo) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, PlaceShare.text(info))
        activity.startActivity(Intent.createChooser(send, null))
    }

    /** Opens the dialer with the number filled in; the user presses call (no permission, nothing is dialled). */
    private fun dial(uri: String) = openExternal(Intent(Intent.ACTION_DIAL, Uri.parse(uri)), R.string.place_no_dialer)

    private fun openWebsite(uri: String) = openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(uri)), R.string.place_no_browser)

    private fun openExternal(intent: Intent, missing: Int) {
        try {
            activity.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(activity, missing, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    fun Content() {
        val focus = LocalFocusManager.current
        val actions = PanelActions(
            onPickResult = { focus.clearFocus(); pick(it) },
            onShowSaved = { focus.clearFocus(); show(it) },
            onRoute = { route.start(it) },
            onShare = ::share,
            onImport = documents::pickFile,
            onExport = { format: GeoFormat -> documents.createFile(format, EXPORT_NAME) },
            onFocusField = { screen.detent = SheetDetent.FULL },
            onOpenMaps = { screen.onOpenMaps() },
            onUseLocation = { route.useCurrentLocation(); onRequestLocation() },
            onCategoryOpened = { screen.detent = SheetDetent.MEDIUM },
            onDial = ::dial,
            onOpenWebsite = ::openWebsite,
        )
        val fuel = remember {
            FuelCardHost(
                state = fuelCard.card,
                mapFuelId = { fuelSettings.settings.value.mapFuel },
                fuelName = fuelName,
                updatedMillis = { fuelRepository.lastUpdateMillis.value },
                now = System::currentTimeMillis,
                onGo = fuelCard::go, onAddStop = fuelCard::addStop, onSave = fuelCard::save,
                navigating = { navigating },
            )
        }
        val navStart = remember(navLauncher) {
            navLauncher?.let { l ->
                NavStartHost(
                    state = l.state,
                    onStart = { if (!hasLocationPermission()) onRequestLocation(); l.start(simulate = false) },
                    onSimulate = { l.start(simulate = true) },
                )
            }
        }
        val chargerHost = remember {
            ChargerCardHost(
                state = chargerCard.card,
                generatedMillis = { chargers?.repository?.generatedMillis?.value },
                onGo = chargerCard::go, onAddStop = chargerCard::addStop,
                navigating = { navigating },
            )
        }
        val bikeHost = remember {
            BikeCardHost(
                state = bikeCard.card,
                generatedMillis = { bikeShare?.repository?.generatedMillis?.value },
                onGo = bikeCard::go, onAddStop = bikeCard::addStop,
                navigating = { navigating },
            )
        }
        val trailHost = remember {
            TrailCardHost(
                state = trailCard.card,
                generatedMillis = { trails?.repository?.generatedMillis?.value },
                onGoToStart = trailCard::goToStart,
            )
        }
        SheetPanel(
            search, places, actions, route = route, fuel = fuel, navStart = navStart,
            quick = quick, history = history, onEmergency = ::openEmergency, tracks = tracks,
            hazard = hazardCard.card,
            charger = chargerHost,
            bike = bikeHost,
            trail = trailHost,
        )
    }

    private companion object {
        const val PLACE_ZOOM = 15.0
        const val EXPORT_NAME = "mapas"
    }
}
