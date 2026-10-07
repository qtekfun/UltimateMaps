package com.qtekfun.mapas.core.nav

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.LocationFix
import com.qtekfun.mapas.core.map.LocationSource
import com.qtekfun.mapas.core.routing.RoutePlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Computes a new route from the user's position; null means no route was found. */
typealias Rerouter = suspend (from: LatLon, bearingDegrees: Float?) -> RoutePlan?

/**
 * Turn-by-turn following of [plan], decoupled from the UI: it reads fixes from [location] and publishes [state]
 * and [announcements]. All tracking runs in one coroutine launched in [scope] (a single consumer, so no locks);
 * fixes arriving on any thread are queued, dropping the oldest if the consumer stalls.
 *
 * [clock] must share the time base of the fixes (`LocationFix.timeMillis`, epoch millis on a device); fixes
 * without a timestamp are stamped with it. [reroute] may be null to disable rerouting.
 */
class NavigationSession(
    plan: RoutePlan,
    private val location: LocationSource,
    private val scope: CoroutineScope,
    private val config: NavConfig = NavConfig(),
    private val reroute: Rerouter? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val _announcements = MutableSharedFlow<Announcement>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var tracker = RouteTracker(plan, config, 0, ::emitAnnouncement)
    private val _state = MutableStateFlow(tracker.snapshot())
    private val _route = MutableStateFlow(plan)

    val state: StateFlow<NavState> = _state.asStateFlow()

    /** Spoken prompts, each emitted once. Collect before [start] to avoid missing the first ones. */
    val announcements: SharedFlow<Announcement> = _announcements.asSharedFlow()

    /** The route being followed; replaced after a successful reroute (see [NavState.routeRevision]). */
    val route: StateFlow<RoutePlan> = _route.asStateFlow()

    private val inbox = Channel<Any>(64, BufferOverflow.DROP_OLDEST)
    private val rerouteResult = Channel<RerouteDone>(Channel.CONFLATED)
    private var loop: Job? = null
    private var rerouteJob: Job? = null
    private var rerouteGeneration = 0
    private var nextRerouteAt = Long.MIN_VALUE
    private var lastFix: LocationFix? = null
    private var revision = 0

    private class RerouteDone(val generation: Int, val plan: RoutePlan?)
    private object Wake

    /** Starts listening to [location]. Idempotent. */
    fun start() {
        if (loop != null) return
        location.start { fix -> inbox.trySend(fix) }
        location.lastKnown()?.let { inbox.trySend(it) }
        loop = scope.launch { run() }
    }

    /** Stops following: no more fixes, and any running reroute is cancelled. The last [state] stays. */
    fun stop() {
        location.stop()
        rerouteJob?.cancel()
        rerouteJob = null
        loop?.cancel()
        loop = null
    }

    override fun close() = stop()

    private suspend fun run() {
        while (true) {
            val message = inbox.tryReceive().getOrNull() ?: withTimeoutOrNull(config.tickMillis) { inbox.receive() }
            when (message) {
                is LocationFix -> handleFix(message)
                null -> if (tracker.onTick(clock())) publish()
                else -> Unit // Wake
            }
            rerouteResult.tryReceive().getOrNull()?.let(::handleRerouteDone)
            manageReroute()
        }
    }

    private fun handleFix(raw: LocationFix) {
        val fix = if (raw.timeMillis == 0L) raw.copy(timeMillis = clock()) else raw
        lastFix = fix
        tracker.onFix(fix)
        publish()
    }

    private fun publish() {
        _state.value = tracker.snapshot()
    }

    private fun emitAnnouncement(a: Announcement) {
        _announcements.tryEmit(a)
    }

    private fun manageReroute() {
        val job = rerouteJob
        when (tracker.status) {
            NavStatus.OFF_ROUTE -> {
                if (reroute == null || job?.isActive == true || clock() < nextRerouteAt) return
                val from = tracker.lastFixPoint() ?: return
                tracker.setRerouting(true)
                publish()
                startReroute(reroute, from, tracker.lastFixBearing)
            }
            NavStatus.REROUTING -> Unit
            else -> if (job?.isActive == true) {
                // The user rejoined the route (or arrived) while a reroute was running: it is no longer needed.
                job.cancel()
                rerouteJob = null
                rerouteGeneration++
            }
        }
    }

    private fun startReroute(reroute: Rerouter, from: LatLon, bearing: Float?) {
        val generation = ++rerouteGeneration
        rerouteJob = scope.launch {
            var found: RoutePlan? = null
            for (attempt in 0 until config.reroute.maxAttempts) {
                if (attempt > 0) delay(config.reroute.retryDelayMillis)
                found = try {
                    reroute(from, bearing)?.takeIf { it.geometry.size >= 2 }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (found != null) break
            }
            rerouteResult.send(RerouteDone(generation, found))
            inbox.trySend(Wake)
        }
    }

    private fun handleRerouteDone(done: RerouteDone) {
        if (done.generation != rerouteGeneration || tracker.status != NavStatus.REROUTING) return
        rerouteJob = null
        val plan = done.plan
        if (plan == null) {
            tracker.setRerouting(false)
            nextRerouteAt = clock() + config.reroute.cooldownMillis
            publish()
            return
        }
        revision++
        tracker = RouteTracker(plan, config, revision, ::emitAnnouncement)
        _route.value = plan
        lastFix?.let(tracker::onFix)
        publish()
    }
}
