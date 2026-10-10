package com.qtekfun.ultimatemaps.route

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import com.qtekfun.ultimatemaps.core.routing.BikeCycleways
import com.qtekfun.ultimatemaps.core.routing.Detours
import com.qtekfun.ultimatemaps.core.routing.RouteOptions
import com.qtekfun.ultimatemaps.core.routing.ElevationProfile
import com.qtekfun.ultimatemaps.core.zbe.ZbeCrossing
import com.qtekfun.ultimatemaps.core.weather.WeatherWarning
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.RouteRequest
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
import com.qtekfun.ultimatemaps.nativecomaps.DetailedRoutingEngine
import com.qtekfun.ultimatemaps.nativecomaps.RouteCode
import com.qtekfun.ultimatemaps.nativecomaps.RouteOutcome
import com.qtekfun.ultimatemaps.places.PlaceInfo
import com.qtekfun.ultimatemaps.search.CoreMaps
import com.qtekfun.ultimatemaps.search.InstalledRegions
import com.qtekfun.ultimatemaps.transit.TransitController
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
enum class RouteError {
    NO_REGIONS, NEED_MORE_MAPS, START_NOT_FOUND, END_NOT_FOUND, STOP_NOT_FOUND, ROUTE_NOT_FOUND, NO_CYCLE_ROUTE, TIMEOUT, INTERNAL,

    /** The last-resort catch fired: something threw while the route was being calculated or prepared for the screen. */
    UNEXPECTED,
}

/** Outcome of [RoutePreviewController.addStop]; everything but [ADDED] leaves the route as it was. */
enum class StopResult { ADDED, DUPLICATE, SAME_AS_DESTINATION, LIMIT, NO_ROUTE }

sealed interface RouteOrigin {
    /** The user's current position (in memory, from the location source). */
    data object Current : RouteOrigin

    /** A point chosen by search ([label]) or by tapping the map (null label). Never logged. */
    data class Picked(val point: LatLon, val label: String?) : RouteOrigin
}

/**
 * The one extra restriction an alternative route was calculated with. The CoMaps core has no alternative-route search,
 * so an alternative is the best route under the user's options plus this one (see `docs/phase2/categories-alternatives.md`).
 */
enum class AlternativeKind {
    AVOID_MOTORWAYS, AVOID_TOLLS, AVOID_UNPAVED, AVOID_FERRIES,

    /** A different road: the route through a point on the left / right of the middle of the main one (see [com.qtekfun.ultimatemaps.core.routing.Detours]). */
    VIA_LEFT, VIA_RIGHT,
}

/** A route calculated with one more restriction than the main one; [options] are the full options it was made with. */
data class RouteAlternative(
    val kind: AlternativeKind,
    val options: RouteOptions,
    val geometry: List<LatLon>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    /** The point a [AlternativeKind.VIA_LEFT]/[AlternativeKind.VIA_RIGHT] route goes through; empty for the others. */
    val via: List<LatLon> = emptyList(),
    /** Raw heights of [geometry] (see `RoutePlan.altitudes`); empty when the maps gave none. */
    val altitudes: List<Double> = emptyList(),
)

enum class AlternativesStatus { NONE, FINDING, DONE }

/** Observable state of the route preview. Written from the main thread only. */
class RouteState {
    var active by mutableStateOf(false)
    var destination by mutableStateOf<PlaceInfo?>(null)

    /** Intermediate stops, in driving order: the route is [origin, stops..., destination]. */
    var stops by mutableStateOf<List<PlaceInfo>>(emptyList())
    var origin by mutableStateOf<RouteOrigin>(RouteOrigin.Current)
    var profile by mutableStateOf(RoutingProfile.CAR)

    /** The fourth travel mode, public transport: the route panel shows the transit section instead of a route. */
    var transitMode by mutableStateOf(false)
    var options by mutableStateOf(RouteOptions())
    var status by mutableStateOf(RouteStatus.IDLE)
    var error by mutableStateOf<RouteError?>(null)
    var distanceMeters by mutableStateOf(0.0)
    var durationSeconds by mutableStateOf(0.0)

