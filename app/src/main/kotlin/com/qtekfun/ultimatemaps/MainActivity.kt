package com.qtekfun.ultimatemaps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.cameras.CameraAlertBanner
import com.qtekfun.ultimatemaps.cameras.LocalAlertBanner
import com.qtekfun.ultimatemaps.cameras.LocalIncidentBanner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.CameraState
import com.qtekfun.ultimatemaps.core.map.MapTheme
import com.qtekfun.ultimatemaps.link.LinkHandler
import com.qtekfun.ultimatemaps.link.LinkOutcome
import com.qtekfun.ultimatemaps.link.pinPoint
import com.qtekfun.ultimatemaps.location.AndroidLocationSource
import com.qtekfun.ultimatemaps.map.MapFiles
import com.qtekfun.ultimatemaps.map.MapLibreEngine
import com.qtekfun.ultimatemaps.map.PrefsCameraStateStore
import com.qtekfun.ultimatemaps.nav.NavHost
import com.qtekfun.ultimatemaps.regions.RegionsActivity
import com.qtekfun.ultimatemaps.settings.SettingsActivity
import com.qtekfun.ultimatemaps.ui.MapScreen
import com.qtekfun.ultimatemaps.ui.MapScreenState
import com.qtekfun.ultimatemaps.ui.Notice
import com.qtekfun.ultimatemaps.link.toPlaceInfo
import com.qtekfun.ultimatemaps.search.PanelHost
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import com.qtekfun.ultimatemaps.cameras.HazardDescriber
import com.qtekfun.ultimatemaps.cameras.HazardsEnv
import com.qtekfun.ultimatemaps.core.fuel.FuelTypes

class MainActivity : ComponentActivity() {
    private val state = MapScreenState()
    private lateinit var files: MapFiles
    private lateinit var engine: MapLibreEngine
    private lateinit var location: AndroidLocationSource
    private var centerOnNextFix = false
    private lateinit var panel: PanelHost
    private lateinit var navHost: NavHost

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        (application as MapasApp).refreshCameraAlerts() // free-driving alerts need the permission too
        if (grants.values.any { it }) startLocation() else state.notice = Notice.LocationDenied
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        files = MapFiles(this)
        location = AndroidLocationSource(this)
        // The engine is built before the first composition so the first frame shows the last saved camera.
        engine = MapLibreEngine(
            context = this,
            files = files,
            store = PrefsCameraStateStore(this),
            initialTheme = if (isNight()) MapTheme.DARK else MapTheme.LIGHT,
            onCameraIdle = state::onCamera,
        )
        lifecycle.addObserver(engine)
        // The navigation voice prompts use the media volume: the volume keys must control it.
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        val app = application as MapasApp
        panel = PanelHost(
            this, engine, state,
            fuelRepository = app.fuel.repository,
            fuelSettings = app.fuelSettings,
            fuelName = { id -> FuelTypes.byId(id)?.displayName ?: id },
            hazards = HazardsEnv(
                app.cameraSettings.settings, app.cameraData.repository, app.incidents.repository,
                HazardDescriber(this, app.cameraData.repository, app.incidents.repository),
            ),
            chargers = com.qtekfun.ultimatemaps.chargers.ChargersEnv(app.chargerSettings.settings, app.chargerData.repository),
            navScreen = app.navScreen,
        )
        navHost = NavHost(this, engine, app.navScreen)
        // Low-emission zones: drawn under the route line while the switch and the map toggle are on.
        com.qtekfun.ultimatemaps.zbe.ZbeMapLayer(lifecycleScope, app.zbeData.repository, app.zbeSettings.settings, engine::showLowEmissionZones).start()
        panel.onRequestLocation = ::onLocate
        state.onOpenMaps = { startActivity(Intent(this, RegionsActivity::class.java)) }
        state.onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
        state.onCamera(engine.cameraState())

