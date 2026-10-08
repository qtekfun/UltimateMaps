package com.qtekfun.ultimatemaps.core.transit.follow

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.map.LocationSource
import com.qtekfun.ultimatemaps.core.transit.Itinerary
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import com.qtekfun.ultimatemaps.core.transit.rt.RideRealTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** Plans a new itinerary from [from] to [to] leaving at [at]; null when none is found. Blocking work belongs to the implementer. */
fun interface TransitReplanner {
    suspend fun replan(from: LatLon, to: LatLon, at: Instant): Itinerary?
}

/** The whole trip as the UI and the service see it. */
data class TransitTripState(
    val itinerary: Itinerary,
    val follow: FollowState,
    /** Time zone id of the city (the itinerary's times are absolute). */
    val zoneId: String,
    /** A re-plan is running. */
    val replanning: Boolean = false,
    /** The last re-plan found nothing (or failed); cleared by the next one. */
    val replanFailed: Boolean = false,
)

/**
 * Owns the one transit trip in progress, like `NavigationController` does for the car: plain Kotlin on coroutines, no Android
 * types, so every behaviour is tested on the JVM. Fixes come from [location], the clock from [clock] (millis); a ticker lets
 * time alone change the state (a departure that passes, the underground estimate). Progress is saved through [store]
 * (throttled) so [resume] can continue after the system killed the process. Re-planning only happens when [replan] is called
 * (the Re-plan button); it never starts by itself. Positions are used for the follower and the re-plan request only, never
 * stored or logged.
 *
 * Methods may be called from any thread.
 */
class TransitTripController(
    private val scope: CoroutineScope,
    private val location: LocationSource,
    private val store: TransitTripStore,
    private val replanner: TransitReplanner? = null,
    private val config: FollowerConfig = FollowerConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMillis: Long = 1_000L,
    private val persistEveryMillis: Long = 10_000L,
    /** Real time for the rides (null: none). Read from memory on every tick; this class never fetches. */
    private val realTime: RideRealTime = RideRealTime.NONE,
) {
    private val _state = MutableStateFlow<TransitTripState?>(null)
    private val _prompts = MutableSharedFlow<FollowPrompt>(extraBufferCapacity = 16)

    /** The trip to draw; null when no trip is being followed. */
    val state: StateFlow<TransitTripState?> = _state.asStateFlow()

    /** Things to say or sound, once each. */
    val prompts: SharedFlow<FollowPrompt> = _prompts.asSharedFlow()

    val isActive: Boolean get() = _state.value != null

    private val lock = Any()
    private var follower: ItineraryFollower? = null
    private var zoneId: String = "UTC"
    private var ticker: Job? = null
    private var replanJob: Job? = null
    private var lastSavedAt = 0L
    private var lastSavedKey = ""

    /** True when [resume] would find a saved trip (a quick look; the file is validated again by [resume]). */
    fun hasResumable(): Boolean = store.load() != null

    /** Starts following [itinerary]. False, leaving everything as it was, when it has no vehicle leg to follow. */
    fun start(itinerary: Itinerary, zoneId: String): Boolean {
        if (itinerary.isWalkOnly) return false
        synchronized(lock) { begin(itinerary, zoneId, null) }
        return true
    }

    /** Continues the trip saved before the process died, if it is still valid. */
    fun resume(): Boolean {
        if (isActive) return true
        val saved = store.load() ?: return false
        synchronized(lock) { begin(saved.itinerary, saved.zoneId, saved.snapshot) }
        return true
    }

    /** Ends the trip: no more fixes, no saved state. Safe when idle. */
    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        ticker?.cancel()
        ticker = null
        replanJob?.cancel()
        replanJob = null
        location.stop()
        follower = null
        _state.value = null
        store.clear()
        lastSavedKey = ""
    }

    private fun begin(itinerary: Itinerary, zone: String, snapshot: FollowerSnapshot?) {
        ticker?.cancel()
        replanJob?.cancel()
        location.stop()
        zoneId = zone
        val f = ItineraryFollower(itinerary, config, clock, snapshot, realTime)
        follower = f
        _state.value = TransitTripState(itinerary, f.state, zone)
        saveLocked(force = true)
        // A listener that is replaced by a restart must not feed a stale follower: it always reads the current one.
        runCatching { location.start { fix -> onFix(fix) } }
        ticker = scope.launch {
            while (true) {
                delay(tickMillis)
                synchronized(lock) { follower?.let { apply(it.tick()) } }
            }
        }
        // Process the last known fix at once so the first screen is not "waiting for GPS".
        location.lastKnown()?.let { onFix(it) }
    }

    /** Restarts the location updates (the permission came back, GPS was switched on). */
    fun restartLocation() {
        synchronized(lock) {
            if (follower == null) return
            location.stop()
            runCatching { location.start { fix -> onFix(fix) } }
        }
    }

    private fun onFix(fix: LocationFix) {
        synchronized(lock) { follower?.let { apply(it.onFix(fix)) } }
    }

    private fun apply(update: FollowUpdate) {
        val f = follower ?: return
        val current = _state.value ?: return
        _state.value = current.copy(follow = update.state)
        update.prompts.forEach { _prompts.tryEmit(it) }
        // Arrived: nothing left to resume and nothing left to watch; the state stays until stop() so the screen can say so.
        if (update.state.phase == FollowPhase.ARRIVED) {
            store.clear()
            lastSavedKey = ""
            ticker?.cancel()
            ticker = null
            location.stop()
        } else {
            val snap = f.snapshot()
            saveLocked(force = false, key = "${snap.legIndex}|${snap.boarded}|${snap.progress.toInt()}")
        }
    }

    private fun saveLocked(force: Boolean, key: String = "") {
        val f = follower ?: return
        val now = clock()
        if (!force && key == lastSavedKey && now - lastSavedAt < persistEveryMillis) return
        lastSavedAt = now
        lastSavedKey = key
        store.save(f.itinerary, f.snapshot(), zoneId)
    }

    /**
     * Plans again from the current position to the same destination, leaving now, and switches to the new itinerary. Only
     * called by the Re-plan button. Returns false when there is no trip, no position, no planner or one is already running;
     * the outcome (found or not) shows in the state.
     */
    fun replan(): Boolean {
        val planner = replanner ?: return false
        val from: LatLon
        val destination: LatLon
        synchronized(lock) {
            val f = follower ?: return false
            if (replanJob?.isActive == true) return false
            from = location.lastKnown()?.point ?: return false
            destination = destinationOf(f.itinerary)
            _state.value = _state.value?.copy(replanning = true, replanFailed = false)
            val atMillis = clock()
            replanJob = scope.launch {
                val found = try {
                    planner.replan(from, destination, Instant.ofEpochMilli(atMillis))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                synchronized(lock) {
                    if (follower !== f) return@launch // the trip ended or changed meanwhile
                    if (found == null || found.isWalkOnly) {
                        _state.value = _state.value?.copy(replanning = false, replanFailed = true)
                    } else {
                        val nf = ItineraryFollower(found, config, clock, realTime = realTime)
                        follower = nf
                        _state.value = TransitTripState(found, nf.state, zoneId)
                        saveLocked(force = true)
                        location.lastKnown()?.let { onFixLocked(nf, it) }
                    }
                }
            }
        }
        return true
    }

    private fun onFixLocked(f: ItineraryFollower, fix: LocationFix) = apply(f.onFix(fix))

    companion object {
        fun destinationOf(itinerary: Itinerary): LatLon = when (val last = itinerary.legs.last()) {
            is ItineraryLeg.Walk -> last.to
            is ItineraryLeg.Ride -> last.alighting.point
        }
    }
}
