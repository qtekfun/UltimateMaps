package com.qtekfun.mapas.core.nav

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.LocationSource
import com.qtekfun.mapas.core.routing.RoutePlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why the follower cannot get positions right now, as far as the platform can tell. */
enum class NavProblem {
    /** The location permission was revoked while navigating. */
    LOCATION_PERMISSION,

    /** Location is switched off in the system settings (GPS off). */
    LOCATION_DISABLED,
}

/** What the controller needs to ask the platform; implemented on Android by the navigation service. */
interface NavEnvironment {
    fun hasLocationPermission(): Boolean
    fun isLocationEnabled(): Boolean

    /** Battery saver is on: fewer disk writes and less notification traffic (positions are still read at full rate). */
    fun isPowerSaveMode(): Boolean
}

/** Computes a route for rerouting: from [from] through the remaining [via] stops to [destination]. Null = none. */
fun interface RouteProvider {
    suspend fun route(from: LatLon, bearingDegrees: Float?, via: List<LatLon>, destination: LatLon, trip: NavTrip): RoutePlan?
}

/**
 * Owns the one navigation in progress and exposes it to the UI and to the Android service. It is plain Kotlin on
 * coroutines (no Android types), so every behaviour is unit-tested on the JVM.
 *
 * - [start] and [resume] create a [NavigationSession]; [stop] ends it and forgets the saved state.
 * - Progress is saved through [store] (throttled) so [resume] can continue after the system killed the process.
 * - A watcher notices a revoked permission or a switched-off GPS ([problem]) and restarts the location updates
 *   when the cause goes away. While there are no fixes the follower itself shows `NO_SIGNAL`.
 * - Rerouting goes through [routes] with the stops that are still ahead; a null [routes] disables it.
 *
 * Methods may be called from any thread.
 */