    /** Climb and chart of the shown route; null when the maps have no heights for it (the UI then shows nothing). */
    var elevation by mutableStateOf<ElevationProfile?>(null)

    /** Low-emission zones the shown CAR route enters (OpenStreetMap data, may be incomplete); empty for other profiles or without data. */
    var lowEmission by mutableStateOf<List<ZbeCrossing>>(emptyList())

    /** Orange and red weather warnings (AEMET) along the shown route; empty while the feature is off. */
    var weather by mutableStateOf<List<WeatherWarning>>(emptyList())

    /** The next search result or map tap becomes the origin. */
    var pickingOrigin by mutableStateOf(false)

    /** Main route figures, kept while another route is selected so the alternatives can show their difference. */
    var baseDistanceMeters by mutableStateOf(0.0)
    var baseDurationSeconds by mutableStateOf(0.0)

    /** Routes found under one more restriction, on request (see [RoutePreviewController.findAlternatives]). */
    var alternatives by mutableStateOf<List<RouteAlternative>>(emptyList())
    var alternativesStatus by mutableStateOf(AlternativesStatus.NONE)

    /** Index into [alternatives] of the selected route; null means the main route. [distanceMeters] follows it. */
    var selectedAlternative by mutableStateOf<Int?>(null)
}

/** Opens the routing engine on top of the installed maps. Blocking and heavy: always called off the main thread. */
fun interface RouteBackend {
    /** [timeoutSec] is the budget handed to the native router for each route. */
    fun open(maps: CoreMaps, timeoutSec: Int): DetailedRoutingEngine
}

