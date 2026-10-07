package com.qtekfun.ultimatemaps.core.cameras

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.nav.NavState
import com.qtekfun.ultimatemaps.core.nav.NavStatus
import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
import com.qtekfun.ultimatemaps.core.nav.RouteMatch
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * What the temporary incident banner shows. [remainingMillis] counts down from [IncidentBannerMachine.SHOW_MILLIS] to
 * zero; the screen draws its seconds and the sweep of the clock. [distanceMeters] is rounded to 10 m and falls as the
 * driver approaches.
 */
data class IncidentBannerState(
    val id: String,
    val kind: IncidentKind,
    val distanceMeters: Int,
    val remainingMillis: Long,
) {
    /** Whole seconds left, 5 down to 1 (the banner is gone when it would reach 0). */
    val remainingSeconds: Int get() = ceil(remainingMillis / 1000.0).toInt()

    /** Share of the time still left, 1.0 .. 0.0. */
    val fraction: Float get() = (remainingMillis.toFloat() / IncidentBannerMachine.SHOW_MILLIS).coerceIn(0f, 1f)
}

/**
 * Decides which traffic incident lying ON the planned route ahead is shown as a banner for [SHOW_MILLIS], one at a
 * time. Pure logic: no threads of its own, no Android; the time comes from [clock] and is advanced by [tick], so tests
 * need no real time.
 *
 * Rules (numbers reuse the design values of [AlertWarner]; none is new or measured):
 *  - only kinds the user enabled ([CameraSettings.incidentKinds]); with them all off nothing is shown or kept;
 *  - an incident counts when one of its ends is within [AlertWarner.ROUTE_LATERAL_METERS] of the route, ahead of the
 *    vehicle along it and within the warner's look-ahead; when the feed names a travel direction it must agree with
 *    the route direction there;
 *  - an incident closer than [AlertWarner.MIN_ANNOUNCE_METERS], or one the vehicle is already inside, is marked as
 *    seen without a banner (too late to be useful);
 *  - the same incident is shown once per trip ([reset] starts a trip); a recalculated route ([onRoute]) drops the one
 *    on screen and the queue but remembers what was shown, so only a different incident can appear afterwards;
 *  - at most [MAX_QUEUED] waiting, the nearest first; a nearer newcomer replaces the farthest waiting one, which is
 *    not marked as seen and may come back when the driver gets closer.
 */
