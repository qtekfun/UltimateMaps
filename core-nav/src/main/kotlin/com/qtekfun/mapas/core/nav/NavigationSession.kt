package com.qtekfun.mapas.core.nav

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.geo.distanceTo
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
    /** Where along [plan] to start (resuming after the process was killed). */
    startAlongMeters: Double = 0.0,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val _announcements = MutableSharedFlow<Announcement>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _events = MutableSharedFlow<NavEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var tracker = RouteTracker(plan, config, 0, startAlongMeters, ::emitEvent, ::emitAnnouncement)
    private val _state = MutableStateFlow(tracker.snapshot())
    private val _route = MutableStateFlow(tracker.plan)

    val state: StateFlow<NavState> = _state.asStateFlow()

    /** Spoken prompts, each emitted once. Collect before [start] to avoid missing the first ones. */
    val announcements: SharedFlow<Announcement> = _announcements.asSharedFlow()

    /** Intermediate stops reached or skipped, each emitted once. */
    val events: SharedFlow<NavEvent> = _events.asSharedFlow()

    /** Number of unexpected exceptions swallowed by the loop (a bug indicator; navigation goes on). */
    @Volatile
    var internalErrors: Int = 0
        private set

    /** The route being followed; replaced after a successful reroute (see [NavState.routeRevision]). */
    val route: StateFlow<RoutePlan> = _route.asStateFlow()

    private val inbox = Channel<Any>(64, BufferOverflow.DROP_OLDEST)
    private val rerouteResult = Channel<RerouteDone>(Channel.CONFLATED)
    private val replacement = Channel<RoutePlan>(Channel.CONFLATED)
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

    /**
     * Switches to another route in the middle of the trip (the user picked an alternative, added a stop). The
     * follower restarts on it with the last fix; announcements of the old route are not repeated on the new one
     * because the new tracker has its own, and [NavState.routeRevision] increases. Unusable plans are ignored.
     */
    fun replaceRoute(plan: RoutePlan) {
        if (!plan.isFollowable()) return
        replacement.trySend(plan)
        inbox.trySend(Wake)
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
            // Nothing a single message does may end the loop: losing guidance on the road is the worst outcome.
            try {
                when (message) {
                    is LocationFix -> handleFix(message)
                    null -> if (tracker.onTick(clock())) publish()
                    else -> Unit // Wake
                }
                replacement.tryReceive().getOrNull()?.let(::handleReplacement)
                rerouteResult.tryReceive().getOrNull()?.let(::handleRerouteDone)
                manageReroute()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                internalErrors++
            }
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

    private fun emitEvent(e: NavEvent) {
        _events.tryEmit(e)
    }

    private fun adoptRoute(plan: RoutePlan) {
        rerouteJob?.cancel()
        rerouteJob = null
        rerouteGeneration++
        revision++
        tracker = RouteTracker(plan, config, revision, 0.0, ::emitEvent, ::emitAnnouncement)
        _route.value = tracker.plan
        lastFix?.let(tracker::onFix)
        publish()
    }

    private fun handleReplacement(plan: RoutePlan) = adoptRoute(plan)

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
                    reroute(from, bearing)?.takeIf { isSane(it, from) }
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
        adoptRoute(plan)
    }

    /**
     * A reroute that does not start where the user is, or does not end at the same destination, is an engine
     * glitch: following it would make the tracker declare "off route" again at once and loop.
     */
    private fun isSane(candidate: RoutePlan, from: LatLon): Boolean {
        if (candidate.geometry.size < 2 || !candidate.isFollowable()) return false
        val first = candidate.geometry.firstOrNull { it.lat.isFinite() && it.lon.isFinite() } ?: return false
        val last = candidate.geometry.lastOrNull { it.lat.isFinite() && it.lon.isFinite() } ?: return false
        val destination = _route.value.geometry.last()
        return first.distanceTo(from) <= config.reroute.maxStartDistanceMeters &&
            last.distanceTo(destination) <= config.reroute.maxEndDistanceMeters
    }
}
