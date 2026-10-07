package com.qtekfun.mapas.nav

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.nav.NavEvent
import com.qtekfun.mapas.core.nav.NavProblem
import com.qtekfun.mapas.core.nav.NavState
import com.qtekfun.mapas.core.nav.NavStatus
import com.qtekfun.mapas.core.nav.NavTrip
import com.qtekfun.mapas.core.nav.NavigationController
import com.qtekfun.mapas.core.nav.withStops
import com.qtekfun.mapas.core.routing.RoutePlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/** What the navigation screen shows, in the six situations the driver can be in. */
enum class NavPhase { ON_ROUTE, OFF_ROUTE, REROUTING, NO_SIGNAL, STOP_REACHED, ARRIVED }

/** The trip in numbers, shown when the destination is reached. */
data class NavSummary(val distanceMeters: Double, val durationMillis: Long, val stopsReached: Int, val simulated: Boolean)

/**
 * Everything the navigation screen draws, as one immutable value. [phase] is null when there is no navigation to
 * show; [nav] is the last snapshot (kept after the arrival, when the controller has already been stopped).
 */
data class NavUi(
    val phase: NavPhase? = null,
    val nav: NavState? = null,
    val problem: NavProblem? = null,
    val simulated: Boolean = false,
    val simulationSpeedKmh: Int = NavSimulation.DEFAULT_SPEED_KMH,
    /** The camera follows the user; false after the user moved the map by hand (the screen offers "recenter"). */
    val following: Boolean = true,
    /** Estimated arrival, epoch millis; 0 when unknown. */
    val etaMillis: Long = 0L,
    val summary: NavSummary? = null,
    val stopsReached: Int = 0,
    /** A navigation saved before the process died is waiting to be resumed (shown only while [phase] is null). */
    val resumable: Boolean = false,
    /** Glove mode (RF-06): big targets, high contrast, no fine gestures. */
    val glove: Boolean = false,
) {
    val active: Boolean get() = phase != null
}

/** Starts and stops the foreground service that keeps the navigation alive; a fake in tests. */
interface NavServiceControl {
    fun start()
    fun resume()
    fun stop()
}

/** Where the glove-mode preference lives. Settings (someone else's screen) can bind to the same value later. */
interface NavUiPrefs {
    var glove: Boolean
}

class InMemoryNavUiPrefs(override var glove: Boolean = false) : NavUiPrefs

/**
 * The model behind the navigation screen. It lives as long as the application (like [NavigationController], which
 * keeps following while the activity is gone), so recreating or leaving the activity loses nothing: the new screen
 * just collects [ui] again. It
 *
 * - starts a trip from a route that already carries guidance ([begin]; the intermediate stops go into the plan
 *   with `withStops`), real or simulated, and stops it ([stop]);
 * - turns the follower's state, problems and stop events into [NavUi] (including the transient "stop reached"
 *   banner and the arrival summary, which must survive the service ending the controller);
 * - offers to resume a trip saved before the process died ([refreshResumable], [resume]);
 * - passes prompts and events to the registered [NavEventSink]s (the voice plugs in there).
 *
 * Plain Kotlin on coroutines, tested on the JVM.
 */
