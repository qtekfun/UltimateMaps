package com.qtekfun.mapas.route

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.routing.RouteOptions
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.RouteRequest
import com.qtekfun.mapas.core.routing.RoutingProfile
import com.qtekfun.mapas.nativecomaps.DetailedRoutingEngine
import com.qtekfun.mapas.nativecomaps.RouteCode
import com.qtekfun.mapas.nativecomaps.RouteOutcome
import com.qtekfun.mapas.places.PlaceInfo
import com.qtekfun.mapas.search.CoreMaps
import com.qtekfun.mapas.search.InstalledRegions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class RouteStatus { IDLE, NEEDS_ORIGIN, COMPUTING, DONE, ERROR }

/** Why there is no route; mapped to a message by the UI. */
enum class RouteError { NO_REGIONS, NEED_MORE_MAPS, START_NOT_FOUND, END_NOT_FOUND, ROUTE_NOT_FOUND, TIMEOUT, INTERNAL }

sealed interface RouteOrigin {
    /** The user's current position (in memory, from the location source). */
    data object Current : RouteOrigin

    /** A point chosen by search ([label]) or by tapping the map (null label). Never logged. */
    data class Picked(val point: LatLon, val label: String?) : RouteOrigin
}

/** Observable state of the route preview. Written from the main thread only. */
class RouteState {
    var active by mutableStateOf(false)
    var destination by mutableStateOf<PlaceInfo?>(null)
    var origin by mutableStateOf<RouteOrigin>(RouteOrigin.Current)
    var profile by mutableStateOf(RoutingProfile.CAR)
    var options by mutableStateOf(RouteOptions())
    var status by mutableStateOf(RouteStatus.IDLE)
    var error by mutableStateOf<RouteError?>(null)
    var distanceMeters by mutableStateOf(0.0)
    var durationSeconds by mutableStateOf(0.0)

    /** The next search result or map tap becomes the origin. */
    var pickingOrigin by mutableStateOf(false)
}

/** Opens the routing engine on top of the installed maps. Blocking and heavy: always called off the main thread. */
fun interface RouteBackend {
    /** [timeoutSec] is the budget handed to the native router for each route. */
    fun open(maps: CoreMaps, timeoutSec: Int): DetailedRoutingEngine
}

/** Latency record for R12. Only the profile, milliseconds and a result name: no positions, no names. */
fun interface RouteLog {
    fun computed(profile: RoutingProfile, millis: Long, result: String)
}

/**
 * Route preview (no turn-by-turn). Every calculation runs on [io], serialised with the other native calls
 * through [mutex], and is cancelled when something newer replaces it (profile, option or origin change).
 * A native call cannot be interrupted, so [timeoutMs] only stops *waiting*: the result is discarded and the
 * native router is given the same budget (rounded up to seconds) so it also gives up.
 */
