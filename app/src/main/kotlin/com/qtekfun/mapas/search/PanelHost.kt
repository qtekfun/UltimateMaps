package com.qtekfun.mapas.search

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.lifecycleScope
import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.fuel.FuelRepository
import com.qtekfun.mapas.core.fuel.FuelSettingsStore
import com.qtekfun.mapas.core.geo.LatLon
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
import com.qtekfun.mapas.places.GeoShare
import com.qtekfun.mapas.places.PlaceInfo
import com.qtekfun.mapas.places.PlacesController
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
    /** The navigation model; with it the route card offers "Start" and "Simulate". Null: preview only. */
    private val navScreen: NavScreenController? = null,
) {
    /** Last known user position, in memory only; used to sort saved places by distance. */
    var userLocation: LatLon? = null
        set(value) {
            field = value
            route.onUserLocation()
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
    )

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
    )

    val fuelCard = FuelCardController(
        repository = fuelRepository,
        route = route,
        places = places,
        card = FuelCardState(),
        category = { activity.getString(R.string.fuel_category) },
        onOpened = { screen.notice = null; screen.detent = SheetDetent.MEDIUM },
    )

    private val fuelLayer = FuelMapLayer(
        scope = activity.lifecycleScope,
        io = Dispatchers.Default,
        repository = fuelRepository,
        settings = fuelSettings.settings,
        render = engine::showFuel,
    )

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
    }

    fun onDestroy() {
        fuelLayer.stop()
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
        engine.setViewportListener(fuelLayer::onViewport)
        fuelLayer.start()
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
        if (route.state.pickingOrigin) route.pickOrigin(result.point, result.name) else show(result.toPlaceInfo())
    }

    private fun share(info: PlaceInfo) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "${info.name}\n${GeoShare.uri(info.point, info.name)}")
        activity.startActivity(Intent.createChooser(send, null))
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
        )
        val fuel = remember {
            FuelCardHost(
                state = fuelCard.card,
                mapFuelId = { fuelSettings.settings.value.mapFuel },
                fuelName = fuelName,
                updatedMillis = { fuelRepository.lastUpdateMillis.value },
                now = System::currentTimeMillis,
                onGo = fuelCard::go, onAddStop = fuelCard::addStop, onSave = fuelCard::save,
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
        SheetPanel(search, places, actions, route = route, fuel = fuel, navStart = navStart, tracks = tracks)
    }

    private companion object {
        const val PLACE_ZOOM = 15.0
        const val EXPORT_NAME = "mapas"
    }
}