class NavScreenController(
    private val scope: CoroutineScope,
    private val controller: NavigationController,
    private val simulation: NavSimulation,
    private val location: SwitchableLocationSource,
    private val service: NavServiceControl,
    private val prefs: NavUiPrefs = InMemoryNavUiPrefs(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val stopFlashMillis: Long = STOP_FLASH_MILLIS,
) {
    private val _ui = MutableStateFlow(NavUi(glove = prefs.glove))
    val ui: StateFlow<NavUi> = _ui.asStateFlow()

    private val sinks = CopyOnWriteArrayList<NavEventSink>()
    private val lock = Any()
    private var tripStartMillis = 0L
    private var flashJob: Job? = null
    private var flashUntil = 0L

    /** The native follower itself (for the service and the camera); the screen goes through [ui]. */
    val navigation: NavigationController get() = controller

    fun addSink(sink: NavEventSink) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: NavEventSink) {
        sinks.remove(sink)
    }

    init {
        scope.launch { controller.state.collect(::onState) }
        scope.launch { controller.problem.collect { p -> _ui.update { it.copy(problem = p) } } }
        scope.launch { controller.events.collect(::onEvent) }
        scope.launch { controller.announcements.collect { a -> sinks.forEach { runCatching { it.onAnnouncement(a) } } } }
    }

    /**
     * Starts following [plan] (computed WITH guidance) through the stops [via]. [simulate] feeds it with a simulated
     * walk at [speedKmh] instead of the real location, and nothing about it is saved. Returns false, changing
     * nothing, when the plan cannot be followed.
     */
    fun begin(plan: RoutePlan, via: List<LatLon>, trip: NavTrip, simulate: Boolean = false, speedKmh: Int = simulation.speedKmh): Boolean {
        val withStops = plan.withStops(via)
        synchronized(lock) {
            simulation.stop()
            if (!simulate) location.endSimulation() else location.beginSimulation()
            tripStartMillis = clock()
            flashJob?.cancel()
            flashUntil = 0L
            _ui.value = NavUi(
                phase = NavPhase.ON_ROUTE, simulated = simulate, simulationSpeedKmh = speedKmh.coerceIn(NavSimulation.MIN_SPEED_KMH, NavSimulation.MAX_SPEED_KMH),
                glove = prefs.glove,
            )
            if (!controller.start(withStops, 0.0, trip, persist = !simulate)) {
                location.endSimulation()
                _ui.value = NavUi(glove = prefs.glove)
                return false
            }
            if (simulate) simulation.start(withStops, 0.0, speedKmh) else service.start()
        }
        sinks.forEach { runCatching { it.onNavigationStarted(simulate) } }
        return true
    }

    /** The user ends the trip (or leaves the arrival summary): everything is cleaned, including the saved state. */
    fun stop() {
        val wasArrived = _ui.value.phase == NavPhase.ARRIVED
        val wasActive = _ui.value.active
        synchronized(lock) {
            simulation.stop()
            controller.stop()
            location.endSimulation()
            flashJob?.cancel()
            _ui.update { NavUi(glove = prefs.glove) }
        }
        service.stop()
        if (wasActive) sinks.forEach { runCatching { it.onNavigationEnded(wasArrived) } }
    }

    // --- Following / recenter / glove ---

    fun onUserMovedMap() = _ui.update { if (it.active && it.following) it.copy(following = false) else it }

    fun recenter() = _ui.update { if (it.active) it.copy(following = true) else it }

    fun setGlove(on: Boolean) {
        prefs.glove = on
        _ui.update { it.copy(glove = on) }
    }

    // --- Simulation speed ---

    fun simulationFaster() = changeSpeed(faster = true)

    fun simulationSlower() = changeSpeed(faster = false)

    private fun changeSpeed(faster: Boolean) {
        val ui = _ui.value
        if (!ui.simulated || ui.phase == NavPhase.ARRIVED) return
        val along = ui.nav?.traveledMeters ?: 0.0
        if (faster) simulation.faster(along) else simulation.slower(along)
        _ui.update { it.copy(simulationSpeedKmh = simulation.speedKmh) }
    }

    // --- Coming back after the process died ---

    /** Call when the activity comes to the front: shows the "resume" offer if a valid saved trip exists. */
    fun refreshResumable() {
        val offer = !controller.isActive && _ui.value.phase == null && controller.hasResumable()
        _ui.update { if (it.phase == null) it.copy(resumable = offer) else it }
    }

    /** Continues the saved trip. Returns whether there was one to continue. */
    fun resume(): Boolean {
        val ok = synchronized(lock) {
            location.endSimulation()
            tripStartMillis = clock()
            controller.resume()
        }
        _ui.update { it.copy(resumable = false) }
        if (ok) {
            service.resume()
            sinks.forEach { runCatching { it.onNavigationStarted(false) } }
        }
        return ok
    }

    /** Forgets the saved trip. */
    fun discardResumable() {
        controller.stop()
        _ui.update { it.copy(resumable = false) }
    }

    // --- From the follower ---

    private fun onState(st: NavState?) {
        if (st == null) {
            // The controller ended. After an arrival the service stops it on its own: the summary stays until dismissed.
            val ui = _ui.value
            if (!ui.active || ui.phase == NavPhase.ARRIVED) return
            val last = ui.nav
            if (last != null && last.remainingMeters <= ARRIVAL_SLACK_METERS) {
                // The ARRIVED snapshot was conflated away: the last one was a few metres from the end.
                finishArrival(last)
            } else {
                // Stopped from the notification (or the process restarted without a trip): close the screen.
                simulation.stop()
                location.endSimulation()
                sinks.forEach { runCatching { it.onNavigationEnded(false) } }
                _ui.update { NavUi(glove = prefs.glove) }
            }
            return
        }
        if (_ui.value.phase == NavPhase.ARRIVED) return
        if (st.status == NavStatus.ARRIVED) {
            finishArrival(st)
            return
        }
        _ui.update {
            it.copy(
                phase = phaseOf(st, flashActive()),
                nav = st,
                etaMillis = clock() + (st.remainingSeconds * 1000).toLong(),
                simulated = if (it.phase == null) location.isSimulated else it.simulated,
                resumable = false,
            )
        }
    }

    private fun finishArrival(st: NavState) {
        simulation.stop()
        val now = clock()
        sinks.forEach { runCatching { it.onNavigationEnded(true) } }
        _ui.update {
            it.copy(
                phase = NavPhase.ARRIVED,
                nav = st,
                etaMillis = now,
                summary = NavSummary(st.traveledMeters.coerceAtLeast(0.0), (now - tripStartMillis).coerceAtLeast(0L), it.stopsReached, it.simulated),
            )
        }
    }

    private fun onEvent(e: NavEvent) {
        sinks.forEach { runCatching { it.onEvent(e) } }
        if (e !is NavEvent.StopReached) return
        flashUntil = clock() + stopFlashMillis
        _ui.update { it.copy(stopsReached = it.stopsReached + 1, phase = if (it.phase == NavPhase.ON_ROUTE) NavPhase.STOP_REACHED else it.phase) }
        flashJob?.cancel()
        flashJob = scope.launch {
            delay(stopFlashMillis)
            _ui.update { if (it.phase == NavPhase.STOP_REACHED) it.copy(phase = NavPhase.ON_ROUTE) else it }
        }
    }

    private fun flashActive() = clock() < flashUntil

    private fun phaseOf(st: NavState, flash: Boolean): NavPhase = when (st.status) {
        NavStatus.ON_ROUTE -> if (flash) NavPhase.STOP_REACHED else NavPhase.ON_ROUTE
        NavStatus.OFF_ROUTE -> NavPhase.OFF_ROUTE
        NavStatus.REROUTING -> NavPhase.REROUTING
        NavStatus.NO_SIGNAL -> NavPhase.NO_SIGNAL
        NavStatus.ARRIVED -> NavPhase.ARRIVED
    }

    companion object {
        const val STOP_FLASH_MILLIS = 6_000L

        /** If the controller vanishes with the last snapshot this close to the end, the user had arrived. */
        const val ARRIVAL_SLACK_METERS = 60.0
    }
}
