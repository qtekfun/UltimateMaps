package com.qtekfun.mapas.nav

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.geo.distanceTo
import com.qtekfun.mapas.core.map.CameraPadding
import com.qtekfun.mapas.core.map.CameraState
import com.qtekfun.mapas.core.map.MapEngine
import kotlinx.coroutines.launch

/**
 * Connects the navigation to one activity: draws [NavScreen] (see [Overlay]), keeps the screen on while navigating,
 * makes the map camera follow the user (tilted 3D "course up" or flat 2D, see [NavCamera]) and draws the route line,
 * the heading arrow and the optional 3D buildings, and listens for the user moving the map. Create it
 * in `onCreate`; it holds nothing the activity cannot lose (the navigation itself lives in [NavScreenController]
 * and survives the activity), so a recreated activity simply creates a new one and the screen comes back.
 */
class NavHost(
    private val activity: ComponentActivity,
    private val engine: MapEngine,
    private val screen: NavScreenController,
    private val camera: NavCamera = NavCamera(),
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
    /** Height of the map view in pixels (for the camera padding). */
    private val screenHeightPx: () -> Int = { activity.resources.displayMetrics.heightPixels },
) {
    private var routeRevisionShown = -1
    private var wasActive = false
    private var wasFollowing = true
    private var wasOverview = false
    private var wasView3d: Boolean? = null
    private var buildingsShown = false

    /** The next camera move is an ease into a new view (start, recenter, 2D/3D switch): slower than a follow update. */
    private var easePending = true

    /** True while there is a navigation (or its arrival summary) on screen. */
    val active: Boolean get() = screen.ui.value.active

    init {
        engine.setCameraGestureListener { screen.onUserMovedMap() }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                routeRevisionShown = -1 // the style may have been reloaded while stopped: draw the line again
                screen.ui.collect(::render)
            }
        }
    }

    /** Map side effects of one UI snapshot. Runs on the main thread. */
    internal fun render(ui: NavUi) {
        if (!ui.active) {
            if (wasActive) {
                engine.clearRoute()
                engine.setBuildings3d(false)
                engine.setUserHeading(null)
                engine.showUserLocation(null)
                // Ease back to the flat north-up view (no tilt, no padding), keeping the centre and the zoom.
                val view = engine.cameraState()
                engine.animateTo(CameraState(view.center, view.zoom), NavCamera.LEAVE_MILLIS)
                camera.forget()
                routeRevisionShown = -1
            }
            wasActive = false
            wasFollowing = true
            wasOverview = false
            wasView3d = null
            buildingsShown = false
            easePending = true
            return
        }
        wasActive = true
        val nav = ui.nav ?: return
        val buildings = ui.view3d && ui.buildings3d
        if (buildings != buildingsShown) {
            engine.setBuildings3d(buildings)
            buildingsShown = buildings
        }
        if (nav.routeRevision != routeRevisionShown) {
            screen.navigation.route.value?.let { engine.showRoute(it.geometry, fit = false) }
            routeRevisionShown = nav.routeRevision
        }
        if (ui.overview && !wasOverview) {
            frameRemainingRoute(nav.position)
        } else if (!ui.overview && wasOverview) {
            // The overview drew only what is left of the line: draw the whole line again.
            screen.navigation.route.value?.let { engine.showRoute(it.geometry, fit = false) }
            routeRevisionShown = nav.routeRevision
        }
        wasOverview = ui.overview
        var heading = camera.heading ?: nav.bearingDegrees.toDouble()
        if (ui.following) {
            val modeChanged = wasView3d != null && wasView3d != ui.view3d
            if (modeChanged || !wasFollowing) easePending = true
            val target = camera.next(nav, now(), force = !wasFollowing || modeChanged, mode3d = ui.view3d, screenHeightPx = screenHeightPx())
            if (target != null) {
                engine.animateTo(target, if (easePending) NavCamera.TRANSITION_MILLIS else camera.animationMillis)
                easePending = false
            }
            heading = camera.heading ?: heading
        } else {
            camera.reset()
        }
        // The arrow first: the user source is pushed once, by showUserLocation, with the heading already set.
        engine.setUserHeading(heading.toFloat())
        engine.showUserLocation(nav.position)
        wasFollowing = ui.following
        wasView3d = ui.view3d
    }

    /** Frames what is left of the route (from the point nearest to the user) with room for the banner and the bottom panel. */
    private fun frameRemainingRoute(position: LatLon) {
        val geometry = screen.navigation.route.value?.geometry ?: return
        val remaining = remainingRoute(geometry, position)
        if (remaining.size < 2) return
        val h = screenHeightPx()
        val side = (h * 0.04).toInt()
        engine.frameRoute(remaining, CameraPadding(left = side, top = (h * 0.28).toInt(), right = side, bottom = (h * 0.24).toInt()))
    }

    /** The navigation screen; compose it over the map (it draws nothing when there is no navigation to show). */
    @Composable
    fun Overlay(dark: Boolean) {
        val ui by screen.ui.collectAsState()
        KeepScreenOn(ui.active && ui.phase != NavPhase.ARRIVED)
        ShowOverLockScreen(ui.active)
        NavScreen(
            ui = ui,
            actions = NavActions(
                onStop = screen::stop,
                onRecenter = screen::recenter,
                onGlove = screen::setGlove,
                onView3d = screen::setView3d,
                onVoice = screen::setVoice,
                onCameraVoice = screen::setCameraVoice,
                onOverview = screen::showOverview,
                onFaster = screen::simulationFaster,
                onSlower = screen::simulationSlower,
                onExit = screen::stop,
                onResume = { screen.resume() },
                onDiscard = screen::discardResumable,
                onShareEta = {
                    EtaShare.text(activity.resources, screen.ui.value, activity.resources.configuration.locales[0])
                        ?.let { activity.startActivity(EtaShare.intent(it)) }
                },
            ),
            dark = dark,
        )
    }

    /** The UI state as Compose state (for the activity to hide the search sheet while navigating). */
    @Composable
    fun uiState(): State<NavUi> = screen.ui.collectAsState()
}

