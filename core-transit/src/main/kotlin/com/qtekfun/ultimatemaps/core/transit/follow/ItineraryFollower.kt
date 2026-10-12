package com.qtekfun.ultimatemaps.core.transit.follow

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.transit.Itinerary
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import com.qtekfun.ultimatemaps.core.transit.rt.LegRealTime
import com.qtekfun.ultimatemaps.core.transit.rt.RideRealTime
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The ride the traveller is on right now: [progress] is a stop index plus the fraction of the way to the next stop; [delaySec] is behind the schedule. */
data class BoardedRide(val legIndex: Int, val ride: ItineraryLeg.Ride, val progress: Double, val delaySec: Int)

/** What survives the death of the process: enough to continue a trip without starting from the first leg. */
data class FollowerSnapshot(
    val legIndex: Int,
    val boarded: Boolean,
    val progress: Double,
    val delaySec: Int?,
    /** The traveller said "I am on board" for this ride and nothing has contradicted it since. */
    val confirmed: Boolean = false,
)

/**
 * Follows a theoretical [Itinerary] with position fixes and the clock, and says where the traveller is relative to it. Pure
 * and deterministic: no threads, no Android, no real time (the clock is a parameter; tests drive it by hand). It never logs
 * or stores positions beyond the last fix in memory.
 *
 * How it matches (all thresholds are design choices in [FollowerConfig], untested on real rides):
 * - A ride is its line geometry when the index has it (rail patterns of feeds that publish shapes), else the stop sequence
 *   joined by straight lines. A fix is matched to the nearest piece of that track inside a corridor that grows with the fix
 *   accuracy (narrow on real geometry, wide on chords, which cut across curves), giving progress `p = stopIndex + fraction`
 *   computed from the distance along the track.
 * - Direction is decided on that distance: a fix behind the best progress is only evidence of the wrong way when several
 *   consecutive fixes, at least [FollowerConfig.reverseMinMeters] back, say so while moving. One noisy fix, a stop at a
 *   station or an estimated position never does. Low-accuracy and standing fixes never start an off-plan state either.
 * - Progress only moves forward while fixes keep arriving; a jump over two or more stops needs [FollowerConfig.confirmFixes]
 *   agreeing fixes, unless the signal was lost (then any matching fix resynchronises, even backwards from an estimate).
 * - Walking is told from riding by speed (the fix's, else derived from two fixes).
 * - On a metro/rail/tram leg, after [FollowerConfig.signalGapSec] without a usable fix, progress is dead-reckoned from the
 *   timetable shifted by the last known offset and the state says [FollowBasis.ESTIMATED]. The estimate never reaches the
 *   alighting stop on its own: only a real fix completes a ride.
 * - "The plan" is the schedule: ahead/behind is the clock against the scheduled time at the matched position. For a ride
 *   that [realTime] knows (Renfe Cercanias, opt-in), the train's real delay replaces that comparison, a departure is the
 *   scheduled one plus the delay, and a cancelled train is a missed connection (so Re-plan is offered).
 *
 * Call [onFix] for each location fix and [tick] regularly (about once a second) so the clock alone can change the state.
 */
