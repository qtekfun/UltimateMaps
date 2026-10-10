package com.qtekfun.ultimatemaps.core.transit.follow

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.map.GeofenceTarget
import com.qtekfun.ultimatemaps.core.map.Geofencer
import com.qtekfun.ultimatemaps.core.map.LocationSource
import com.qtekfun.ultimatemaps.core.map.MovementHint
import com.qtekfun.ultimatemaps.core.map.NoGeofencer
import com.qtekfun.ultimatemaps.core.map.NoMovementHint
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
import kotlin.math.floor
import kotlin.math.max

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
    /** The system's movement hint (activity recognition in the `play` flavor); started with a trip and stopped with it. */
    private val movementHint: MovementHint = NoMovementHint,
    /** Geofences on the stop to get off at and the stop to board (the `play` flavor); cleared with the trip. */
    private val geofencer: Geofencer = NoGeofencer,
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
        runCatching { movementHint.stop() }
        runCatching { geofencer.clear() }
        geofenceKey = ""
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
        val f = ItineraryFollower(itinerary, config, clock, snapshot, realTime, inVehicle = movementHint::inVehicle)
        follower = f
        _state.value = TransitTripState(itinerary, f.state, zone)
        saveLocked(force = true)
        geofenceKey = ""
        updateGeofences(f)
        // A listener that is replaced by a restart must not feed a stale follower: it always reads the current one.
        runCatching { location.start { fix -> onFix(fix) } }
        runCatching { movementHint.start() }
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
            runCatching { movementHint.stop() }
            runCatching { geofencer.clear() }
            geofenceKey = ""
        } else {
            val snap = f.snapshot()
            saveLocked(force = false, key = "${snap.legIndex}|${snap.boarded}|${snap.progress.toInt()}")
            updateGeofences(f)
        }
    }

    private var geofenceKey = ""

    /**
     * Watches the stop where the traveller gets off (while on or about to be on a ride) and the stop where they board next. The
     * list only changes when the leg or the boarded state does. An entry is turned into a fix at that stop: the system says the
     * device is inside the circle, which is exactly what the follower needs when ordinary position updates are throttled.
     */
    private fun updateGeofences(f: ItineraryFollower) {
        val s = f.snapshot()
        val key = "${s.legIndex}|${s.boarded}"
        if (key == geofenceKey) return
        geofenceKey = key
        val legs = f.itinerary.legs
        val targets = ArrayList<GeofenceTarget>(2)
        val ride = legs.getOrNull(s.legIndex) as? ItineraryLeg.Ride
        val nextRide = (s.legIndex until legs.size).firstNotNullOfOrNull { legs[it] as? ItineraryLeg.Ride }
        if (ride != null) targets.add(GeofenceTarget("alight:${s.legIndex}", ride.alighting.point, GEOFENCE_RADIUS_M))
        if (!s.boarded && nextRide != null) {
            targets.add(GeofenceTarget("board:${legs.indexOf(nextRide)}", nextRide.boarding.point, GEOFENCE_RADIUS_M))
        }
        if (nextRide != null && nextRide !== ride) {
            targets.add(GeofenceTarget("alight:${legs.indexOf(nextRide)}", nextRide.alighting.point, GEOFENCE_RADIUS_M))
        }
        runCatching { geofencer.set(targets.distinctBy { it.id }) { id -> onGeofence(id) } }
    }

    private fun onGeofence(id: String) {
        synchronized(lock) {
            val f = follower ?: return
            val index = id.substringAfter(':').toIntOrNull() ?: return
            val ride = f.itinerary.legs.getOrNull(index) as? ItineraryLeg.Ride ?: return
            // Boarding: the device is at the stop. Getting off: "within about 150 m of the stop", so the fix goes a little
            // before it on the line (inside the follower's get-off distance, outside its arrival distance): the traveller is
            // told to get off, and the ride ends when a real fix says the stop was reached.
            val point = if (id.startsWith("alight:")) before(ride) else ride.boarding.point
            apply(f.onFix(LocationFix(point, accuracyMeters = GEOFENCE_FIX_ACCURACY_M)))
        }
    }

    /** The point [GEOFENCE_APPROACH_M] before the alighting stop on the way from the previous stop (halfway on a shorter hop). */
    private fun before(ride: ItineraryLeg.Ride): LatLon {
        val end = ride.alighting.point
        val prev = ride.stops.getOrNull(ride.stops.size - 2)?.point ?: return end
        val d = prev.distanceTo(end)
        if (d <= 1.0) return end
        val frac = if (d > 2 * GEOFENCE_APPROACH_M) GEOFENCE_APPROACH_M / d else 0.5
        return LatLon(end.lat + frac * (prev.lat - end.lat), end.lon + frac * (prev.lon - end.lon))
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
     * The traveller says whether they are on the vehicle; it wins over what the fixes inferred and decides how [replan]
     * starts. False when there is no trip (or, for "on board", no ride left).
     */
    fun setOnBoard(onBoard: Boolean): Boolean = synchronized(lock) {
        val f = follower ?: return false
        if (onBoard) {
            if (!f.assumeBoarded()) return false
        } else {
            f.assumeNotBoarded()
        }
        apply(FollowUpdate(f.state, emptyList()))
        true
    }

    /**
     * Plans again to the same destination, leaving now, and switches to the new itinerary. Only called by the Re-plan button.
     * Returns false when there is no trip, no position, no planner or one is already running; the outcome (found or not)
     * shows in the state.
     *
     * Aboard a vehicle the position is not where the traveller can start walking: the new trip starts on the vehicle, getting
     * off at each of the next few stops (and at the planned one) and planning from there at the time the vehicle gets there,
     * and the earliest arrival wins. Staying on the same vehicle through a stop is merged into one ride, so the answer is never
     * "take the next train" to someone already on it.
     */
    fun replan(): Boolean {
        val planner = replanner ?: return false
        val from: LatLon
        val destination: LatLon
        val onboard: BoardedRide?
        synchronized(lock) {
            val f = follower ?: return false
            if (replanJob?.isActive == true) return false
            from = location.lastKnown()?.point ?: return false
            destination = destinationOf(f.itinerary)
            onboard = f.boardedRide()
            _state.value = _state.value?.copy(replanning = true, replanFailed = false)
            val atMillis = clock()
            replanJob = scope.launch {
                val found = try {
                    if (onboard != null) replanAboard(planner, onboard, destination, atMillis) else
                        planner.replan(from, destination, Instant.ofEpochMilli(atMillis))?.let { Replanned(it, null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                synchronized(lock) {
                    if (follower !== f) return@launch // the trip ended or changed meanwhile
                    if (found == null || found.itinerary.isWalkOnly) {
                        _state.value = _state.value?.copy(replanning = false, replanFailed = true)
                    } else {
                        val nf = ItineraryFollower(found.itinerary, config, clock, found.snapshot, realTime = realTime, inVehicle = movementHint::inVehicle)
                        follower = nf
                        _state.value = TransitTripState(found.itinerary, nf.state, zoneId)
                        saveLocked(force = true)
                        geofenceKey = ""
                        updateGeofences(nf)
                        location.lastKnown()?.let { onFixLocked(nf, it) }
                    }
                }
            }
        }
        return true
    }

    private class Replanned(val itinerary: Itinerary, val snapshot: FollowerSnapshot?)

    private suspend fun replanAboard(planner: TransitReplanner, on: BoardedRide, destination: LatLon, atMillis: Long): Replanned? {
        val ride = on.ride
        val start = floor(on.progress).toInt().coerceIn(0, ride.stops.size - 2)
        val first = floor(on.progress).toInt() + 1
        val candidates = ((first until minOf(first + ALIGHT_CANDIDATES, ride.stops.size)) + (ride.stops.size - 1)).distinct()
        val nowSec = atMillis / 1000
        var best: Itinerary? = null
        for (k in candidates) {
            val stop = ride.stops[k]
            val reach = max(stop.arriveAt + max(on.delaySec, 0), nowSec)
            val rest = planner.replan(stop.point, destination, Instant.ofEpochSecond(reach)) ?: continue
            val head = ride.copy(stops = ride.stops.subList(start, k + 1))
            val combined = join(head, rest, ride)
            if (best == null || combined.arriveAt < best.arriveAt) best = combined
        }
        val itinerary = best ?: return null
        return Replanned(itinerary, FollowerSnapshot(0, true, on.progress - start, on.delaySec))
    }

    /** [head] (the vehicle up to a stop) followed by [rest]; when [rest] starts on the same vehicle the two rides are one. */
    private fun join(head: ItineraryLeg.Ride, rest: Itinerary, original: ItineraryLeg.Ride): Itinerary {
        val next = rest.legs.firstOrNull() as? ItineraryLeg.Ride
        val sameVehicle = next != null && ((original.tripId != null && next.tripId == original.tripId) ||
            (next.line == original.line && next.headsign == original.headsign && next.boarding.departAt == head.alighting.departAt))
        val legs = if (sameVehicle && next != null) {
            listOf<ItineraryLeg>(head.copy(stops = head.stops + next.stops.drop(1), shape = null)) + rest.legs.drop(1)
        } else {
            listOf<ItineraryLeg>(head) + rest.legs
        }
        return Itinerary(legs, rest.note)
    }

    private fun onFixLocked(f: ItineraryFollower, fix: LocationFix) = apply(f.onFix(fix))

    companion object {
        /** How many of the next stops are tried as the place to get off when re-planning aboard (the planned one is always tried). */
        const val ALIGHT_CANDIDATES = 5

        /** Radius of the geofences on stops: at least about 100 m for the system to deliver them reliably. */
        const val GEOFENCE_RADIUS_M = 150f

        /** What a geofence entry is worth as a fix: a fix this far before the alighting stop, this accurate (see [onGeofence]). */
        const val GEOFENCE_APPROACH_M = 110.0
        const val GEOFENCE_FIX_ACCURACY_M = 30f

        fun destinationOf(itinerary: Itinerary): LatLon = when (val last = itinerary.legs.last()) {
            is ItineraryLeg.Walk -> last.to
            is ItineraryLeg.Ride -> last.alighting.point
        }
    }
}