class IncidentBannerMachine(
    private val incidents: () -> IncidentRepository,
    private val settings: () -> CameraSettings,
    private val clock: () -> Long,
) {
    private class Item(val incident: TrafficIncident, val alongMeters: Double)

    private val shownFlow = MutableStateFlow<IncidentBannerState?>(null)
    val state: StateFlow<IncidentBannerState?> = shownFlow.asStateFlow()

    private val seen = HashSet<String>()
    private val queue = ArrayList<Item>()
    private var current: Item? = null
    private var deadline = 0L
    private var geometry: RouteGeometry? = null
    private var curAlong = 0.0
    private val match = RouteMatch()

    /** A new trip: forget what was shown. */
    @Synchronized
    fun reset() {
        seen.clear()
        clearScreen()
        geometry = null
    }

    /** The plan changed (first route or a reroute): distances along the old one mean nothing. */
    @Synchronized
    fun onRoute(route: RouteGeometry?) {
        geometry = route
        clearScreen()
    }

    @Synchronized
    fun onProgress(alongMeters: Double, lat: Double, lon: Double, speedMps: Double) {
        curAlong = alongMeters
        val kinds = settings().incidentKinds()
        val g = geometry
        if (kinds.isEmpty() || g == null) {
            clearScreen()
            return
        }
        val lookahead = (if (speedMps >= 0) speedMps * AlertWarner.LOOKAHEAD_SECONDS else 0.0)
            .coerceIn(AlertWarner.MIN_LOOKAHEAD_METERS, AlertWarner.MAX_LOOKAHEAD_METERS)
        val radius = lookahead + SCAN_MARGIN_METERS
        val dLat = radius / TargetGrid.METERS_PER_DEGREE
        val dLon = radius / (TargetGrid.METERS_PER_DEGREE * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val bounds = LatLonBounds(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
        for (i in incidents().incidentsIn(bounds, kinds)) {
            if (i.id in seen || current?.incident?.id == i.id || queue.any { it.incident.id == i.id }) continue
            consider(g, i, lookahead)
        }
        // Waiting ones the vehicle has passed are too late: forget them as seen.
        val it = queue.iterator()
        while (it.hasNext()) {
            val q = it.next()
            if (q.alongMeters - curAlong < AlertWarner.MIN_ANNOUNCE_METERS) {
                seen += q.incident.id
                it.remove()
            }
        }
        queue.sortBy { it.alongMeters }
        if (current == null) promote()
        publish()
    }

    /** Advances the countdown to the clock's time; call about four times a second while [state] is not null. */
    @Synchronized
    fun tick() {
        if (current != null && clock() >= deadline) {
            current = null
            promote()
        }
        publish()
    }

    /** The user tapped the banner: it goes now and the next waiting one takes its place. */
    @Synchronized
    fun dismiss() {
        if (current == null) return
        current = null
        promote()
        publish()
    }

    private fun clearScreen() {
        current = null
        queue.clear()
        shownFlow.value = null
    }

    private fun promote() {
        if (queue.isEmpty()) return
        val next = queue.removeAt(0)
        seen += next.incident.id
        current = next
        deadline = clock() + SHOW_MILLIS
    }

    private fun publish() {
        val c = current
        shownFlow.value = if (c == null) {
            null
        } else {
            val meters = (c.alongMeters - curAlong).coerceAtLeast(0.0)
            IncidentBannerState(c.incident.id, c.incident.kind, (meters / 10.0).roundToInt() * 10, (deadline - clock()).coerceIn(0L, SHOW_MILLIS))
        }
    }

    private fun consider(g: RouteGeometry, i: TrafficIncident, lookahead: Double) {
        val ends = listOfNotNull(alongOf(g, i, i.location), i.end?.let { alongOf(g, i, it) })
        if (ends.isEmpty()) return
        val ahead = ends.filter { it - curAlong >= 0 }
        if (ahead.isEmpty()) return // behind us
        if (ahead.size < ends.size) { // between the two ends: already inside
            seen += i.id
            return
        }
        val along = ahead.min()
        val distance = along - curAlong
        if (distance < AlertWarner.MIN_ANNOUNCE_METERS) {
            seen += i.id
            return
        }
        if (distance > lookahead) return
        addQueued(Item(i, along))
    }

    /** The along-route position of [p] when it lies on the route (and in the right direction), else null. */
    private fun alongOf(g: RouteGeometry, i: TrafficIncident, p: LatLon): Double? {
        g.search(p.lat, p.lon, curAlong, curAlong + AlertWarner.MAX_LOOKAHEAD_METERS + 2 * SCAN_MARGIN_METERS, Double.NaN, 0.0, match)
        if (match.distance > AlertWarner.ROUTE_LATERAL_METERS) return null
        val dir = i.directionDeg
        if (dir != null && !match.segmentBearing.isNaN() &&
            TargetGrid.angleDiff(match.segmentBearing, dir.toDouble()) > DIRECTION_TOLERANCE_DEG
        ) {
            return null
        }
        return match.along
    }

    private fun addQueued(item: Item) {
        if (queue.size < MAX_QUEUED) {
            queue += item
            return
        }
        val far = queue.maxBy { it.alongMeters }
        if (item.alongMeters < far.alongMeters) {
            queue.remove(far)
            queue += item
        }
    }

    companion object {
        /** The owner asked for five seconds. */
        const val SHOW_MILLIS = 5_000L
        const val MAX_QUEUED = 3
        private const val SCAN_MARGIN_METERS = 100.0
        private const val DIRECTION_TOLERANCE_DEG = 60.0
    }
}

/**
 * Feeds an [IncidentBannerMachine] from the navigation's published state and route, and drives its countdown with
 * [ticker] (a flow of ticks collected only while a banner is on screen; the app passes a delay loop, tests a
 * hand-driven flow). Owns no location source. Nothing here stores or logs a position.
 */
class IncidentBannerFeed(
    private val scope: CoroutineScope,
    private val state: Flow<NavState?>,
    private val route: Flow<RoutePlan?>,
    val machine: IncidentBannerMachine,
    private val ticker: Flow<Unit>,
) : AutoCloseable {
    private var jobs: List<Job> = emptyList()

    fun start() {
        if (jobs.isNotEmpty()) return
        machine.reset()
        jobs = listOf(
            scope.launch {
                route.collect { plan -> machine.onRoute(plan?.geometry?.takeIf { it.size >= 2 }?.let { RouteGeometry(it) }) }
            },
            scope.launch {
                state.collect { s ->
                    if (s != null && s.status == NavStatus.ON_ROUTE && !s.estimated) {
                        machine.onProgress(s.traveledMeters, s.position.lat, s.position.lon, s.speedMps)
                    }
                }
            },
            scope.launch { collectTicks() },
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectTicks() {
        machine.state.map { it != null }.distinctUntilChanged()
            .flatMapLatest { showing -> if (showing) ticker else emptyFlow() }
            .collect { machine.tick() }
    }

    override fun close() {
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        machine.reset()
    }
}