class NavigationController(
    private val scope: CoroutineScope,
    private val location: LocationSource,
    private val store: NavStateStore,
    private val environment: NavEnvironment,
    private val routes: RouteProvider? = null,
    private val config: NavConfig = NavConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val persistEveryMillis: Long = 10_000L,
    private val persistEveryMillisPowerSave: Long = 30_000L,
    private val watchEveryMillis: Long = 2_000L,
) {
    private val _state = MutableStateFlow<NavState?>(null)
    private val _route = MutableStateFlow<RoutePlan?>(null)
    private val _problem = MutableStateFlow<NavProblem?>(null)
    private val _announcements = MutableSharedFlow<Announcement>(extraBufferCapacity = 64)
    private val _events = MutableSharedFlow<NavEvent>(extraBufferCapacity = 16)

    /** The snapshot to draw; null when no navigation is in progress. */
    val state: StateFlow<NavState?> = _state.asStateFlow()

    /** The route being followed (changes after a reroute or [replaceRoute]); null when idle. */
    val route: StateFlow<RoutePlan?> = _route.asStateFlow()

    /** Non-null while the platform cannot provide positions (permission revoked, GPS off). */
    val problem: StateFlow<NavProblem?> = _problem.asStateFlow()

    val announcements: SharedFlow<Announcement> = _announcements.asSharedFlow()
    val events: SharedFlow<NavEvent> = _events.asSharedFlow()

    val isActive: Boolean get() = _state.value != null

    private val lock = Any()
    private var session: NavigationSession? = null
    private var runJob: Job? = null
    private var stopPoints: List<LatLon> = emptyList()
    private var trip = NavTrip()

    /** False while a simulated trip runs: nothing is saved, so a simulation can never be resumed as a real trip. */
    @Volatile private var persist = true

    /** True when [resume] would find a saved navigation (a quick look; the file is validated again by [resume]). */
    fun hasResumable(): Boolean = store.load() != null

    /**
     * Starts following [plan], [startAlongMeters] into it (0 for a new trip). Returns false, leaving everything as
     * it was, when the plan cannot be followed. Replaces any navigation in progress. With [persist] false (route
     * simulation) nothing is written to the store and any saved real trip is left alone.
     */
    fun start(plan: RoutePlan, startAlongMeters: Double = 0.0, trip: NavTrip = NavTrip(), persist: Boolean = true): Boolean {
        if (!plan.isFollowable()) return false
        synchronized(lock) {
            stopLocked(clearStore = false)
            this.trip = trip
            this.persist = persist
            launchLocked(plan, startAlongMeters)
        }
        return true
    }

    /** Continues the navigation saved before the process died, if it is still valid. Returns whether it did. */
    fun resume(): Boolean {
        if (isActive) return true
        val saved = store.load() ?: return false
        return start(saved.plan, saved.progressMeters, saved.trip)
    }

    /** Switches to another route without ending the trip (the user chose an alternative, added a stop). */
    fun replaceRoute(plan: RoutePlan) {
        if (!plan.isFollowable()) return
        synchronized(lock) { session?.replaceRoute(plan) }
    }

    /** Ends the navigation: no more fixes, no saved state. Safe when idle. */
    fun stop() = synchronized(lock) { stopLocked(clearStore = true) }

    private fun stopLocked(clearStore: Boolean) {
        runJob?.cancel()
        runJob = null
        session?.close()
        session = null
        if (clearStore && persist) store.clear()
        _state.value = null
        _route.value = null
        _problem.value = null
        stopPoints = emptyList()
    }

    private fun launchLocked(plan: RoutePlan, startAlong: Double) {
        val rerouter: Rerouter? = routes?.let { provider ->
            { from, bearing ->
                val remaining = stopPoints.takeLast((_state.value?.stopsRemaining ?: 0).coerceAtLeast(0))
                val destination = _route.value?.geometry?.lastOrNull() ?: plan.geometry.last()
                provider.route(from, bearing, remaining, destination, trip)?.withStops(remaining)
            }
        }
        val s = NavigationSession(plan, location, scope, config, rerouter, startAlong, clock)
        session = s
        stopPoints = pointsOfStops(s.route.value)
        _route.value = s.route.value
        _state.value = s.state.value
        // A simulated trip replaces whatever was saved: after it, "resume" must not offer the old real one.
        if (persist) store.save(s.route.value, s.state.value.traveledMeters, trip) else store.clear()
        runJob = scope.launch {
            launch { s.announcements.collect { _announcements.tryEmit(it) } }
            launch { s.events.collect { _events.tryEmit(it) } }
            launch { followState(s) }
            launch { watchEnvironment(s) }
            s.start()
        }
    }

    private suspend fun followState(s: NavigationSession) {
        var lastSaved = clock()
        var savedRevision = 0
        s.state.collect { st ->
            _state.value = st
            val route = s.route.value
            if (_route.value !== route) {
                _route.value = route
                stopPoints = pointsOfStops(route)
            }
            if (!persist) return@collect
            if (st.status == NavStatus.ARRIVED) {
                withContext(io) { store.clear() }
                return@collect
            }
            val every = if (environment.isPowerSaveMode()) persistEveryMillisPowerSave else persistEveryMillis
            val now = clock()
            if (st.routeRevision != savedRevision || now - lastSaved >= every) {
                savedRevision = st.routeRevision
                lastSaved = now
                val progress = st.traveledMeters
                withContext(io) { store.save(route, progress, trip) }
            }
        }
    }

    private suspend fun watchEnvironment(s: NavigationSession) {
        while (true) {
            val now = when {
                !environment.hasLocationPermission() -> NavProblem.LOCATION_PERMISSION
                !environment.isLocationEnabled() -> NavProblem.LOCATION_DISABLED
                else -> null
            }
            val before = _problem.value
            if (now != before) {
                _problem.value = now
                if (now == null) s.restartLocation() // the cause went away: listen again
            }
            delay(watchEveryMillis)
        }
    }

    private fun pointsOfStops(plan: RoutePlan): List<LatLon> =
        plan.guidance.stops.sorted().mapNotNull { plan.geometry.getOrNull(it) }

    /** Everything is cancelled by [stop]; this exists for the owner of the scope to release the controller. */
    fun close() {
        try {
            stop()
        } catch (_: CancellationException) {
        }
    }
}