/** Latency record for R12. Only the profile, milliseconds and a result name: no positions, no names. */
fun interface RouteLog {
    fun computed(profile: RoutingProfile, millis: Long, result: String)

    /** Same record with the number of intermediate stops (a count, never their positions). */
    fun computed(profile: RoutingProfile, millis: Long, result: String, stops: Int) = computed(profile, millis, result)
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
    /** Cycle-infrastructure level a new bike preview starts from (the Settings default). */
    private val defaultBikeCycleways: () -> BikeCycleways = { BikeCycleways.OFF },
    /** Draws the routes that are not selected (lighter); an empty list removes them. */
    private val showAlternatives: (List<List<LatLon>>) -> Unit = {},
    /** Public-transport planner for the "Transit" mode; null hides that mode. */
    val transit: TransitController? = null,
    /** The zones a route enters (the optional low-emission-zone layer); only asked for car routes. Empty without data. */
    private val lowEmissionZones: (List<LatLon>) -> List<ZbeCrossing> = { emptyList() },
    /** The orange and red weather warnings along a route (optional AEMET alerts, matched on the phone). Empty while off. */
    private val weatherWarnings: (List<LatLon>) -> List<WeatherWarning> = { emptyList() },
    /** Where an unexpected failure is noted (exception class and stack only, never a message or a position). */
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    val state = RouteState()

    private fun zonesOf(geometry: List<LatLon>): List<ZbeCrossing> =
        if (state.profile == RoutingProfile.CAR) guarded("zones", emptyList()) { lowEmissionZones(geometry) } else emptyList()

    /**
     * Runs one optional extra of a route (heights, zones, warnings, drawing). These only decorate a route that was found,
     * so when one of them throws the route is still shown and the failure is noted; it must never take the app down.
     */
    private fun <T> guarded(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        onFailure(what, e)
        fallback
    }

    private var job: Job? = null
    private var altJob: Job? = null
    private var mainPlan: RoutePlan? = null
    private var mainRequest: RouteRequest? = null

    @Volatile private var engine: DetailedRoutingEngine? = null

    @Volatile private var dirty = true

    /** The installed regions may have changed: reopen the engine on the next calculation. */
    fun invalidate() {
        dirty = true
    }

    /** Starts a preview from the current location to [destination] (keeps the profile and options). */
    fun start(destination: PlaceInfo) {
        // A new preview starts from the Settings default for the cycle level; a running one keeps what the user chose.
        if (!state.active) state.options = state.options.copy(bikeCycleways = defaultBikeCycleways())
        state.active = true
        state.destination = destination
        state.stops = emptyList()
        state.origin = RouteOrigin.Current
        state.pickingOrigin = false
        compute()
    }

    /**
     * Inserts [place] as the last stop, right before the destination, and recalculates. Refused (route unchanged)
     * when there is no active route, the stop is at most [SAME_PLACE_METERS] from the destination or from another
     * stop, or [MAX_STOPS] stops already exist.
     */
    fun addStop(place: PlaceInfo): StopResult {
        val destination = state.destination
        if (!state.active || destination == null) return StopResult.NO_ROUTE
        if (state.stops.size >= MAX_STOPS) return StopResult.LIMIT
        if (samePlace(place, destination)) return StopResult.SAME_AS_DESTINATION
        if (state.stops.any { samePlace(place, it) }) return StopResult.DUPLICATE
        state.stops = state.stops + place
        compute()
        return StopResult.ADDED
    }

    fun removeStop(index: Int) {
        if (!state.active || index !in state.stops.indices) return
        state.stops = state.stops.filterIndexed { i, _ -> i != index }
        compute()
    }

    /** Moves the stop at [index] one place earlier ([delta] = -1) or later (+1); ignored at the ends of the list. */
    fun moveStop(index: Int, delta: Int) {
        val to = index + delta
        val stops = state.stops
        if (!state.active || index !in stops.indices || to !in stops.indices || delta == 0) return
        state.stops = stops.toMutableList().also { java.util.Collections.swap(it, index, to) }
        compute()
    }

    /**
     * Looks for up to [MAX_ALTERNATIVES] routes that differ from the main one by one extra restriction
     * ([alternativeKinds]), one after another in the background, and draws them lighter. Each one is a full route
     * calculation under the shared core lock, so it is on request and not automatic. Failed or identical routes are dropped.
     */
    fun findAlternatives() {
        val base = mainRequest ?: return
        val main = mainPlan ?: return
        if (!state.active || state.status != RouteStatus.DONE || state.alternativesStatus != AlternativesStatus.NONE) return
        val kinds = alternativeKinds(base.profile, base.options)
        state.alternativesStatus = AlternativesStatus.FINDING
        altJob = scope.launch {
            val found = mutableListOf<RouteAlternative>()
            try {
                for (kind in kinds) {
                    val options = base.options.withAvoiding(kind)
                    val request = base.copy(options = options)
                    val work = async(io) { mutex.withLock { runNative(request) } }
                    val native = try {
                        withTimeoutOrNull(timeoutMs) { work.await() }
                    } catch (e: CancellationException) {
                        work.cancel()
                        throw e
                    }
                    if (native == null) {
                        work.cancel()
                        continue
                    }
                    val plan = (native as? Native.Done)?.outcome?.plan ?: continue
                    if (plan.geometry.size < 2) continue
                    if (plan.geometry == main.geometry || found.any { it.geometry == plan.geometry }) continue
                    found += RouteAlternative(kind, options, plan.geometry, plan.distanceMeters, plan.durationSeconds, altitudes = plan.altitudes)
                    state.alternatives = found.toList()
                    redrawAlternatives()
                }
                // Different roads, not only different restrictions: through a point on each side of the middle of the main
                // route. Only without stops of the user (the point would have to be placed among them).
                if (base.via.isEmpty()) {
                    for (side in listOf(Detours.Side.LEFT, Detours.Side.RIGHT)) {
                        if (found.size >= MAX_ALTERNATIVES_TOTAL) break
                        val via = Detours.viaPoint(main.geometry, side) ?: continue
                        val request = base.copy(via = listOf(via))
                        val work = async(io) { mutex.withLock { runNative(request) } }
                        val native = try {
                            withTimeoutOrNull(timeoutMs) { work.await() }
                        } catch (e: CancellationException) {
                            work.cancel()
                            throw e
                        }
                        if (native == null) {
                            work.cancel()
                            continue
                        }
                        val plan = (native as? Native.Done)?.outcome?.plan ?: continue
                        if (!worthOffering(main, plan, found.map { it.geometry })) continue
                        found += RouteAlternative(
                            if (side == Detours.Side.LEFT) AlternativeKind.VIA_LEFT else AlternativeKind.VIA_RIGHT,
                            base.options, plan.geometry, plan.distanceMeters, plan.durationSeconds, listOf(via), plan.altitudes,
                        )
                        state.alternatives = found.toList()
                        redrawAlternatives()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onFailure("alternatives", e) // keep what was found; the main route stays as it is
            }
            state.alternativesStatus = AlternativesStatus.DONE
        }
    }

    /** Selects the alternative at [index], or the main route with null; the map and the figures follow. */
    fun selectAlternative(index: Int?) {
        val main = mainPlan ?: return
        if (!state.active || state.status != RouteStatus.DONE) return
        val alt = index?.let { state.alternatives.getOrNull(it) }
        if (index != null && alt == null) return
        state.selectedAlternative = index
        state.distanceMeters = alt?.distanceMeters ?: main.distanceMeters
        state.durationSeconds = alt?.durationSeconds ?: main.durationSeconds
        state.elevation = guarded("elevation", null) {
            if (alt != null) ElevationProfile.of(alt.geometry, alt.altitudes) else ElevationProfile.of(main.geometry, main.altitudes)
        }
        state.lowEmission = zonesOf(alt?.geometry ?: main.geometry)
        state.weather = guarded("weather", emptyList()) { weatherWarnings(alt?.geometry ?: main.geometry) }
        guarded("draw", Unit) { showRoute(alt?.geometry ?: main.geometry) }
        redrawAlternatives()
    }

    private fun redrawAlternatives() {
        val selected = state.selectedAlternative
        val lines = buildList {
            if (selected != null) mainPlan?.let { add(it.geometry) }
            state.alternatives.forEachIndexed { i, a -> if (i != selected) add(a.geometry) }
        }
        guarded("draw-alternatives", Unit) { showAlternatives(lines) }
    }

    private fun resetAlternatives() {
        altJob?.cancel()
        mainPlan = null
        mainRequest = null
        state.elevation = null
        state.lowEmission = emptyList()
        state.weather = emptyList()
        state.alternatives = emptyList()
        state.alternativesStatus = AlternativesStatus.NONE
        state.selectedAlternative = null
        guarded("draw-alternatives", Unit) { showAlternatives(emptyList()) }
    }

    fun close() {
        job?.cancel()
        transit?.clear()
        resetAlternatives()
        state.active = false
        state.destination = null
        state.stops = emptyList()
        state.pickingOrigin = false
        state.status = RouteStatus.IDLE
        state.error = null
        clearRoute()
    }

    fun setProfile(profile: RoutingProfile) {
        val leavingTransit = state.transitMode
        if (state.profile == profile && !leavingTransit) return
        state.transitMode = false
        state.profile = profile
        if (leavingTransit) transit?.clear()
        if (state.active) compute()
    }

    /** Switches to (or, with false, away from) the public-transport mode; leaving it restores the last profile's route. */
    fun setTransitMode(on: Boolean) {
        if (transit == null || state.transitMode == on) return
        if (!on) {
            setProfile(state.profile)
            return
        }
        state.transitMode = true
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
        val waiting = state.status == RouteStatus.NEEDS_ORIGIN ||
            (state.transitMode && transit?.state?.phase == com.qtekfun.ultimatemaps.transit.TransitPhase.NEEDS_ORIGIN)
        if (state.active && state.origin == RouteOrigin.Current && waiting) compute()
    }

    /**
     * The request behind the route on screen (origin, stops, destination, profile, options), for the guided
     * calculation that "Start" and "Simulate" make; null while there is no destination or origin yet.
     */
    fun currentRequest(): RouteRequest? {
        val to = state.destination ?: return null
        val from = when (val o = state.origin) {
            RouteOrigin.Current -> userLocation()
            is RouteOrigin.Picked -> o.point
        } ?: return null
        val alt = state.selectedAlternative?.let { state.alternatives.getOrNull(it) }
        val options = alt?.options ?: state.options
        return RouteRequest(from, to.point, via = state.stops.map { it.point } + (alt?.via ?: emptyList()), profile = state.profile, options = options)
    }

    private fun compute() {
        job?.cancel()
        resetAlternatives()
        val to = state.destination ?: return
        val from = when (val o = state.origin) {
            RouteOrigin.Current -> userLocation()
            is RouteOrigin.Picked -> o.point
        }
        guarded("clear", Unit) { clearRoute() }
        state.error = null
        if (state.transitMode && transit != null) {
            // Public transport has its own planner and result; the car/walk/bike route state stays idle.
            state.status = RouteStatus.IDLE
            transit.plan(from, to.point)
            return
        }
        if (from == null) {
            state.status = RouteStatus.NEEDS_ORIGIN
            return
        }
        val profile = state.profile
        val stops = state.stops.size
        val request = RouteRequest(from, to.point, via = state.stops.map { it.point }, profile = profile, options = state.options)
        state.status = RouteStatus.COMPUTING
        job = scope.launch {
            val t0 = clock()
            try {
                val work = async(io) { mutex.withLock { runNative(request) } }
                val native = try {
                    withTimeoutOrNull(timeoutMs) { work.await() }
                } catch (e: CancellationException) {
                    work.cancel()
                    log.computed(profile, clock() - t0, "cancelled", stops)
                    throw e
                }
                if (native == null) {
                    work.cancel() // cannot stop a running native call; its result is dropped
                    finish(profile, stops, clock() - t0, null, RouteError.TIMEOUT)
                    return@launch
                }
                when (native) {
                    is Native.Done -> {
                        val plan = native.outcome.plan
                        if (plan != null && plan.geometry.size >= 2) {
                            finish(profile, stops, clock() - t0, plan, null, request)
                        } else {
                            finish(profile, stops, clock() - t0, null, errorFor(native.outcome))
                        }
                    }
                    Native.NoRegions -> finish(profile, stops, clock() - t0, null, RouteError.NO_REGIONS)
                    Native.Failed -> finish(profile, stops, clock() - t0, null, RouteError.INTERNAL)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Last resort: a route that fails in any way is a message on screen, never a dead app.
                failUnexpected(profile, stops, clock() - t0, e)
            }
        }
    }

    /** The last-resort state: note the failure (class and stack only), forget any half-built route and say so. */
    private fun failUnexpected(profile: RoutingProfile, stops: Int, millis: Long, e: Throwable) {
        onFailure("route", e)
        try {
            log.computed(profile, millis, "unexpected", stops)
        } catch (_: Throwable) {
        }
        resetAlternatives()
        try {
            clearRoute()
        } catch (_: Throwable) {
        }
        state.error = RouteError.UNEXPECTED
        state.status = RouteStatus.ERROR
    }

    private fun finish(
        profile: RoutingProfile, stops: Int, millis: Long, plan: RoutePlan?, error: RouteError?, request: RouteRequest? = null,
    ) {
        log.computed(profile, millis, error?.name?.lowercase() ?: "ok", stops)
        if (plan != null) {
            state.distanceMeters = plan.distanceMeters
            state.durationSeconds = plan.durationSeconds
            state.baseDistanceMeters = plan.distanceMeters
            state.baseDurationSeconds = plan.durationSeconds
            mainPlan = plan
            mainRequest = request
            state.elevation = guarded("elevation", null) { ElevationProfile.of(plan.geometry, plan.altitudes) }
            state.lowEmission = if (profile == RoutingProfile.CAR) guarded("zones", emptyList()) { lowEmissionZones(plan.geometry) } else emptyList()
            state.weather = guarded("weather", emptyList()) { weatherWarnings(plan.geometry) }
            state.status = RouteStatus.DONE
            guarded("draw", Unit) { showRoute(plan.geometry) }
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
        } catch (e: Throwable) {
            // Also Errors (OutOfMemoryError, a missing native symbol...): this runs on a worker, where they would be fatal.
            onFailure("native", e)
            return Native.Failed
        }
    }

    private fun nativeTimeoutSec() = ((timeoutMs + 999) / 1000).toInt().coerceAtLeast(1)

    companion object {
        /** Generous because the spike measured ~18 s for a long route; the R12 target is 2 s. */
        const val TIMEOUT_MS = 30_000L

        /** Extra routes offered besides the main one: up to this many restriction-based ones... */
        const val MAX_ALTERNATIVES = 2

        /** ...and different-road ones fill up to this many in all. */
        const val MAX_ALTERNATIVES_TOTAL = 3

        /** A detour is worth showing when it is really another road and not much worse than the main route. */
        const val DETOUR_MAX_SHARED = 0.75
        const val DETOUR_MAX_TIME_FACTOR = 1.5
        const val DETOUR_MAX_DISTANCE_FACTOR = 1.7

        /** [plan] differs from [main] and from the [others] already offered, and costs at most 50 % more time and 70 % more distance. */
        fun worthOffering(main: RoutePlan, plan: RoutePlan, others: List<List<LatLon>>): Boolean =
            plan.geometry.size >= 2 &&
                plan.durationSeconds <= main.durationSeconds * DETOUR_MAX_TIME_FACTOR &&
                plan.distanceMeters <= main.distanceMeters * DETOUR_MAX_DISTANCE_FACTOR &&
                Detours.sharedFraction(main.geometry, plan.geometry) < DETOUR_MAX_SHARED &&
                others.none { Detours.sharedFraction(it, plan.geometry) >= 0.9 }

        /** The restrictions to try, in order: the ones that apply to [profile] and the user has not turned on yet. */
        fun alternativeKinds(profile: RoutingProfile, options: RouteOptions): List<AlternativeKind> {
            val candidates = if (profile == RoutingProfile.CAR) {
                listOf(AlternativeKind.AVOID_MOTORWAYS to options.avoidMotorways, AlternativeKind.AVOID_TOLLS to options.avoidTolls)
            } else {
                listOf(AlternativeKind.AVOID_UNPAVED to options.avoidUnpaved, AlternativeKind.AVOID_FERRIES to options.avoidFerries)
            }
            return candidates.filter { !it.second }.map { it.first }.take(MAX_ALTERNATIVES)
        }

        private fun RouteOptions.withAvoiding(kind: AlternativeKind) = when (kind) {
            AlternativeKind.AVOID_MOTORWAYS -> copy(avoidMotorways = true)
            AlternativeKind.AVOID_TOLLS -> copy(avoidTolls = true)
            AlternativeKind.AVOID_UNPAVED -> copy(avoidUnpaved = true)
            AlternativeKind.AVOID_FERRIES -> copy(avoidFerries = true)
            AlternativeKind.VIA_LEFT, AlternativeKind.VIA_RIGHT -> this
        }

        /** Intermediate stops allowed (the native router does one leg per stop, so the time grows with them). */
        const val MAX_STOPS = 5

        /** Two points this close count as the same place (a station tapped twice, or the destination itself). */
        const val SAME_PLACE_METERS = 30.0

        fun samePlace(a: PlaceInfo, b: PlaceInfo) = a.point.distanceTo(b.point) <= SAME_PLACE_METERS

        fun errorFor(outcome: RouteOutcome): RouteError = when (outcome.code) {
            RouteCode.NEED_MORE_MAPS -> RouteError.NEED_MORE_MAPS
            RouteCode.START_NOT_FOUND -> RouteError.START_NOT_FOUND
            RouteCode.END_NOT_FOUND -> RouteError.END_NOT_FOUND
            RouteCode.INTERMEDIATE_NOT_FOUND -> RouteError.STOP_NOT_FOUND
            RouteCode.ROUTE_NOT_FOUND, RouteCode.NO_ERROR, RouteCode.HAS_WARNINGS ->
                RouteError.ROUTE_NOT_FOUND
            RouteCode.NO_CYCLE_ROUTE -> RouteError.NO_CYCLE_ROUTE
            RouteCode.CANCELLED -> RouteError.TIMEOUT // the native router gave up on its own budget
            else -> RouteError.INTERNAL
        }
    }
}
