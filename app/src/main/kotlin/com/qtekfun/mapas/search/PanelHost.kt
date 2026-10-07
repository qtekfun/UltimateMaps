package com.qtekfun.mapas.search

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.lifecycleScope
import com.qtekfun.mapas.MapasApp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.recording.RecordingPanel
import com.qtekfun.mapas.core.fuel.FuelRepository
import com.qtekfun.mapas.core.fuel.FuelSettingsStore
import com.qtekfun.mapas.core.geo.CoordinateQuery
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.cameras.HazardCardController
import com.qtekfun.mapas.cameras.HazardCardState
import com.qtekfun.mapas.cameras.HazardsEnv
import com.qtekfun.mapas.map.HazardMapLayer
import com.qtekfun.mapas.fuel.FuelCardController
import com.qtekfun.mapas.fuel.FuelCardState
import com.qtekfun.mapas.map.FuelMapLayer
import com.qtekfun.mapas.map.NoFuelData
import com.qtekfun.mapas.map.StaticFuelSettings
import com.qtekfun.mapas.core.map.CameraPadding
import com.qtekfun.mapas.core.map.CameraState
import com.qtekfun.mapas.core.map.MapEngine
import com.qtekfun.mapas.places.DocumentLaunchers
import com.qtekfun.mapas.places.GeoFormat
import com.qtekfun.mapas.places.PlaceInfo
import com.qtekfun.mapas.places.PlaceShare
import com.qtekfun.mapas.places.PanelMode
import com.qtekfun.mapas.places.PlacesController
import com.qtekfun.mapas.places.QuickPlacesController
import com.qtekfun.mapas.emergency.EmergencyActivity
import com.qtekfun.mapas.shortcuts.AppShortcuts
import com.qtekfun.mapas.shortcuts.ShortcutTarget
import com.qtekfun.mapas.places.TrackLayerController
import com.qtekfun.mapas.places.openPlacesService
import com.qtekfun.mapas.places.toPlaceInfo
import com.qtekfun.mapas.core.search.SearchResult
import com.qtekfun.mapas.nav.CoMapsGuidedRouteBackend
import com.qtekfun.mapas.nav.NavLauncher
import com.qtekfun.mapas.nav.NavScreenController
import com.qtekfun.mapas.nav.NavStartHost
import com.qtekfun.mapas.nav.RouteRunner
import com.qtekfun.mapas.route.CoMapsRouteBackend
import com.qtekfun.mapas.route.LogcatRouteLog
import com.qtekfun.mapas.route.RoutePreviewController
import com.qtekfun.mapas.ui.MapScreenState
import com.qtekfun.mapas.ui.sheet.SheetDetent
import kotlinx.coroutines.Dispatchers
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

    /** Public-transport mode of the route panel; null when the application object is not the real one (tests). */
    private val transitController: com.qtekfun.mapas.transit.TransitController? =
        (activity.application as? MapasApp)?.transit?.let { repo ->
            com.qtekfun.mapas.transit.TransitController(
                scope = activity.lifecycleScope,
                io = Dispatchers.IO,
                source = repo,
                showItinerary = { engine.showTransitItinerary(it) },
            )
        }

    val route = RoutePreviewController(
        scope = activity.lifecycleScope,
        io = Dispatchers.IO,
        regions = regions,
        backend = CoMapsRouteBackend(activity),
        userLocation = { userLocation },
        showRoute = { engine.showRoute(it) },
        clearRoute = engine::clearRoute,
        clock = ::elapsedMillis,
        log = LogcatRouteLog,
        mutex = coreLock,
        defaultBikeCycleways = { com.qtekfun.mapas.voice.VoiceModule.settings(activity).settings.value.bikeCycleways },
        showAlternatives = engine::showAlternativeRoutes,
        transit = transitController,
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

    /** A navigation is running (the sheet is hidden then, except for the station card). */
    val navigating: Boolean get() = navScreen?.ui?.value?.active == true

    /** A petrol-station card is open (it is shown over the navigation screen too). */
    val fuelCardOpen: Boolean get() = fuelCard.card.station != null

    val fuelCard = FuelCardController(
        repository = fuelRepository,
        route = route,
        places = places,
        card = FuelCardState(),
        category = { activity.getString(R.string.fuel_category) },
        onOpened = { screen.notice = null; screen.detent = SheetDetent.MEDIUM },
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

    val hazardCard = HazardCardController(
        describer = { id -> hazards?.describer?.describe(id) },
        card = HazardCardState(),
        onOpened = { screen.notice = null; screen.detent = SheetDetent.MEDIUM },
    )

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
        engine.setViewportListener { bounds, zoom ->
            fuelLayer.onViewport(bounds, zoom)
            hazardLayer?.onViewport(bounds, zoom)
        }
        fuelLayer.start()
        hazardLayer?.start()
        // A recording was saved or tracks were deleted: refresh the tracks list.
        (tracks.recording?.controller)?.let { r -> activity.lifecycleScope.launch { r.stored.collect { tracks.refresh() } } }
    }

    private fun show(info: PlaceInfo) {
        screen.notice = null
        screen.detent = SheetDetent.MEDIUM
        engine.showPin(info.point)
        engine.animateTo(CameraState(info.point, PLACE_ZOOM))
        places.showCard(info)
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
        SheetPanel(
            search, places, actions, route = route, fuel = fuel, navStart = navStart,
            quick = quick, history = history, onEmergency = ::openEmergency, tracks = tracks,
            hazard = hazardCard.card,
        )
    }

    private companion object {
        const val PLACE_ZOOM = 15.0
        const val EXPORT_NAME = "mapas"
    }
}