/**
 * Keeps the screen on while [on] by setting the window flag `FLAG_KEEP_SCREEN_ON` (not a wake lock: no permission,
 * and the system clears it by itself when the window goes away).
 */
@Composable
fun KeepScreenOn(on: Boolean) {
    val context = LocalContext.current
    DisposableEffect(on, context) {
        val window = context.findActivity()?.window
        if (on) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (on) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/**
 * Lets the activity be seen over the lock screen while [on] (a trip is running), like other navigation apps: the route
 * and the next turn stay visible with the phone locked, without unlocking it. It does not unlock anything: the keyguard
 * stays and any other app or action still needs the PIN. Off again when the trip ends.
 */
@Composable
fun ShowOverLockScreen(on: Boolean) {
    val context = LocalContext.current
    DisposableEffect(on, context) {
        val activity = context.findActivity()
        if (on && activity != null) setShowWhenLocked(activity, true)
        onDispose { if (on && activity != null) setShowWhenLocked(activity, false) }
    }
}

@Suppress("DEPRECATION")
private fun setShowWhenLocked(activity: android.app.Activity, show: Boolean) {
    if (android.os.Build.VERSION.SDK_INT >= 27) {
        activity.setShowWhenLocked(show)
    } else if (show) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
    } else {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
    }
}

private fun Context.findActivity(): android.app.Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

/** The part of [geometry] from the vertex nearest to [position] to the end, with [position] as its first point. */
internal fun remainingRoute(geometry: List<LatLon>, position: LatLon): List<LatLon> {
    if (geometry.isEmpty()) return emptyList()
    var best = 0
    var bestDistance = Double.MAX_VALUE
    geometry.forEachIndexed { i, p ->
        val d = p.distanceTo(position)
        if (d < bestDistance) {
            bestDistance = d
            best = i
        }
    }
    return listOf(position) + geometry.subList(best, geometry.size)
}
