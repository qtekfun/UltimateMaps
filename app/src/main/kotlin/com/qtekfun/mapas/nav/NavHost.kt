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
import com.qtekfun.mapas.core.map.MapEngine
import kotlinx.coroutines.launch

/**
 * Connects the navigation to one activity: draws [NavScreen] (see [Overlay]), keeps the screen on while navigating,
 * makes the map camera follow the user and draws the route line, and listens for the user moving the map. Create it
 * in `onCreate`; it holds nothing the activity cannot lose (the navigation itself lives in [NavScreenController]
 * and survives the activity), so a recreated activity simply creates a new one and the screen comes back.
 */
class NavHost(
    private val activity: ComponentActivity,
    private val engine: MapEngine,
    private val screen: NavScreenController,
    private val camera: NavCamera = NavCamera(),
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private var routeRevisionShown = -1
    private var wasActive = false
    private var wasFollowing = true

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
                engine.showUserLocation(null)
                engine.resetNorth()
                camera.reset()
                routeRevisionShown = -1
            }
            wasActive = false
            wasFollowing = true
            return
        }
        wasActive = true
        val nav = ui.nav ?: return
        if (nav.routeRevision != routeRevisionShown) {
            screen.navigation.route.value?.let { engine.showRoute(it.geometry, fit = false) }
            routeRevisionShown = nav.routeRevision
        }
        engine.showUserLocation(nav.position)
        if (ui.following) {
            val target = camera.next(nav, now(), force = !wasFollowing)
            if (target != null) engine.animateTo(target, camera.animationMillis)
        } else {
            camera.reset()
        }
        wasFollowing = ui.following
    }

    /** The navigation screen; compose it over the map (it draws nothing when there is no navigation to show). */
    @Composable
    fun Overlay(dark: Boolean) {
        val ui by screen.ui.collectAsState()
        KeepScreenOn(ui.active && ui.phase != NavPhase.ARRIVED)
        NavScreen(
            ui = ui,
            actions = NavActions(
                onStop = screen::stop,
                onRecenter = screen::recenter,
                onGlove = screen::setGlove,
                onFaster = screen::simulationFaster,
                onSlower = screen::simulationSlower,
                onExit = screen::stop,
                onResume = { screen.resume() },
                onDiscard = screen::discardResumable,
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

private fun Context.findActivity(): android.app.Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}