        setContent {
            val dark = isSystemInDarkTheme()
            LaunchedEffect(dark) { engine.setTheme(if (dark) MapTheme.DARK else MapTheme.LIGHT) }
            val navUi by navHost.uiState()
            val tripUi by app.transitTrip.ui.collectAsState()
            CompositionLocalProvider(
                LocalAlertBanner provides app.alertBanner.state, LocalIncidentBanner provides app.incidentBanner,
                com.qtekfun.ultimatemaps.zbe.LocalZbeBanner provides app.zbeBanner,
            ) {
            MapasTheme(darkTheme = dark) {
                MapScreen(
                    state = state, onLocate = ::onLocate, onResetNorth = engine::resetNorth, sheetPanel = { panel.Content() },
                    navigating = navUi.active || tripUi.active, navSheet = panel.cardOverNavigation, overlay = {
                        navHost.Overlay(dark)
                        com.qtekfun.ultimatemaps.transit.follow.TransitTripOverlay(app.transitTrip, dark) { legs -> engine.showTransitItinerary(legs) }
                        // Driving without a navigation: the same alert, under the map controls (the navigation screen draws its own).
                        if (!navUi.active) CameraAlertBanner(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 96.dp))
                    },
                ) {
                    AndroidView(factory = { engine.view }, modifier = Modifier.fillMaxSize())
                }
            }
            }
        }
        if (savedInstanceState == null) {
            handleLink(intent)
            panel.handleShortcut(intent?.action)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
        panel.handleShortcut(intent.action)
    }

    override fun onStart() {
        super.onStart()
        state.hasTiles = files.pmtilesList().isNotEmpty()
        panel.onStart()
        engine.refreshTilesIfChanged() // back from "Maps" with a region downloaded or deleted
        (application as MapasApp).navScreen.refreshResumable() // a trip interrupted by the process dying
        (application as MapasApp).transitTrip.refreshResumable() // the same for a step-by-step transit trip
        if (state.locating && hasLocationPermission()) startLocation()
    }

    override fun onDestroy() {
        panel.onDestroy()
        super.onDestroy()
    }

    override fun onStop() {
        location.stop() // no background location in the viewer; navigation will use a foreground service
        super.onStop()
    }

    // --- Links (RF-11) ---

    private fun handleLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val result = LinkHandler.handle(intent.dataString)
        engine.showPin(result.pinPoint()) // always replaces (or clears) the pin of the previous link
        when (val outcome = result) {
            is LinkOutcome.ShowPlace -> {
                // The same card as a search result (Save, Route, Share, Home/Work); it also moves the map and clears the notice.
                panel.showLinkPlace(outcome.toPlaceInfo(), (outcome.zoom ?: DEFAULT_LINK_ZOOM).coerceIn(0.0, 22.0))
            }
            is LinkOutcome.Search -> {
                outcome.near?.let { engine.animateTo(CameraState(it, (outcome.zoom ?: DEFAULT_LINK_ZOOM).coerceIn(0.0, 22.0))) }
                // Offline search runs as if the text had been typed in the search box. With no region installed
                // (known only after the first scan) a neutral notice says the link could only move the map.
                state.notice = if (panel.search.state.regionsAvailable == false) Notice.Search(outcome.query) else null
                panel.runLinkSearch(outcome.query)
            }
            LinkOutcome.ShortLinkNotResolved -> state.notice = Notice.ShortLink
            LinkOutcome.Unrecognized -> state.notice = Notice.Unrecognized
        }
    }

    // --- Location ---

    private fun onLocate() {
        when {
            !location.isAvailable -> state.notice = Notice.LocationUnavailable
            hasLocationPermission() -> {
                centerOnNextFix = true
                startLocation()
            }
            else -> {
                centerOnNextFix = true
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }

    private fun startLocation() {
        state.locating = true
        location.lastKnown()?.let { onFix(it.point) }
        location.start { fix ->
            (application as MapasApp).recording.onFix(fix) // only queued while a recording is running
            onFix(fix.point)
        }
    }

    private fun onFix(point: LatLon) {
        panel.userLocation = point
        if (navHost.active) return // while navigating the map shows the follower's position and camera
        engine.showUserLocation(point)
        if (centerOnNextFix) {
            centerOnNextFix = false
            engine.animateTo(CameraState(point, maxOf(engine.cameraState().zoom, 15.0)))
        }
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private companion object {
        const val DEFAULT_LINK_ZOOM = 15.0
    }
}
