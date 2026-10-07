package com.qtekfun.mapas.core.transit.follow

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.geo.distanceTo
import com.qtekfun.mapas.core.map.LocationFix
import com.qtekfun.mapas.core.transit.Itinerary
import com.qtekfun.mapas.core.transit.ItineraryLeg
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What survives the death of the process: enough to continue a trip without starting from the first leg. */
data class FollowerSnapshot(val legIndex: Int, val boarded: Boolean, val progress: Double, val delaySec: Int?)

/**
 * Follows a theoretical [Itinerary] with position fixes and the clock, and says where the traveller is relative to it. Pure
 * and deterministic: no threads, no Android, no real time (the clock is a parameter; tests drive it by hand). It never logs
 * or stores positions beyond the last fix in memory.
 *
 * How it matches (all thresholds are design choices in [FollowerConfig], untested on real rides):
 * - A ride is the stop sequence joined by straight lines (the index has no shapes). A fix is matched to the nearest of those
 *   segments inside a corridor that grows with the fix accuracy, giving progress `p = stopIndex + fraction`.
 * - Progress only moves forward while fixes keep arriving; a jump over two or more stops needs [FollowerConfig.confirmFixes]
 *   agreeing fixes, unless the signal was lost (then any matching fix resynchronises, even backwards from an estimate).
 * - Walking is told from riding by speed (the fix's, else derived from two fixes).
 * - On a metro/rail/tram leg, after [FollowerConfig.signalGapSec] without a usable fix, progress is dead-reckoned from the
 *   timetable shifted by the last known offset and the state says [FollowBasis.ESTIMATED]. The estimate never reaches the
 *   alighting stop on its own: only a real fix completes a ride.
 * - "The plan" is only the schedule: ahead/behind is the clock against the scheduled time at the matched position.
 *
 * Call [onFix] for each location fix and [tick] regularly (about once a second) so the clock alone can change the state.
 */