class RoutePreviewController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val regions: InstalledRegions,
    private val backend: RouteBackend,
    private val userLocation: () -> LatLon?,
    private val showRoute: (List<LatLon>) -> Unit,
    private val clearRoute: () -> Unit,
    private val clock: () -> Long,
    private val log: RouteLog,
    private val mutex: Mutex = Mutex(),
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    val state = RouteState()

    private var job: Job? = null

    @Volatile private var engine: DetailedRoutingEngine? = null

    @Volatile private var dirty = true

    /** The installed regions may have changed: reopen the engine on the next calculation. */
    fun invalidate() {
        dirty = true
    }

    /** Starts a preview from the current location to [destination] (keeps the profile and options). */
    fun start(destination: PlaceInfo) {
        state.active = true
        state.destination = destination
        state.origin = RouteOrigin.Current
        state.pickingOrigin = false
        compute()
    }

    fun close() {
        job?.cancel()
        state.active = false
        state.destination = null
        state.pickingOrigin = false
        state.status = RouteStatus.IDLE
        state.error = null
        clearRoute()
    }

    fun setProfile(profile: RoutingProfile) {
        if (state.profile == profile) return
        state.profile = profile
        if (state.active) compute()
    }

    fun setOptions(options: RouteOptions) {
        if (state.options == options) return
        state.options = options
        if (state.active) compute()
    }

    fun beginPickOrigin() {
        if (state.active) state.pickingOrigin = true
    }

    fun cancelPickOrigin() {
        state.pickingOrigin = false
    }

    /** Uses [point] as origin; ignored unless the user asked to pick one (a stray map tap must not reroute). */
    fun pickOrigin(point: LatLon, label: String?) {
        if (!state.active || !state.pickingOrigin) return
        state.origin = RouteOrigin.Picked(point, label)
        state.pickingOrigin = false
        compute()
    }

    fun useCurrentLocation() {
        if (!state.active) return
        state.origin = RouteOrigin.Current
        state.pickingOrigin = false
        compute()
    }

    /** A location fix arrived: retries when the route was waiting for the current position. */
    fun onUserLocation() {
        if (state.active && state.origin == RouteOrigin.Current && state.status == RouteStatus.NEEDS_ORIGIN) compute()
    }

    private fun compute() {
        job?.cancel()
        val to = state.destination ?: return
        val from = when (val o = state.origin) {
            RouteOrigin.Current -> userLocation()
            is RouteOrigin.Picked -> o.point
        }
        clearRoute()
        state.error = null
        if (from == null) {
            state.status = RouteStatus.NEEDS_ORIGIN
            return
        }
        val profile = state.profile
        val request = RouteRequest(from, to.point, profile = profile, options = state.options)
        state.status = RouteStatus.COMPUTING
        job = scope.launch {
            val t0 = clock()
            val work = async(io) { mutex.withLock { runNative(request) } }
            val native = try {
                withTimeoutOrNull(timeoutMs) { work.await() }
            } catch (e: CancellationException) {
                work.cancel()
                log.computed(profile, clock() - t0, "cancelled")
                throw e
            }
            if (native == null) {
                work.cancel() // cannot stop a running native call; its result is dropped
                finish(profile, clock() - t0, null, RouteError.TIMEOUT)
                return@launch
            }
            when (native) {
                is Native.Done -> {
                    val plan = native.outcome.plan
                    if (plan != null && plan.geometry.size >= 2) {
                        finish(profile, clock() - t0, plan, null)
                    } else {
                        finish(profile, clock() - t0, null, errorFor(native.outcome))
                    }
                }
                Native.NoRegions -> finish(profile, clock() - t0, null, RouteError.NO_REGIONS)
                Native.Failed -> finish(profile, clock() - t0, null, RouteError.INTERNAL)
            }
        }
    }

    private fun finish(profile: RoutingProfile, millis: Long, plan: RoutePlan?, error: RouteError?) {
        log.computed(profile, millis, error?.name?.lowercase() ?: "ok")
        if (plan != null) {
            state.distanceMeters = plan.distanceMeters
            state.durationSeconds = plan.durationSeconds
            state.status = RouteStatus.DONE
            showRoute(plan.geometry)
        } else {
            state.error = error
            state.status = RouteStatus.ERROR
        }
    }

    private sealed interface Native {
        data class Done(val outcome: RouteOutcome) : Native
        data object NoRegions : Native
        data object Failed : Native
    }

    /** Runs under [mutex] on [io]. Never throws (a failure in a child would take the scope down). */
    private fun runNative(request: RouteRequest): Native {
        try {
            var current = engine
            if (current == null || dirty) {
                val maps = regions.coreMaps() ?: return Native.NoRegions
                current = backend.open(maps, nativeTimeoutSec())
                engine = current
                dirty = false
            }
            return Native.Done(current.routeDetailed(request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Native.Failed
        }
    }

    private fun nativeTimeoutSec() = ((timeoutMs + 999) / 1000).toInt().coerceAtLeast(1)

    companion object {
        /** Generous because the spike measured ~18 s for a long route; the R12 target is 2 s. */
        const val TIMEOUT_MS = 30_000L

        fun errorFor(outcome: RouteOutcome): RouteError = when (outcome.code) {
            RouteCode.NEED_MORE_MAPS -> RouteError.NEED_MORE_MAPS
            RouteCode.START_NOT_FOUND -> RouteError.START_NOT_FOUND
            RouteCode.END_NOT_FOUND -> RouteError.END_NOT_FOUND
            RouteCode.ROUTE_NOT_FOUND, RouteCode.INTERMEDIATE_NOT_FOUND, RouteCode.NO_ERROR, RouteCode.HAS_WARNINGS ->
                RouteError.ROUTE_NOT_FOUND
            RouteCode.CANCELLED -> RouteError.TIMEOUT // the native router gave up on its own budget
            else -> RouteError.INTERNAL
        }
    }
}
