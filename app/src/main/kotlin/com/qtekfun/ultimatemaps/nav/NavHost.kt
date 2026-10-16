package com.qtekfun.ultimatemaps.nav

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import com.qtekfun.ultimatemaps.core.map.CameraPadding
import com.qtekfun.ultimatemaps.core.map.CameraState
import com.qtekfun.ultimatemaps.core.map.MapEngine
import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
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
    /**
     * Parts of the map covered by panels now (a side column at the start, the bottom half in tabletop; none in portrait):
     * the follow camera and the overview framing use the free part of the map, not the whole window.
     */
    private val coveredPx: () -> CameraPadding = { CameraPadding.NONE },
    /** Battery saver on: the marker and the camera are pushed at most at [PowerSavePolicy.SAVER_FPS]. */
    private val powerSave: () -> Boolean = {
        (activity.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode == true
    },
) {
    /** Marker and camera, one push per display frame (see [NavPoseDriver]). */
    private val pose = NavPoseDriver(engine)
    private var lastNav: com.qtekfun.ultimatemaps.core.nav.NavState? = null
    private var geometryRevision = -1

    /** Fast, straight and far from a maneuver: the arrow and the camera may be redrawn less often (see [CruiseDetector]). */
    private val cruise = CruiseDetector()
    private var routeGeometry: RouteGeometry? = null

    /** The redraws a second now: the chosen rate, or the cruising one while [cruise] says so. Compose state: the display hint follows it. */
    private var targetFps by androidx.compose.runtime.mutableIntStateOf(com.qtekfun.ultimatemaps.core.voice.NavSettings.DEFAULT_FRAME_RATE)

    private fun navSettings() = com.qtekfun.ultimatemaps.voice.VoiceModule.settings(activity).settings.value

    private fun fpsFor(s: com.qtekfun.ultimatemaps.core.voice.NavSettings): Int =
        if (cruise.cruising && s.cruiseFrameRate != com.qtekfun.ultimatemaps.core.voice.NavSettings.CRUISE_SAME) minOf(s.frameRate, s.cruiseFrameRate) else s.frameRate

    /** The minimum time between pushes now; also tells the pose how long one frame may count for. */
    private fun frameIntervalMillis(saver: Boolean): Long {
        val interval = PowerSavePolicy.minIntervalMillis(fpsFor(navSettings()), saver)
        pose.maxStepMillis = maxOf(PuckInterpolator.MAX_STEP_MILLIS, 2 * interval)
        return interval
    }
    private val frames = FrameLoop()

    private var routeRevisionShown = -1
    private var wasActive = false
    private var wasFollowing = true
    private var wasOverview = false
    private var wasView3d: Boolean? = null
    private var buildingsShown = false

    /** The next camera move is an ease into a new view (start, recenter, 2D/3D switch): slower than a follow update. */
    private var easePending = true

    /** Where the phone's compass heading comes from (null: none); the arrow uses it while the vehicle is standing. */
    fun setDeviceHeading(provider: () -> Float?) {
        pose.deviceHeading = provider
    }

    /** The compass moved: a standing vehicle has no frame loop running, so draw the arrow now. */
    fun onDeviceHeadingChanged() {
        if (!active) return
        // A moving vehicle's arrow follows the course, not the compass: the reading changes nothing, and drawing for it would
        // bypass the frame rate (and the cruise rate) with a push per sensor event.
        if (!pose.usesCompass) return
        pose.frame(now(), PowerSavePolicy.minIntervalMillis(fpsFor(navSettings()), frames.saverOn))
    }

    /** True while there is a navigation (or its arrival summary) on screen. */
    val active: Boolean get() = screen.ui.value.active

    init {
        engine.setCameraGestureListener { screen.onUserMovedMap() }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                routeRevisionShown = -1 // the style may have been reloaded while stopped: draw the line again
                try {
                    screen.ui.collect(::render)
                } finally {
                    frames.stop() // not started: no frame callbacks (screen off, app in the background)
                }
            }
        }
    }

    /** Map side effects of one UI snapshot. Runs on the main thread. */
    internal fun render(ui: NavUi) {
        if (!ui.active) {
            if (wasActive) {
                frames.stop()
                pose.reset()
                lastNav = null
                geometryRevision = -1
                engine.clearRoute()
                engine.setBuildings3d(false)
                engine.setNavigating(false)
                engine.setUserHeading(null)
                engine.showUserLocation(null)
                // Ease back to the flat north-up view (no tilt, no padding), keeping the centre and the zoom.
                val view = engine.cameraState()
                engine.animateTo(CameraState(view.center, view.zoom), NavCamera.LEAVE_MILLIS)
                camera.forget()
                routeRevisionShown = -1
                cruise.reset()
                routeGeometry = null
            }
            wasActive = false
            wasFollowing = true
            wasOverview = false
            wasView3d = null
            buildingsShown = false
            easePending = true
            return
        }
        if (!wasActive) engine.setNavigating(true)
        wasActive = true
        val nav = ui.nav ?: return
        val buildings = ui.view3d && ui.buildings3d
        if (buildings != buildingsShown) {
            engine.setBuildings3d(buildings)
            buildingsShown = buildings
        }
        if (nav.routeRevision != routeRevisionShown) {
            screen.navigation.route.value?.let {
                val before = now()
                engine.showRoute(it.geometry, fit = false)
                com.qtekfun.ultimatemaps.core.nav.NavDiagnostics.routePainted(nav.routeRevision, System.currentTimeMillis(), now() - before)
            }
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
        val t = now()
        val fresh = nav !== lastNav
        if (fresh) {
            if (nav.routeRevision != geometryRevision || lastNav == null) {
                routeGeometry = screen.navigation.route.value?.geometry?.takeIf { it.size >= 2 }?.let(::RouteGeometry)
                pose.setRoute(routeGeometry)
                geometryRevision = nav.routeRevision
            }
            pose.onFix(nav, t)
            lastNav = nav
            updateCruise(nav, t)
        }
        if (ui.following) {
            val modeChanged = wasView3d != null && wasView3d != ui.view3d
            if (modeChanged || !wasFollowing) easePending = true
            if (fresh || modeChanged || !wasFollowing) {
                val target = camera.target(nav, mode3d = ui.view3d, screenHeightPx = screenHeightPx(), covered = coveredPx())
                pose.retarget(target, transition = easePending, current = engine.cameraState())
                easePending = false
            }
        } else {
            camera.reset()
            pose.release()
        }
        pose.frame(t)
        frames.start()
        wasFollowing = ui.following
        wasView3d = ui.view3d
    }

    /** Re-evaluates cruising for a fresh navigation snapshot and tells the display hint the new rate. */
    private fun updateCruise(nav: com.qtekfun.ultimatemaps.core.nav.NavState, t: Long) {
        val g = routeGeometry
        val steady = nav.status == com.qtekfun.ultimatemaps.core.nav.NavStatus.ON_ROUTE && !nav.estimated && !nav.inTunnel
        cruise.update(
            t, nav.speedMps, nav.nextManeuver?.distanceMeters,
            if (g != null) CruiseDetector.headingChange(g, nav.traveledMeters) else Double.MAX_VALUE, steady,
        )
        targetFps = fpsFor(navSettings())
    }

    /** One display frame: the marker and the camera at their interpolated place. Public for the tests. */
    internal fun frame(nowMillis: Long) {
        pose.frame(nowMillis, frameIntervalMillis(powerSave()))
    }

    /**
     * Posts a Choreographer callback per display frame while the marker or the camera still has something to do, and
     * nothing otherwise (a standing car, the screen off, the navigation over): no work and no wake-ups.
     */
    private inner class FrameLoop : android.view.Choreographer.FrameCallback {
        private var posted = false
        private var saver = false
        val saverOn: Boolean get() = saver
        private var saverCheckedAt = Long.MIN_VALUE
        private var lastFrameAt = Long.MIN_VALUE

        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val post = Runnable { android.view.Choreographer.getInstance().postFrameCallback(this) }

        /** Posts the next frame. After a long [afterMillis] (a low frame rate on purpose) the wait is a timer, not a wake-up every display frame. */
        fun start(afterMillis: Long = 0L) {
            if (posted || !pose.needsFrames(now())) return
            posted = true
            if (afterMillis > 100L) handler.postDelayed(post, afterMillis) else post.run()
        }

        fun stop() {
            if (!posted) return
            posted = false
            handler.removeCallbacks(post)
            android.view.Choreographer.getInstance().removeFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            posted = false
            val t = now()
            if (saverCheckedAt == Long.MIN_VALUE || t - saverCheckedAt > PowerSavePolicy.CHECK_MILLIS) {
                saver = powerSave()
                saverCheckedAt = t
            }
            // The user took the camera: stop moving it at once, before the flow delivers the new state.
            if (!screen.ui.value.following) pose.release()
            val interval = frameIntervalMillis(saver)
            pose.frame(t, interval)
            // A clock that did not advance (a test's frozen clock) has nothing more to animate: do not spin.
            val advanced = t != lastFrameAt
            lastFrameAt = t
            if (advanced && screen.ui.value.active) start(afterMillis = interval - 16L)
        }
    }

    /** Frames what is left of the route (from the point nearest to the user) with room for the banner and the bottom panel. */
    private fun frameRemainingRoute(position: LatLon) {
        val geometry = screen.navigation.route.value?.geometry ?: return
        val remaining = remainingRoute(geometry, position)
        if (remaining.size < 2) return
        val cover = coveredPx()
        val h = (screenHeightPx() - cover.top - cover.bottom).coerceAtLeast(1)
        val side = (h * 0.04).toInt()
        engine.frameRoute(
            remaining,
            CameraPadding(
                left = cover.left + side, top = cover.top + (h * 0.28).toInt(),
                right = cover.right + side, bottom = cover.bottom + (h * 0.24).toInt(),
            ),
        )
    }

    /** The navigation screen; compose it over the map (it draws nothing when there is no navigation to show). */
    @Composable
    fun Overlay(dark: Boolean) {
        val ui by screen.ui.collectAsState()
        KeepScreenOn(ui.active && ui.phase != NavPhase.ARRIVED)
        val settingsRate = com.qtekfun.ultimatemaps.voice.VoiceModule.settings(activity).settings.collectAsState().value.frameRate
        // The panel is asked for the rate in use (never below 10: the nearest mode it offers is chosen); the settings value is read so a change applies at once.
        RefreshRateHint(if (ui.active) maxOf(10, minOf(settingsRate, if (targetFps > 0) targetFps else settingsRate)) else null)
        ShowOverLockScreen(ui.active)
        HideStatusBar(ui.active && com.qtekfun.ultimatemaps.voice.VoiceModule.settings(activity).settings.collectAsState().value.hideStatusBar)
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
                onAcceptSuggestion = screen::acceptSuggestion,
                onDismissSuggestion = screen::dismissSuggestion,
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
 * Asks the display for [fps] while it is non-null (the navigation is on screen), by `preferredRefreshRate` of the window; null
 * gives the choice back to the system. A hint: on a variable-refresh (LTPO) display a lower rate costs less power, and the system
 * picks the nearest rate the panel supports.
 */
@Composable
fun RefreshRateHint(fps: Int?) {
    val window = androidx.activity.compose.LocalActivity.current?.window
    androidx.compose.runtime.DisposableEffect(window, fps) {
        window?.let { w -> w.attributes = w.attributes.also { it.preferredRefreshRate = fps?.toFloat() ?: 0f } }
        onDispose { window?.let { w -> w.attributes = w.attributes.also { it.preferredRefreshRate = 0f } } }
    }
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

/**
 * Hides the system status bar while [on] (Settings > Driving focus), so the notification icons of other apps do not
 * distract. A swipe from the top shows the bars for a moment (transient). The navigation screen pads with the
 * status-bar inset ignoring visibility, so nothing jumps when the bar goes. Shown again when [on] ends or the
 * composition leaves (the activity stops).
 */
@Composable
fun HideStatusBar(on: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(on, view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        if (on && controller != null) {
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
        onDispose { if (on) controller?.show(androidx.core.view.WindowInsetsCompat.Type.statusBars()) }
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

/** Frame-rate policy of the marker and camera updates. */
object PowerSavePolicy {
    /** Normal: up to 60 pushes per second (on a 120 Hz display, every second frame). */
    const val NORMAL_FPS = 60
    const val NORMAL_MIN_INTERVAL_MILLIS = 1_000L / NORMAL_FPS - 2

    /** Battery saver: at most 30 pushes per second. */
    const val SAVER_FPS = 30
    const val SAVER_MIN_INTERVAL_MILLIS = 1_000L / SAVER_FPS - 2
    const val CHECK_MILLIS = 5_000L

    /** Minimum time between pushes for a chosen [fps]; the battery saver never allows more than [SAVER_FPS]. */
    fun minIntervalMillis(fps: Int, saver: Boolean): Long {
        val effective = (if (saver) minOf(fps, SAVER_FPS) else fps).coerceIn(1, 120)
        return 1_000L / effective - 2
    }
}
