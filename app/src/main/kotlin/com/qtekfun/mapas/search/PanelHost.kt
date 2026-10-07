package com.qtekfun.mapas.search

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.lifecycleScope
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.CameraState
import com.qtekfun.mapas.core.map.MapEngine
import com.qtekfun.mapas.places.DocumentLaunchers
import com.qtekfun.mapas.places.GeoFormat
import com.qtekfun.mapas.places.GeoShare
import com.qtekfun.mapas.places.PlaceInfo
import com.qtekfun.mapas.places.PlacesController
import com.qtekfun.mapas.places.openPlacesService
import com.qtekfun.mapas.places.toPlaceInfo
import com.qtekfun.mapas.core.search.SearchResult
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

    val places = PlacesController(
        scope = activity.lifecycleScope,
        io = Dispatchers.IO,
        service = lazy { openPlacesService(activity) },
        near = { userLocation ?: engine.cameraState().center },
        onMarkers = engine::showMarkers,
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
        search.close()
        route.close()
    }

    private fun input(uri: Uri) = activity.contentResolver.openInputStream(uri) ?: error("cannot open document")
    private fun output(uri: Uri) = activity.contentResolver.openOutputStream(uri, "wt") ?: error("cannot open document")

    init {
        engine.setMapTapListener { route.pickOrigin(it, null) }
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
        SheetPanel(search, places, actions, route = route)
    }

    private companion object {
        const val PLACE_ZOOM = 15.0
        const val EXPORT_NAME = "mapas"
    }
}