class ItineraryFollower(
    val itinerary: Itinerary,
    private val config: FollowerConfig = FollowerConfig(),
    private val clockMillis: () -> Long,
    snapshot: FollowerSnapshot? = null,
) {
    private val legs = itinerary.legs

    private class Fix(val point: LatLon, val accuracy: Float, val atMs: Long)
    private class Match(val p: Double, val distance: Double)

    private var legIdx = 0
    private var boarded = false
    private var boardedByEstimate = false
    private var progress = 0.0
    private var progressEstimated = false
    private var delaySec: Int? = null

    private var minWalkDistance = Double.MAX_VALUE
    private var offCount = 0
    private var offPlan = false
    private var offEpisode = 0
    private var ridingCount = 0
    private var pendingP = -1.0
    private var pendingCount = 0
    private var forceRecover = false

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
            }
        }
        skipEmptyWalks()
        state = buildState(clockMillis())
    }

    fun snapshot(): FollowerSnapshot = FollowerSnapshot(legIdx, boarded, progress, delaySec)

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
            val prev = lastFix
            gapBefore = prev?.let { nowMs - it.atMs }
            speed = raw?.speedMps ?: prev?.let { derivedSpeed(it, usable) }
            lastFix = usable
        }
        var guard = legs.size * 2 + 2
        while (legIdx < legs.size && guard-- > 0) {
            val finished = when (val leg = legs[legIdx]) {
                is ItineraryLeg.Walk -> stepWalk(leg, usable)
                is ItineraryLeg.Ride -> stepRide(leg, usable, speed, gapBefore, nowMs)
            }
            if (!finished) break
            legIdx++
            resetLeg()
            skipEmptyWalks()
        }
        state = buildState(nowMs)
        return FollowUpdate(state, prompts(state, nowMs))
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
        offPlan = false
        ridingCount = 0
        pendingP = -1.0
        pendingCount = 0
        lastAtBoardingStop = false
        arrivedAtBoarding = false
        forceRecover = false
    }

    private fun derivedSpeed(prev: Fix, now: Fix): Float? {
        val dt = (now.atMs - prev.atMs) / 1000.0
        if (dt < 1.0 || dt > 30.0) return null
        return (prev.point.distanceTo(now.point) / dt).toFloat()
    }

    private fun radius(accuracy: Float): Double =
        (config.minStopRadiusM + accuracy).coerceAtMost(config.maxStopRadiusM)

    /** True when the walk is complete. */
    private fun stepWalk(leg: ItineraryLeg.Walk, fix: Fix?): Boolean {
        if (fix == null) return false
        val d = fix.point.distanceTo(leg.to)
        if (d <= radius(fix.accuracy)) return true
        trackDistance(d, fix.accuracy)
        return false
    }

    /** Off plan when the distance to a walking target grows well beyond the smallest seen. */
    private fun trackDistance(d: Double, accuracy: Float) {
        if (d < minWalkDistance) minWalkDistance = d
        if (d > minWalkDistance + config.walkOffPlanMeters + accuracy) offCount++ else offCount = 0
        setOffPlan(offCount >= config.offPlanFixes)
    }

    private fun setOffPlan(value: Boolean) {
        if (value && !offPlan) offEpisode++
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
            val m = if (moving && !atStop) bestSegment(ride, fix, 0, min(config.lookaheadSegments, n - 2)) else null
            if (m != null) {
                if (++ridingCount >= config.confirmFixes) {
                    boarded = true
                    progress = m.p
                    updateDelay(ride, nowMs)
                }
            } else {
                ridingCount = 0
            }
            if (!boarded) {
                if (atStop) {
                    offCount = 0
                    setOffPlan(false)
                } else {
                    trackDistance(d, fix.accuracy)
                }
            }
            return false
        }
        // On board.
        if (fix == null) {
            estimateProgress(ride, nowMs)
            return false
        }
        val recovering = forceRecover || progressEstimated || (gapBefore != null && gapBefore > config.signalGapSec * 1000L)
        val lo = if (progressEstimated) 0 else min(floor(progress).toInt(), n - 2)
        val hi = if (recovering) n - 2 else min(n - 2, floor(progress).toInt() + config.lookaheadSegments)
        val m = bestSegment(ride, fix, lo, hi)
        if (m == null) {
            offCount++
            setOffPlan(offCount >= config.offPlanFixes)
            return false
        }
        offCount = 0
        setOffPlan(false)
        var newP = m.p
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
        if (nowMs / 1000 < ride.departAt) return
        boarded = true
        boardedByEstimate = true
        progressEstimated = true
        progress = min(scheduleProgress(ride, nowMs / 1000), ride.stops.size - 1 - 1e-3)
    }

    private fun estimateProgress(ride: ItineraryLeg.Ride, nowMs: Long) {
        val last = lastFix ?: return
        if (ride.line.routeType !in config.estimateRouteTypes) return
        if (nowMs - last.atMs < config.signalGapSec * 1000L) return
        val est = scheduleProgress(ride, nowMs / 1000 - (delaySec ?: 0))
        progress = max(progress, min(est, ride.stops.size - 1 - 1e-3))
        progressEstimated = true
    }

    // ------------------------------------------------------------------------------------------------ geometry

    /** Best stop-to-stop segment in [lo]..[hi] within the corridor of [fix], or null. Ties go to the earlier segment. */
    private fun bestSegment(ride: ItineraryLeg.Ride, fix: Fix, lo: Int, hi: Int): Match? {
        var best: Match? = null
        val limit = config.corridorM + fix.accuracy
        for (i in lo..hi) {
            if (i < 0 || i + 1 >= ride.stops.size) continue
            val (t, d) = project(fix.point, ride.stops[i].point, ride.stops[i + 1].point)
            if (d <= limit && (best == null || d < best.distance - 1e-6)) best = Match(i + t, d)
        }
        return best
    }

    /** Fraction along a->b (0..1) of the point nearest to [p], and the distance to it in metres (local flat approximation). */
    private fun project(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val ky = METERS_PER_DEGREE
        val kx = METERS_PER_DEGREE * cos(Math.toRadians(a.lat))
        val bx = (b.lon - a.lon) * kx
        val by = (b.lat - a.lat) * ky
        val px = (p.lon - a.lon) * kx
        val py = (p.lat - a.lat) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 < 1e-6) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return t to sqrt(dx * dx + dy * dy)
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
                // Without a position there is nothing to say about reaching the next departure.
                val margin = if (lastFix == null) null else next?.let { it.departAt - (nowSec + (sec ?: 0)) }
                val conn = margin?.let { connectionOf(it) }
                FollowState(
                    phase = if (offPlan) FollowPhase.OFF_PLAN else phase, legIndex = legIdx, basis = basis,
                    line = next?.line, headsign = next?.headsign, targetName = leg.toName,
                    walkMeters = meters, walkSeconds = sec,
                    boardAt = next?.departAt, secondsToBoard = next?.let { it.departAt - nowSec },
                    planOffsetSec = plan.offset, plan = plan.status, planMinutes = plan.minutes,
                    connection = conn, connectionMarginSec = margin?.toInt(),
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
        val toBoard = ride.departAt - nowSec
        if (atStop) {
            val late = (nowSec - ride.departAt).toInt()
            val conn = if (late > config.waitingLateGraceSec) ConnectionStatus.MISSED else ConnectionStatus.OK
            val plan = planOf(late.coerceAtLeast(0), allowAhead = false)
            return FollowState(
                phase = if (offPlan) FollowPhase.OFF_PLAN else FollowPhase.WAITING, legIndex = legIdx, basis = basis,
                line = ride.line, headsign = ride.headsign, targetName = ride.boarding.name,
                boardAt = ride.departAt, secondsToBoard = toBoard,
                planOffsetSec = plan.offset, plan = plan.status, planMinutes = plan.minutes,
                connection = conn, connectionMarginSec = toBoard.toInt(), changeHere = previousIsRide,
                canReplan = offPlan || conn == ConnectionStatus.MISSED, etaAt = itinerary.arriveAt + (plan.offset ?: 0),
            )
        }
        val (meters, sec) = if (f == null) null to null else walkRemaining(ride.boarding.point, null)
        val margin = sec?.let { toBoard - it }
        val conn = margin?.let { connectionOf(it) }
        // Late for this departure by the negative margin; early or on time is simply on plan.
        val plan = planOf(margin?.let { (-it).toInt().coerceAtLeast(0) }, allowAhead = false)
        return FollowState(
            phase = if (offPlan) FollowPhase.OFF_PLAN else if (legIdx == 0) FollowPhase.BEFORE_START else FollowPhase.TRANSFER,
            legIndex = legIdx, basis = basis,
            line = ride.line, headsign = ride.headsign, targetName = ride.boarding.name,
            walkMeters = meters, walkSeconds = sec, boardAt = ride.departAt, secondsToBoard = toBoard,
            planOffsetSec = plan.offset, plan = plan.status, planMinutes = plan.minutes,
            connection = conn, connectionMarginSec = margin?.toInt(), changeHere = previousIsRide,
            canReplan = offPlan || conn == ConnectionStatus.MISSED, etaAt = itinerary.arriveAt + (plan.offset ?: 0),
        )
    }

    private fun buildOnBoard(ride: ItineraryLeg.Ride, basis: FollowBasis): FollowState {
        val n = ride.stops.size
        val last = min(floor(progress).toInt(), n - 1)
        val next = min(last + 1, n - 1)
        val remaining = (n - 1) - last
        val offset = delaySec ?: if (progressEstimated) 0 else null
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
            planOffsetSec = plan.offset, plan = plan.status, planMinutes = plan.minutes,
            connection = conn, connectionMarginSec = margin?.toInt(),
            canReplan = offPlan || conn == ConnectionStatus.MISSED, etaAt = itinerary.arriveAt + (plan.offset ?: 0),
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
        /** Metres per degree of latitude on the mean-radius sphere used by the rest of the code (6,371,008.8 m). */
        const val METERS_PER_DEGREE = 6_371_008.8 * Math.PI / 180.0
    }
}