class ItineraryFollower(
    val itinerary: Itinerary,
    private val config: FollowerConfig = FollowerConfig(),
    private val clockMillis: () -> Long,
    snapshot: FollowerSnapshot? = null,
    /** Real time for a ride, when the user switched it on and the train is in the feed; [RideRealTime.NONE] otherwise. */
    private val realTime: RideRealTime = RideRealTime.NONE,
    /** The system's guess whether the device is in a vehicle (true), on foot or still (false), or unknown (null); weak evidence only. */
    private val inVehicle: () -> Boolean? = { null },
    /** Where the local diagnostics lines go (see [TransitFollowDiagnostics]); tests pass their own. */
    private val notes: (String) -> Unit = TransitFollowDiagnostics::note,
) {
    private val legs = itinerary.legs

    private class Fix(val point: LatLon, val accuracy: Float, val atMs: Long)
    private val tracks = arrayOfNulls<RideTrack>(legs.size)

    // result of the last successful [bestSegment] call (kept in fields so matching allocates nothing)
    private var matchP = 0.0
    private var matchAlong = 0.0
    private var matchDist = 0.0

    private var legIdx = 0
    private var boarded = false
    private var boardedByEstimate = false
    private var progress = 0.0
    private var progressEstimated = false
    private var delaySec: Int? = null

    private var minWalkDistance = Double.MAX_VALUE
    private var offCount = 0
    private var offSinceMs = 0L
    private var offCorridor = false
    private var reversed = false
    private var backCount = 0
    private var backStartAlong = 0.0
    private var offPlan = false
    private var offEpisode = 0
    private var ridingCount = 0
    private var fastCount = 0
    private var pendingP = -1.0
    private var pendingCount = 0
    private var forceRecover = false

    /** Clock time of the traveller's "I am on board" for the ride in progress, or -1. Sticky: see [assumeBoarded]. */
    private var confirmedAtMs = -1L

    // local diagnostics: what was last logged, and the evidence behind the next line
    private var loggedKey = ""
    private var loggedAtMs = 0L
    private var event = ""
    private var nearestM = -1.0
    private var lastAccuracy = -1f
    private var lastSpeed = -1f

    private var lastFix: Fix? = null
    private var lastAtBoardingStop = false
    private var arrivedAtBoarding = false
    private val said = HashSet<String>()

    /** The current state; updated by [onFix] and [tick]. */
    var state: FollowState
        private set

    init {
        require(legs.isNotEmpty()) { "an itinerary needs at least one leg" }
        if (snapshot != null) {
            legIdx = snapshot.legIndex.coerceIn(0, legs.size)
            val ride = legs.getOrNull(legIdx) as? ItineraryLeg.Ride
            if (ride != null && snapshot.boarded) {
                boarded = true
                progress = snapshot.progress.coerceIn(0.0, ride.stops.size - 1.0)
                delaySec = snapshot.delaySec
                forceRecover = true
                if (snapshot.confirmed) confirmedAtMs = clockMillis()
            }
        }
        skipEmptyWalks()
        state = buildState(clockMillis())
    }

    /** The ride the traveller is aboard, or null when not (waiting, walking, arrived). */
    fun boardedRide(): BoardedRide? {
        val ride = legs.getOrNull(legIdx) as? ItineraryLeg.Ride ?: return null
        if (!boarded) return null
        return BoardedRide(legIdx, ride, progress, delaySec ?: 0)
    }

    /**
     * The traveller says "I am on the train": the next (or current) ride counts as boarded, at the place of the last fix on
     * its stops (a wide corridor) or, with no usable fix, where the timetable says it is. The next fixes resynchronise it.
     * False when no ride is left. Wins over what the fixes said, and the answer is sticky: it also clears an off-plan state
     * of a ride that was already boarded, and for [FollowerConfig.confirmedGraceSec] no fix can make the ride off plan again;
     * afterwards only a clear contradiction does (the wrong way over a stretch, or [FollowerConfig.confirmedOffSec] outside even
     * the wide corridor). While fixes do not fit the line the progress follows the timetable with the clock.
     */
    fun assumeBoarded(): Boolean {
        val idx = if (legs.getOrNull(legIdx) is ItineraryLeg.Ride) legIdx else nextRideIndex(legIdx)
        if (idx < 0) return false
        val ride = legs[idx] as ItineraryLeg.Ride
        val nowMs = clockMillis()
        val wasBoarded = idx == legIdx && boarded
        val wasOffPlan = offPlan
        if (!wasBoarded) {
            legIdx = idx
            resetLeg()
            boarded = true
        } else {
            boardedByEstimate = false
            offCount = 0
            offCorridor = false
            reversed = false
            backCount = 0
            pendingCount = 0
            offPlan = false
        }
        val matched = lastFix?.let { bestSegment(ride, it, 0, ride.stops.size - 2, config.wideCorridorFactor * 2, 0.0) } == true
        val byClock = min(scheduleProgress(ride, nowMs / 1000 - (delaySec ?: 0)), ride.stops.size - 1 - 1e-3)
        progress = when {
            matched -> matchP
            wasBoarded -> max(progress, byClock)
            else -> byClock
        }
        progressEstimated = false
        forceRecover = true
        confirmedAtMs = nowMs
        // A boarding the traveller states has no better delay than "now against the timetable"; keep a known one while aboard.
        if (!wasBoarded || delaySec == null) updateDelay(ride, nowMs)
        event = "user-confirmed-aboard" + if (wasOffPlan) " (was off plan)" else ""
        state = buildState(clockMillis())
        logLine(nowMs, force = true)
        return true
    }

    /** The traveller says "I am not on the train": the ride goes back to being waited for, and the position decides what is off plan. */
    fun assumeNotBoarded() {
        if (legs.getOrNull(legIdx) is ItineraryLeg.Ride && boarded) {
            resetLeg()
            event = "user-not-aboard"
            state = buildState(clockMillis())
            logLine(clockMillis(), force = true)
        }
    }

    fun snapshot(): FollowerSnapshot = FollowerSnapshot(legIdx, boarded, progress, delaySec, confirmedAtMs >= 0)

    fun onFix(fix: LocationFix): FollowUpdate = step(fix)

    /** The clock moved with no fix: waiting times, estimates and missed departures can change the state. */
    fun tick(): FollowUpdate = step(null)

    // ------------------------------------------------------------------------------------------------ stepping

    private fun step(raw: LocationFix?): FollowUpdate {
        val nowMs = clockMillis()
        val usable = raw?.takeIf { (it.accuracyMeters ?: config.defaultAccuracyM) <= config.unusableAccuracyM }?.let {
            Fix(it.point, it.accuracyMeters ?: config.defaultAccuracyM, nowMs)
        }
        var speed: Float? = null
        var gapBefore: Long? = null
        if (usable != null) {
            nearestM = -1.0
            val prev = lastFix
            gapBefore = prev?.let { nowMs - it.atMs }
            speed = raw?.speedMps ?: prev?.let { derivedSpeed(it, usable) }
            lastFix = usable
            lastAccuracy = usable.accuracy
            lastSpeed = speed ?: -1f
        }
        var guard = legs.size * 2 + 2
        while (legIdx < legs.size && guard-- > 0) {
            val finished = when (val leg = legs[legIdx]) {
                is ItineraryLeg.Walk -> stepWalk(leg, usable, speed)
                is ItineraryLeg.Ride -> stepRide(leg, usable, speed, gapBefore, nowMs)
            }
            if (!finished) break
            legIdx++
            resetLeg()
            skipEmptyWalks()
        }
        state = buildState(nowMs)
        logLine(nowMs, force = false)
        return FollowUpdate(state, prompts(state, nowMs))
    }

    /**
     * One line of the local diagnostics when what the follower believes changed (or a heartbeat while aboard or off plan). No
     * coordinate and no name: state, reason, distance to the track, accuracy, speed, progress, delay.
     */
    private fun logLine(nowMs: Long, force: Boolean) {
        val s = state
        val key = "${s.phase}|${s.legIndex}|$boarded|$progressEstimated|${confirmedAtMs >= 0}|${s.basis}"
        val beat = (boarded || offPlan) && nowMs - loggedAtMs >= config.diagnosticsHeartbeatSec * 1000L
        if (!force && key == loggedKey && !beat) return
        val changed = key != loggedKey
        loggedKey = key
        loggedAtMs = nowMs
        val why = event.ifEmpty { if (changed) "state-change" else "heartbeat" }
        event = ""
        val dist = if (nearestM < 0) "-" else "${nearestM.roundToInt()}m"
        val clock = java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), java.time.ZoneId.systemDefault()).withNano(0)
        val prog = "%.2f".format(java.util.Locale.ROOT, progress)
        val speedText = if (lastSpeed < 0) "-" else "%.1f".format(java.util.Locale.ROOT, lastSpeed)
        val accText = if (lastAccuracy < 0) "-" else "${lastAccuracy.roundToInt()}m"
        notes(
            "$clock ${s.phase} leg=${s.legIndex} boarded=$boarded confirmed=${confirmedAtMs >= 0} estimated=$progressEstimated " +
                "progress=$prog delay=${delaySec ?: "-"}s basis=${s.basis} toTrack=$dist acc=$accText speed=$speedText reason=$why",
        )
    }

    private fun skipEmptyWalks() {
        while (legIdx < legs.size) {
            val w = legs[legIdx] as? ItineraryLeg.Walk ?: return
            if (w.meters > 0) return
            legIdx++
            resetLeg()
        }
    }

    private fun resetLeg() {
        boarded = false
        boardedByEstimate = false
        progress = 0.0
        progressEstimated = false
        minWalkDistance = Double.MAX_VALUE
        offCount = 0
        offCorridor = false
        reversed = false
        backCount = 0
        offPlan = false
        ridingCount = 0
        fastCount = 0
        pendingP = -1.0
        pendingCount = 0
        lastAtBoardingStop = false
        arrivedAtBoarding = false
        forceRecover = false
        confirmedAtMs = -1L
    }

    private fun derivedSpeed(prev: Fix, now: Fix): Float? {
        val dt = (now.atMs - prev.atMs) / 1000.0
        if (dt < 1.0 || dt > 30.0) return null
        return (prev.point.distanceTo(now.point) / dt).toFloat()
    }

    private fun radius(accuracy: Float): Double =
        (config.minStopRadiusM + accuracy).coerceAtMost(config.maxStopRadiusM)

    /** True when the walk is complete. */
    private fun stepWalk(leg: ItineraryLeg.Walk, fix: Fix?, speed: Float?): Boolean {
        if (fix == null) return false
        val d = fix.point.distanceTo(leg.to)
        if (d <= radius(fix.accuracy)) return true
        trackDistance(d, fix, speed)
        return false
    }

    /**
     * Off plan when the distance to a walking target grows well beyond the smallest seen, for several fixes over several
     * seconds. Standing near the stop (a large station, a platform away from the stop's point) is never off plan.
     */
    private fun trackDistance(d: Double, fix: Fix, speed: Float?) {
        if (d < minWalkDistance) minWalkDistance = d
        val away = d > minWalkDistance + config.walkOffPlanMeters + fix.accuracy && d > config.stationProximityM
        offEvidence(away, fix, speed)
    }

    /**
     * One fix's worth of evidence that the traveller left the plan ([away]) or is back on it. It only becomes the off-plan
     * state after [FollowerConfig.offPlanFixes] fixes spread over [FollowerConfig.offPlanSec]; a fix too vague to tell
     * ([FollowerConfig.offPlanMaxAccuracyM]) or one with the traveller standing still counts as neither.
     */
    private fun offEvidence(away: Boolean, fix: Fix, speed: Float?) {
        if (!away) {
            offCount = 0
            offCorridor = false
            refreshOffPlan()
            return
        }
        if (fix.accuracy > config.offPlanMaxAccuracyM) return
        if (speed != null && speed < config.stationarySpeedMps && !offCorridor) return
        if (offCount == 0) offSinceMs = fix.atMs
        offCount++
        if (offCount >= config.offPlanFixes && fix.atMs - offSinceMs >= config.offPlanSec * 1000L) offCorridor = true
        refreshOffPlan()
    }

    private fun refreshOffPlan() {
        val value = offCorridor || reversed
        if (value && !offPlan) {
            offEpisode++
            event = if (reversed) "wrong-way" else "outside-corridor"
        } else if (!value && offPlan) {
            event = "back-on-track"
        }
        offPlan = value
    }

    /** True when the ride is complete (alighting stop reached with a real fix). */
    private fun stepRide(ride: ItineraryLeg.Ride, fix: Fix?, speed: Float?, gapBefore: Long?, nowMs: Long): Boolean {
        val n = ride.stops.size
        if (!boarded) {
            if (fix == null) {
                estimateBoarding(ride, nowMs)
                return false
            }
            val d = fix.point.distanceTo(ride.boarding.point)
            val atStop = d <= radius(fix.accuracy)
            lastAtBoardingStop = atStop
            if (atStop) arrivedAtBoarding = true
            val moving = (speed ?: 0f) >= config.ridingSpeedMps
            val m = moving && !atStop && bestSegment(ride, fix, 0, min(config.lookaheadSegments, n - 2), 1.0, 0.0)
            if (m) {
                if (++ridingCount >= config.confirmFixes) {
                    boarded = true
                    progress = matchP
                    updateDelay(ride, nowMs)
                }
            } else {
                ridingCount = (ridingCount - 1).coerceAtLeast(0) // one slow fix (a traffic light) does not undo the evidence
            }
            // Rail lines curve between stops and the stop-to-stop lines have no shape, so a fast train can stay outside the
            // corridor for a whole hop: a speed no walker or cyclist reaches, sustained along the line, also means "aboard".
            if (!boarded && !atStop && ride.line.routeType in config.estimateRouteTypes &&
                ((speed ?: 0f) >= config.trainSpeedMps || (inVehicle() == true && (speed ?: 0f) >= config.ridingSpeedMps))
            ) {
                val wide = bestSegment(ride, fix, 0, min(config.lookaheadSegments, n - 2), config.wideCorridorFactor, 0.0)
                if (wide && ++fastCount >= config.trainConfirmFixes) {
                    boarded = true
                    progress = matchP
                    updateDelay(ride, nowMs)
                }
            } else if (!boarded) {
                fastCount = 0
            }
            if (!boarded) {
                if (atStop) {
                    offEvidence(false, fix, speed)
                } else if (m || fastCount > 0) {
                    // moving along the line towards the next stop: the vehicle is leaving, not a walker straying
                    offEvidence(false, fix, speed)
                } else {
                    trackDistance(d, fix, speed)
                }
            }
            return false
        }
        // On board.
        if (fix == null) {
            estimateProgress(ride, nowMs)
            return false
        }
        val confirmed = confirmedAtMs >= 0
        val inGrace = confirmed && nowMs - confirmedAtMs < config.confirmedGraceSec * 1000L
        val recovering = forceRecover || progressEstimated || (gapBefore != null && gapBefore > config.signalGapSec * 1000L)
        // One hop of look-behind: a fix a little behind the best progress is how the wrong way shows up.
        val lo = if (progressEstimated) 0 else (min(floor(progress).toInt(), n - 2) - 1).coerceAtLeast(0)
        val hi = if (recovering) n - 2 else min(n - 2, floor(progress).toInt() + config.lookaheadSegments)
        val track = trackOf(ride)
        // The traveller said so: a chord can be 600 m from the rails, so the corridor is wide.
        val matched = bestSegment(ride, fix, lo, hi, if (confirmed) config.confirmedCorridorFactor else 1.0, progress)
        if (!matched) {
            // Not on the line. A station area (platform away from the line), a vague fix or a standing traveller is no proof.
            val nearStop = nearAnyStop(ride, fix, lo, hi + 1)
            backCount = 0
            if (confirmed) {
                confirmedUnmatched(ride, fix, speed, nowMs, nearStop, inGrace)
                // The ride ends at the alighting stop even when the line was never matched on the way.
                return progress >= n - 2 && fix.point.distanceTo(ride.alighting.point) <= radius(fix.accuracy)
            }
            offEvidence(!nearStop, fix, speed)
            return false
        }
        var newP = matchP
        val newAlong = matchAlong
        // Direction: only a sustained, consistent move backwards along the line says the wrong way.
        if (!recovering && !inGrace) {
            val back = track.alongOf(progress) - newAlong
            val tolerance = max(config.reverseToleranceM, 2.0 * fix.accuracy)
            val movingEnough = speed == null || speed >= config.stationarySpeedMps
            if (back > tolerance && movingEnough && fix.accuracy <= config.offPlanMaxAccuracyM) {
                if (backCount == 0) backStartAlong = track.alongOf(progress)
                backCount++
                if (backCount >= config.reverseFixes && backStartAlong - newAlong >= config.reverseMinMeters) reversed = true
            } else if (back <= tolerance) {
                backCount = 0
                reversed = false
            }
        } else {
            backCount = 0
            reversed = false
        }
        offCount = 0
        offCorridor = false
        refreshOffPlan()
        if (!progressEstimated && newP < progress) newP = progress
        if (!recovering && floor(newP) - floor(progress) >= 2) {
            pendingCount = if (pendingCount > 0 && abs(newP - pendingP) < 1.0) pendingCount + 1 else 1
            pendingP = newP
            if (pendingCount < config.confirmFixes) newP = progress
        } else {
            pendingCount = 0
        }
        // A fix back at the boarding stop right after an estimated boarding, not moving: the vehicle never left.
        if (config.undoEstimatedBoarding && boardedByEstimate && newP < 0.2 &&
            fix.point.distanceTo(ride.boarding.point) <= radius(fix.accuracy) && (speed ?: 0f) < config.ridingSpeedMps
        ) {
            boarded = false
            boardedByEstimate = false
            progress = 0.0
            progressEstimated = false
            lastAtBoardingStop = true
            return false
        }
        progress = newP
        progressEstimated = false
        forceRecover = false
        updateDelay(ride, nowMs)
        // Only a progress that was accepted (not held back for confirmation) can finish the ride.
        return progress >= n - 1 - 1e-9 || (progress >= n - 2 && fix.point.distanceTo(ride.alighting.point) <= radius(fix.accuracy))
    }

    private fun updateDelay(ride: ItineraryLeg.Ride, nowMs: Long) {
        delaySec = (nowMs / 1000.0 - scheduledAt(ride, progress)).roundToInt()
    }

    private fun estimateBoarding(ride: ItineraryLeg.Ride, nowMs: Long) {
        val last = lastFix ?: return
        if (!lastAtBoardingStop || ride.line.routeType !in config.estimateRouteTypes) return
        if (nowMs - last.atMs < config.signalGapSec * 1000L) return
        val rt = rtOf(ride)
        // A cancelled train is not boarded, and a delayed one is boarded at its real departure, not the scheduled one.
        if (rt?.cancelled == true || nowMs / 1000 < expectedDeparture(ride, rt)) return
        boarded = true
        boardedByEstimate = true
        progressEstimated = true
        progress = min(scheduleProgress(ride, nowMs / 1000 - (rt?.delaySec ?: 0)), ride.stops.size - 1 - 1e-3)
    }

    /**
     * A fix of a ride the traveller confirmed, outside even the wide corridor: it is not evidence by itself. The progress goes
     * on with the timetable (dead reckoning, marked estimated). It only makes the ride off plan after the grace, when fixes
     * (accurate, not standing) have stayed outside for [FollowerConfig.confirmedOffSec]; a stop area or a matching fix resets it.
     */
    private fun confirmedUnmatched(ride: ItineraryLeg.Ride, fix: Fix, speed: Float?, nowMs: Long, nearStop: Boolean, inGrace: Boolean) {
        val est = scheduleProgress(ride, nowMs / 1000 - (rtOf(ride)?.delaySec ?: delaySec ?: 0))
        progress = max(progress, min(est, ride.stops.size - 1 - 1e-3))
        progressEstimated = true
        if (inGrace || nearStop) {
            offCount = 0
            offCorridor = false
        } else if (fix.accuracy <= config.offPlanMaxAccuracyM && (speed == null || speed >= config.stationarySpeedMps)) {
            if (offCount == 0) offSinceMs = fix.atMs
            offCount++
            if (offCount >= config.offPlanFixes && fix.atMs - offSinceMs >= config.confirmedOffSec * 1000L) offCorridor = true
        }
        refreshOffPlan()
    }

    private fun estimateProgress(ride: ItineraryLeg.Ride, nowMs: Long) {
        val last = lastFix ?: return
        if (ride.line.routeType !in config.estimateRouteTypes && confirmedAtMs < 0) return
        if (nowMs - last.atMs < config.signalGapSec * 1000L) return
        val est = scheduleProgress(ride, nowMs / 1000 - (rtOf(ride)?.delaySec ?: delaySec ?: 0))
        progress = max(progress, min(est, ride.stops.size - 1 - 1e-3))
        progressEstimated = true
    }

    // ------------------------------------------------------------------------------------------------ geometry

    private fun trackOf(ride: ItineraryLeg.Ride): RideTrack {
        val i = legs.indexOf(ride)
        val cached = if (i >= 0) tracks[i] else null
        if (cached != null) return cached
        return RideTrack(ride).also { if (i >= 0) tracks[i] = it }
    }

    /**
     * Finds the piece of the ride's track nearest to [fix] among hops [lo]..[hi] within the corridor (narrow on real geometry,
     * wide on chords, times [corridorFactor], plus the fix accuracy). On success the progress, the metres along the track and
     * the distance are left in [matchP], [matchAlong], [matchDist]. A piece clearly nearer wins; between nearly equal ones
     * (the line passing the same place twice, parallel tracks) the one closest to [refProgress] wins, then the earlier one.
     */
    private fun bestSegment(ride: ItineraryLeg.Ride, fix: Fix, lo: Int, hi: Int, corridorFactor: Double, refProgress: Double): Boolean {
        val t = trackOf(ride)
        if (t.stopCount < 2 || t.edgeCount == 0) return false
        val h0 = lo.coerceAtLeast(0)
        val h1 = hi.coerceAtMost(t.stopCount - 2)
        if (h0 > h1) return false
        val px = t.xOf(fix.point.lon)
        val py = t.yOf(fix.point.lat)
        val refAlong = t.alongOf(refProgress)
        var found = false
        var bestD = 0.0
        var bestAlong = 0.0
        var bestHop = 0
        val vFrom = t.stopVertex[h0]
        val vTo = min(t.stopVertex[h1 + 1], t.edgeCount) // edges vFrom until vTo
        for (e in vFrom until vTo) {
            val hop = t.edgeHopOf(e)
            if (hop < h0 || hop > h1) continue
            val ax = t.vx[e]
            val ay = t.vy[e]
            val bx = t.vx[e + 1] - ax
            val by = t.vy[e + 1] - ay
            val qx = px - ax
            val qy = py - ay
            val len2 = bx * bx + by * by
            val u = if (len2 < 1e-6) 0.0 else ((qx * bx + qy * by) / len2).coerceIn(0.0, 1.0)
            val dx = qx - u * bx
            val dy = qy - u * by
            val d = sqrt(dx * dx + dy * dy)
            if (nearestM < 0 || d < nearestM) nearestM = d
            val base = if (t.hopOnShape[hop]) config.railCorridorM else config.corridorM
            if (d > base * corridorFactor + fix.accuracy) continue
            val along = t.cum[e] + u * sqrt(len2)
            val better = !found || d < bestD - NEAR_TIE_M ||
                (d <= bestD + NEAR_TIE_M && abs(along - refAlong) < abs(bestAlong - refAlong) - 1e-6)
            if (better) {
                found = true
                bestD = d
                bestAlong = along
                bestHop = hop
            }
        }
        if (!found) return false
        matchDist = bestD
        matchAlong = bestAlong
        // progress from the metres along the track, inside the hop that owns the edge
        val hopLen = t.stopAlong[bestHop + 1] - t.stopAlong[bestHop]
        val frac = if (hopLen < 1e-6) 0.0 else ((bestAlong - t.stopAlong[bestHop]) / hopLen).coerceIn(0.0, 1.0)
        matchP = bestHop + frac
        return true
    }

    /** True when [fix] is within a stop's arrival radius for any stop of hops [lo]..[hi] (station areas are not off the line). */
    private fun nearAnyStop(ride: ItineraryLeg.Ride, fix: Fix, lo: Int, hi: Int): Boolean {
        val r = radius(fix.accuracy)
        for (i in lo.coerceAtLeast(0)..hi.coerceAtMost(ride.stops.size - 1)) {
            if (fix.point.distanceTo(ride.stops[i].point) <= r) return true
        }
        return false
    }

    /** Scheduled epoch second at progress [p] (stop index plus fraction of the way to the next stop). */
    private fun scheduledAt(ride: ItineraryLeg.Ride, p: Double): Double {
        val n = ride.stops.size
        if (p >= n - 1) return ride.stops[n - 1].arriveAt.toDouble()
        val i = floor(p).toInt().coerceIn(0, n - 2)
        val t = (p - i).coerceIn(0.0, 1.0)
        val dep = ride.stops[i].departAt.toDouble()
        val arr = ride.stops[i + 1].arriveAt.toDouble()
        return dep + t * (arr - dep)
    }

    /** The inverse of [scheduledAt]: where the timetable says the vehicle is at [tSec]. */
    private fun scheduleProgress(ride: ItineraryLeg.Ride, tSec: Long): Double {
        val n = ride.stops.size
        if (tSec <= ride.stops[0].departAt) return 0.0
        for (i in 0 until n - 1) {
            val dep = ride.stops[i].departAt
            val arr = ride.stops[i + 1].arriveAt
            if (tSec < dep) return i.toDouble()
            if (tSec <= arr) return i + (tSec - dep).toDouble() / max(1L, arr - dep)
        }
        return n - 1.0
    }

    // ------------------------------------------------------------------------------------------------ state

    private class Plan(val status: PlanStatus, val minutes: Int, val offset: Int?)

    private fun planOf(offset: Int?, allowAhead: Boolean): Plan {
        if (offset == null) return Plan(PlanStatus.ON_PLAN, 0, null)
        val tol = config.onPlanToleranceSec
        return when {
            offset > tol -> Plan(PlanStatus.BEHIND, max(1, (offset / 60.0).roundToInt()), offset)
            offset < -tol && allowAhead -> Plan(PlanStatus.AHEAD, max(1, (-offset / 60.0).roundToInt()), offset)
            else -> Plan(PlanStatus.ON_PLAN, 0, offset)
        }
    }

    private fun rtOf(ride: ItineraryLeg.Ride): LegRealTime? = try { realTime.forRide(ride)?.takeIf { it.hasInfo } } catch (_: RuntimeException) { null }

    /** Scheduled departure moved by the train's real delay, when the feed knows it. */
    private fun expectedDeparture(ride: ItineraryLeg.Ride, rt: LegRealTime?): Long = ride.departAt + (rt?.delaySec?.toLong() ?: 0L)

    private fun nextRideIndex(from: Int): Int = (from until legs.size).firstOrNull { legs[it] is ItineraryLeg.Ride } ?: -1

    private fun connectionOf(marginSec: Long): ConnectionStatus = when {
        marginSec < -config.missedGraceSec -> ConnectionStatus.MISSED
        marginSec < config.riskMarginSec -> ConnectionStatus.AT_RISK
        else -> ConnectionStatus.OK
    }

    private fun basisOf(nowMs: Long): FollowBasis {
        if (progressEstimated) return FollowBasis.ESTIMATED
        val last = lastFix ?: return FollowBasis.NO_SIGNAL
        return if (nowMs - last.atMs > config.signalGapSec * 1000L) FollowBasis.NO_SIGNAL else FollowBasis.GNSS
    }

    /** Metres and seconds left on a walk to [target]; the planned figures until the first fix. */
    private fun walkRemaining(target: LatLon, plannedMeters: Int?): Pair<Int?, Int?> {
        val f = lastFix
        if (f == null) return plannedMeters to plannedMeters?.let { (it / config.walkSpeedMps).roundToInt() }
        val meters = (f.point.distanceTo(target) * config.detourFactor).roundToInt()
        return meters to (meters / config.walkSpeedMps).roundToInt()
    }

    private fun buildState(nowMs: Long): FollowState {
        val nowSec = nowMs / 1000
        val basis = basisOf(nowMs)
        if (legIdx >= legs.size) {
            return FollowState(FollowPhase.ARRIVED, legs.size, basis, etaAt = itinerary.arriveAt + (delaySec ?: 0))
        }
        return when (val leg = legs[legIdx]) {
            is ItineraryLeg.Walk -> {
                val phase = when {
                    legIdx == legs.lastIndex -> FollowPhase.FINAL_WALK
                    legIdx == 0 -> FollowPhase.BEFORE_START
                    else -> FollowPhase.TRANSFER
                }
                val (meters, sec) = walkRemaining(leg.to, leg.meters)
                val next = legs.getOrNull(nextRideIndex(legIdx + 1)) as? ItineraryLeg.Ride
                val projected = if (lastFix != null) nowSec + (sec ?: 0) else null
                val plan = planOf(projected?.let { (it - leg.arriveAt).toInt() }, allowAhead = false)
                val nextRt = next?.let { rtOf(it) }
                val nextDepart = next?.let { expectedDeparture(it, nextRt) }
                // Without a position there is nothing to say about reaching the next departure.
                val margin = if (lastFix == null) null else nextDepart?.let { it - (nowSec + (sec ?: 0)) }
                val conn = if (nextRt?.cancelled == true) ConnectionStatus.MISSED else margin?.let { connectionOf(it) }
                FollowState(
                    phase = if (offPlan) FollowPhase.OFF_PLAN else phase, legIndex = legIdx, basis = basis,
                    line = next?.line, headsign = next?.headsign, targetName = leg.toName,
                    walkMeters = meters, walkSeconds = sec,
                    boardAt = nextDepart, secondsToBoard = nextDepart?.let { it - nowSec },
                    planOffsetSec = plan.offset, plan = plan.status, planMinutes = plan.minutes, realTime = nextRt,
                    connection = conn, connectionMarginSec = margin?.toInt(), connectionLine = next?.line?.shortName,
                    canReplan = offPlan || conn == ConnectionStatus.MISSED, etaAt = itinerary.arriveAt + (plan.offset ?: 0),
                )
            }
            is ItineraryLeg.Ride -> if (!boarded) buildBoarding(leg, nowSec, basis) else buildOnBoard(leg, basis)
        }
    }

    private fun buildBoarding(ride: ItineraryLeg.Ride, nowSec: Long, basis: FollowBasis): FollowState {
        val f = lastFix
        val previousIsRide = legIdx > 0 && legs[legIdx - 1] is ItineraryLeg.Ride
        // Once at the stop, one fix outside the radius (GNSS noise, a step to the kerb) does not turn waiting back into walking.
        val atStop = arrivedAtBoarding || (f != null && f.point.distanceTo(ride.boarding.point) <= radius(f.accuracy))
        val rt = rtOf(ride)
        val departure = expectedDeparture(ride, rt)
        val toBoard = departure - nowSec
        if (atStop) {
            val late = (nowSec - departure).toInt()
            val conn = if (rt?.cancelled == true || late > config.waitingLateGraceSec) ConnectionStatus.MISSED else ConnectionStatus.OK
            // With real time the plan chip says how late the train is; without it, how late the schedule already is.
            val plan = planOf(rt?.delaySec ?: late.coerceAtLeast(0), allowAhead = false)
            return FollowState(
                phase = if (offPlan) FollowPhase.OFF_PLAN else FollowPhase.WAITING, legIndex = legIdx, basis = basis,
                line = ride.line, headsign = ride.headsign, targetName = ride.boarding.name,
                boardAt = departure, secondsToBoard = toBoard,
                planOffsetSec = plan.offset, plan = plan.status, planMinutes = plan.minutes, realTime = rt,
                connection = conn, connectionMarginSec = toBoard.toInt(), connectionLine = ride.line.shortName, changeHere = previousIsRide,
                canReplan = offPlan || conn == ConnectionStatus.MISSED, etaAt = itinerary.arriveAt + (plan.offset ?: 0),
            )
        }
        val (meters, sec) = if (f == null) null to null else walkRemaining(ride.boarding.point, null)
        val margin = sec?.let { toBoard - it }
        val conn = if (rt?.cancelled == true) ConnectionStatus.MISSED else margin?.let { connectionOf(it) }
        // Late for this departure by the negative margin; early or on time is simply on plan.
        val plan = planOf(margin?.let { (-it).toInt().coerceAtLeast(0) }, allowAhead = false)
        return FollowState(
            phase = if (offPlan) FollowPhase.OFF_PLAN else if (legIdx == 0) FollowPhase.BEFORE_START else FollowPhase.TRANSFER,
            legIndex = legIdx, basis = basis,
            line = ride.line, headsign = ride.headsign, targetName = ride.boarding.name,
            walkMeters = meters, walkSeconds = sec, boardAt = departure, secondsToBoard = toBoard,
            planOffsetSec = plan.offset, plan = plan.status, planMinutes = plan.minutes, realTime = rt,
            connection = conn, connectionMarginSec = margin?.toInt(), connectionLine = ride.line.shortName, changeHere = previousIsRide,
            canReplan = offPlan || conn == ConnectionStatus.MISSED, etaAt = itinerary.arriveAt + (plan.offset ?: 0),
        )
    }

    private fun buildOnBoard(ride: ItineraryLeg.Ride, basis: FollowBasis): FollowState {
        val n = ride.stops.size
        val last = min(floor(progress).toInt(), n - 1)
        val next = min(last + 1, n - 1)
        val remaining = (n - 1) - last
        val rt = rtOf(ride)
        // The train's own delay beats the clock-against-schedule comparison, which only sees where we think we are.
        val offset = rt?.delaySec ?: delaySec ?: if (progressEstimated) 0 else null
        val plan = planOf(offset, allowAhead = true)
        // Look ahead at the connection to the next boarding: the planned margin moved by the current offset.
        val nextRide = legs.getOrNull(nextRideIndex(legIdx + 1)) as? ItineraryLeg.Ride
        val transferWalk = if (nextRide != null) legs.subList(legIdx + 1, nextRideIndex(legIdx + 1)).sumOf { it.arriveAt - it.departAt } else 0L
        val margin = nextRide?.let { it.departAt - (ride.arriveAt + (plan.offset ?: 0) + transferWalk) }
        val conn = margin?.let { connectionOf(it) }
        val phase = when {
            offPlan -> FollowPhase.OFF_PLAN
            remaining <= 1 -> FollowPhase.ALIGHT_NEXT
            else -> FollowPhase.ON_BOARD
        }
        return FollowState(
            phase = phase, legIndex = legIdx, basis = basis, line = ride.line, headsign = ride.headsign,
            targetName = nextRide?.boarding?.name,
            lastStopIndex = last, nextStopIndex = next, stopsRemaining = remaining,
            nextStopName = ride.stops[next].name, nextStopAt = ride.stops[next].arriveAt,
            alightName = ride.alighting.name, alightAt = ride.alighting.arriveAt,
            planOffsetSec = plan.offset, plan = plan.status, planMinutes = plan.minutes, realTime = rt,
            connection = conn, connectionMarginSec = margin?.toInt(), connectionLine = nextRide?.line?.shortName,
            canReplan = offPlan || conn == ConnectionStatus.MISSED, etaAt = itinerary.arriveAt + (plan.offset ?: 0),
            aboard = true,
        )
    }

    // ------------------------------------------------------------------------------------------------ prompts

    private fun once(key: String): Boolean = said.add(key)

    /** Index of the ride whose boarding the connection status of [s] is about. */
    private fun connectionRide(s: FollowState): Int {
        val here = legs[s.legIndex]
        return if (here is ItineraryLeg.Ride && s.phase != FollowPhase.ON_BOARD && s.phase != FollowPhase.ALIGHT_NEXT && !boarded) s.legIndex
        else nextRideIndex(s.legIndex + 1)
    }

    private fun prompts(s: FollowState, nowMs: Long): List<FollowPrompt> {
        val out = ArrayList<FollowPrompt>(2)
        val line = s.line?.shortName
        val i = s.legIndex
        when (s.phase) {
            FollowPhase.ARRIVED -> if (once("arrived")) out.add(FollowPrompt(PromptKind.ARRIVED))
            FollowPhase.OFF_PLAN -> if (once("off#$offEpisode")) out.add(FollowPrompt(PromptKind.OFF_PLAN))
            FollowPhase.WAITING -> {
                if (s.changeHere && once("change#$i")) out.add(FollowPrompt(PromptKind.CHANGE_HERE, line, s.headsign, s.targetName))
                val toBoard = s.secondsToBoard
                if (toBoard != null && toBoard <= config.boardNowSec && s.connection != ConnectionStatus.MISSED && once("board#$i")) {
                    out.add(FollowPrompt(PromptKind.BOARD_NOW, line, s.headsign, s.targetName))
                }
            }
            FollowPhase.TRANSFER -> if (once("change#$i")) out.add(FollowPrompt(PromptKind.CHANGE_HERE, line, s.headsign, s.targetName))
            FollowPhase.ALIGHT_NEXT -> {
                if (once("ready#$i")) out.add(FollowPrompt(PromptKind.GET_READY, line, stop = s.alightName, estimated = s.estimated))
                val ride = legs[i] as ItineraryLeg.Ride
                val f = lastFix
                val near = if (s.estimated) nowMs / 1000 >= ride.arriveAt - config.getOffNowEstimatedSec
                else f != null && f.point.distanceTo(ride.alighting.point) <= config.getOffNowMeters
                if (near && once("off-now#$i")) out.add(FollowPrompt(PromptKind.GET_OFF_NOW, line, stop = s.alightName, estimated = s.estimated))
            }
            else -> Unit
        }
        if (s.phase != FollowPhase.ARRIVED && s.connection != null && s.connection != ConnectionStatus.OK) {
            val connIdx = connectionRide(s)
            val target = legs.getOrNull(connIdx) as? ItineraryLeg.Ride
            val minutes = s.connectionMarginSec?.let { (it / 60.0).roundToInt() }
            if (s.connection == ConnectionStatus.AT_RISK && s.phase != FollowPhase.WAITING && once("risk#$connIdx")) {
                out.add(FollowPrompt(PromptKind.CONNECTION_AT_RISK, target?.line?.shortName, target?.headsign, target?.boarding?.name, minutes))
            }
            if (s.connection == ConnectionStatus.MISSED && once("missed#$connIdx")) {
                out.add(FollowPrompt(PromptKind.CONNECTION_MISSED, target?.line?.shortName, target?.headsign, target?.boarding?.name))
            }
        }
        return out
    }

    private companion object {
        /** Two matches this close in distance (metres) are the same quality; the one nearer the current progress wins. */
        const val NEAR_TIE_M = 15.0
    }
}
