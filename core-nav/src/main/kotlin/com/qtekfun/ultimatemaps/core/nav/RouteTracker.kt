package com.qtekfun.ultimatemaps.core.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.routing.Maneuver
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Synchronous, deterministic core of route following: feed it fixes ([onFix]) and clock ticks ([onTick]),
 * read the result with [snapshot]. No threads and no coroutines; time comes only from the fixes and ticks,
 * so tests are exact. NOT thread-safe: [NavigationSession] confines it to one coroutine.
 *
 * `onFix` and `onTick` do not allocate (apart from an [Announcement] when one is emitted); [snapshot] allocates
 * the published [NavState].
 */
class RouteTracker(
    rawPlan: RoutePlan,
    private val config: NavConfig = NavConfig(),
    private val revision: Int = 0,
    /** Where along the route to start (resuming after the process was killed); 0 for a fresh route. */
    startAlongMeters: Double = 0.0,
    private val onEvent: ((NavEvent) -> Unit)? = null,
    /** Where the tunnels of this route are, if anything knows; null keeps the plain 30 s signal-loss estimate everywhere. */
    tunnelSpans: TunnelSpanSource? = null,
    /** Optional stop/go detection (IMU) used only while there is no signal inside a known tunnel. */
    private val stopGo: StopGoSignal = StopGoSignal.NONE,
    private val onAnnouncement: ((Announcement) -> Unit)? = null,
) {
    /** The plan as followed: unusable points removed (see [sanitized]). */
    val plan: RoutePlan = rawPlan.sanitized()
    val geometry = RouteGeometry(plan.geometry)

    private val maneuvers: Array<Maneuver> =
        plan.guidance.maneuvers.sortedBy { it.geometryIndex }.toTypedArray()
    private val spans: Array<TunnelSpan> = sanitizeSpans(
        tunnelSpans?.let { src -> try { src.spansFor(geometry) } catch (_: Exception) { emptyList() } } ?: emptyList(),
        geometry.totalMeters,
    ).toTypedArray()
    private val tunnelObserver: TunnelObserver? = tunnelSpans as? TunnelObserver
    private val estimator = TunnelEstimator(config.tunnel)
    private var lossBegun = false
    private var lossSpan = -1
    private var errorMeters = 0.0
    private val mAlong = DoubleArray(maneuvers.size) { geometry.alongOfIndex(maneuvers[it].geometryIndex) }
    private val mAnnounced = IntArray(maneuvers.size) { -1 }
    // Stops at the very start are not stops (a round trip would "reach" one on the first fix).
    private val stopAlong: DoubleArray = plan.guidance.stops.map { geometry.alongOfIndex(it) }.filter { it > 1.0 }.sorted().toDoubleArray()
    // Resuming mid-route: the stops already passed are neither reported nor counted.
    private var nextStop = stopAlong.count { it <= progressAtStart(startAlongMeters) }
    private val limitBySegment = IntArray(geometry.size - 1) { -1 }.also { limits ->
        for (l in plan.guidance.speedLimits) {
            val from = l.startIndex.coerceIn(0, limits.size - 1)
            val to = max(l.endIndex, l.startIndex + 1).coerceIn(from + 1, limits.size)
            for (s in from until to) limits[s] = l.kmh ?: -1
        }
    }

    private fun progressAtStart(start: Double) = if (start.isFinite()) start.coerceIn(0.0, geometry.totalMeters) else 0.0

    var status = NavStatus.ON_ROUTE
        private set

    /** Last usable raw fix, for starting a reroute; null before the first one. */
    val lastFixBearing: Float? get() = if (rawBearing.isNaN()) null else rawBearing
    fun lastFixPoint(): LatLon? = if (hasRaw) LatLon(rawLat, rawLon) else null

    private var progress = if (startAlongMeters.isFinite()) startAlongMeters.coerceIn(0.0, geometry.totalMeters) else 0.0
    private var anchor = progress
    private var anchorTime = -1L
    private var lastFixTime = -1L
    private var speed = 0.0
    private var offCount = 0
    private var offStart = 0L
    private var offDistance = 0.0
    private var stillSince = -1L
    private var mIdx = 0
    private var estimated = false
    private var overSpeed = false
    private var staleStreak = 0
    private var staleLast = 0L
    private var jumpCount = 0
    private var hasRaw = false
    private var rawLat = 0.0
    private var rawLon = 0.0
    private var rawBearing = Float.NaN
    private val match = RouteMatch()

    fun onFix(fix: LocationFix) {
        if (status == NavStatus.ARRIVED) return
        val lat = fix.point.lat
        val lon = fix.point.lon
        // A fix with impossible coordinates is not a fix (NaN would poison every comparison below).
        if (!(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)) return
        val acc = (fix.accuracyMeters ?: config.defaultAccuracyMeters).toDouble()
        // A fix with a useless accuracy is treated as no fix at all (it must not hide a signal loss).
        if (!(acc <= config.maxUsableAccuracyMeters)) return
        val t = fix.timeMillis
        if (lastFixTime >= 0 && t <= lastFixTime) {
            // Duplicate or out of order: dropped. But a clock that jumped back for good (NTP, GPS week rollover)
            // would drop every fix for ever, so a few increasing fixes far in the past re-base the time.
            if (lastFixTime - t <= CLOCK_BACK_MILLIS) return
            staleStreak = if (staleStreak > 0 && t > staleLast) staleStreak + 1 else 1
            staleLast = t
            if (staleStreak < CLOCK_RESET_FIXES) return
            lastFixTime = -1
            anchorTime = -1
            offCount = 0
            stillSince = -1
        }
        staleStreak = 0
        val first = lastFixTime < 0
        val prevFixTime = lastFixTime
        val dt = if (first || t <= lastFixTime) 0.0 else (t - lastFixTime) / 1000.0
        val wasNoSignal = status == NavStatus.NO_SIGNAL

        rawLat = fix.point.lat
        rawLon = fix.point.lon
        rawBearing = fix.bearingDegrees ?: Float.NaN
        hasRaw = true
        val reported = fix.speedMps
        val fixSpeed = if (reported != null && reported.isFinite() && reported >= 0f) reported.toDouble() else Double.NaN
        lastFixTime = t

        val heading = if (!rawBearing.isNaN() && (fixSpeed.isNaN() || fixSpeed >= config.headingMinSpeedMps)) {
            rawBearing.toDouble()
        } else {
            Double.NaN
        }
        val sinceAnchor = if (anchorTime < 0) 0.0 else max(0.0, (t - anchorTime) / 1000.0)
        val reach = if (first) {
            config.firstFixWindowMeters
        } else {
            min(
                config.windowMaxMeters,
                config.windowBaseMeters + 3 * acc + max(2 * max(speed, if (fixSpeed.isNaN()) 0.0 else fixSpeed), 15.0) * sinceAnchor,
            )
        }
        geometry.search(
            fix.point.lat, fix.point.lon,
            if (first) anchor - config.firstFixWindowMeters else anchor - config.backToleranceMeters - 2 * acc, anchor + reach,
            heading, config.headingPenaltyMeters, match,
        )
        var forceResync = false
        if (match.distance > max(config.offRouteMinMeters, config.accuracyFactor * acc) &&
            (first || wasNoSignal || sinceAnchor > LONG_GAP_SECONDS)
        ) {
            // The first fix may be anywhere along the route (resumed trip), and after a long loss the capped window
            // may not contain the car any more: look at the whole route.
            geometry.search(fix.point.lat, fix.point.lon, 0.0, geometry.totalMeters, heading, config.headingPenaltyMeters, match)
            forceResync = true
        }

        val threshold = max(config.offRouteMinMeters, config.accuracyFactor * acc)
        val d = match.distance
        offDistance = d
        val movingSpeed = if (fixSpeed.isNaN()) speed else fixSpeed
        val wrongWay = !heading.isNaN() && !match.segmentBearing.isNaN() && movingSpeed >= config.wrongWayMinSpeedMps &&
            angleDiff(heading, match.segmentBearing) > config.wrongWayDegrees

        if (wasNoSignal) {
            status = NavStatus.ON_ROUTE
            estimated = false
        }

        var resync = first || wasNoSignal || dt > 10.0 || forceResync
        if (d > threshold || wrongWay) {
            if (offCount == 0) offStart = t
            offCount++
            val duration = t - offStart
            val confirmed = (offCount >= config.offRouteFixes && duration >= config.offRouteMinMillis) ||
                (offCount >= 2 && duration >= config.offRouteMaxMillis) ||
                (d > threshold * config.farFactor && offCount >= config.farFixes)
            if (confirmed && status == NavStatus.ON_ROUTE) status = NavStatus.OFF_ROUTE
            if (!fixSpeed.isNaN()) speed = fixSpeed
            return
        }
        if (d <= threshold * config.onRouteBand) {
            offCount = 0
            if (status == NavStatus.OFF_ROUTE || status == NavStatus.REROUTING) {
                status = NavStatus.ON_ROUTE
                resync = true
            }
        } else if (status == NavStatus.OFF_ROUTE || status == NavStatus.REROUTING) {
            return // between the "on" band and the threshold: not enough to rejoin
        }

        var along = match.along
        if (!resync) {
            // A spike along the road (multipath) must not drag the progress ahead for good: a jump the speed cannot
            // explain is ignored unless it repeats, which means it was real.
            val vmax = max(speed, if (fixSpeed.isNaN()) 0.0 else fixSpeed)
            if (along - anchor > 1.6 * vmax * sinceAnchor + 3 * acc + JUMP_SLACK_METERS) {
                if (++jumpCount < JUMP_CONFIRM_FIXES) return
                resync = true
            }
        }
        jumpCount = 0
        if (resync) {
            if (wasNoSignal && lossBegun) along = handBack(t, prevFixTime, along, acc)
            progress = along
            if (!fixSpeed.isNaN()) speed = fixSpeed
        } else {
            if (!fixSpeed.isNaN()) speed = 0.5 * speed + 0.5 * fixSpeed
            val newProgress = if (!fixSpeed.isNaN() && fixSpeed < config.stationarySpeedMps) {
                anchor
            } else {
                val predicted = anchor + speed * dt
                val ratio = acc / 8.0
                val alpha = 1.0 / (1.0 + ratio * ratio)
                val innovation = along - predicted
                if (fixSpeed.isNaN() && dt > 0.2) speed = max(0.0, speed + 0.4 * alpha * alpha * innovation / dt)
                predicted + alpha * innovation
            }
            progress = max(anchor, newProgress)
        }
        progress = progress.coerceIn(0.0, geometry.totalMeters)
        anchor = progress
        anchorTime = t
        estimated = false
        errorMeters = 0.0
        if (lossBegun) stopGo.release()
        lossBegun = false
        lossSpan = -1
        estimator.onFix(t, progress, fixSpeed, speed, acc)

        if (!fixSpeed.isNaN() && fixSpeed < config.arrivalStopSpeedMps) {
            if (stillSince < 0) stillSince = t
        } else {
            stillSince = -1
        }
        derive(t, allowArrival = true, resynced = resync)
    }

    /**
     * Advances the clock when no fix arrived. After [NavConfig.signalLossMillis] without a fix it switches to
     * [NavStatus.NO_SIGNAL] and dead-reckons with the last speed for up to [NavConfig.estimateMaxMillis].
     * Returns true when the published state may have changed.
     */
    fun onTick(nowMillis: Long): Boolean {
        if (status == NavStatus.ARRIVED || lastFixTime < 0) return false
        val gap = nowMillis - lastFixTime
        if (gap < config.signalLossMillis) return false
        if (status == NavStatus.OFF_ROUTE || status == NavStatus.REROUTING) return false
        status = NavStatus.NO_SIGNAL
        estimated = true
        if (!lossBegun) beginLoss()
        val lastPoint = max(anchor, geometry.totalMeters - 1.0)
        if (lossSpan < 0) {
            // No tunnel known here: the plain estimate, last speed held for at most estimateMaxMillis.
            val seconds = min(gap, config.estimateMaxMillis) / 1000.0
            val estimate = min(anchor + speed * seconds, lastPoint)
            if (estimate > progress) progress = estimate
            errorMeters = estimator.errorMeters(gap / 1000.0, progress - anchor)
        } else {
            // Known tunnel: advance until its exit (never past it, never to the end of the route), up to the hard caps.
            val sp = spans[lossSpan]
            val slack = if (sp.source == TunnelSource.LEARNED) {
                config.tunnel.learnedSlackFraction * sp.lengthMeters + config.tunnel.learnedSlackMeters
            } else {
                0.0
            }
            val estimate = estimator.advance(nowMillis, anchor, stopGo.motionState(nowMillis), min(sp.endMeters + slack, lastPoint))
            if (estimate > progress) progress = estimate
            speed = estimator.speedMps
            errorMeters = estimator.errorMeters(
                min(gap, config.tunnel.hardMaxMillis) / 1000.0, progress - anchor, sp.endMeters + slack - min(anchor, sp.startMeters),
            )
        }
        derive(nowMillis, allowArrival = false, resynced = false)
        return true
    }

    /** A loss starts: it is expected (inside or just before a known span) or not. */
    private fun beginLoss() {
        lossBegun = true
        lossSpan = -1
        for (i in spans.indices) {
            if (anchor >= spans[i].startMeters - config.tunnel.entryMarginMeters && anchor <= spans[i].endMeters) {
                lossSpan = i
                break
            }
        }
    }

    /**
     * The first fix after a loss, at route position [along]: snaps to the portal when the fix is poor and lands near
     * the exit of the span, and teaches the learned store about a loss that had no known span. Returns the position to use.
     */
    private fun handBack(t: Long, prevFixTime: Long, along: Double, accuracy: Double): Double {
        if (lossSpan >= 0) {
            val end = spans[lossSpan].endMeters
            if (accuracy > config.tunnel.snapAccuracyMeters && abs(along - end) <= config.tunnel.snapMarginMeters) return end
        } else if (config.tunnel.learn && prevFixTime >= 0 && t - prevFixTime >= config.tunnel.learnMinMillis &&
            along - anchor >= config.tunnel.learnMinMeters
        ) {
            tunnelObserver?.onTunnelObserved(geometry.pointAt(anchor), geometry.pointAt(along))
        }
        return along
    }

    private fun inSpan(along: Double): Boolean {
        for (s in spans) if (along >= s.startMeters && along <= s.endMeters) return true
        return false
    }

    /** Inside a known tunnel the "now" prompt of a maneuver cannot be timed when the estimate is looser than its band. */
    private fun nowUnreliable(along: Double): Boolean =
        estimated && spans.isNotEmpty() && errorMeters > config.announcements.now.metersAt(speed) && inSpan(along)

    /** The session marks that a reroute is running (only meaningful while off route). */
    fun setRerouting(active: Boolean) {
        if (active && status == NavStatus.OFF_ROUTE) status = NavStatus.REROUTING
        else if (!active && status == NavStatus.REROUTING) status = NavStatus.OFF_ROUTE
    }

    private fun derive(t: Long, allowArrival: Boolean, resynced: Boolean) {
        while (mIdx > 0 && mAlong[mIdx - 1] > progress) mIdx--
        val passedFrom = mIdx
        while (mIdx < maneuvers.size && mAlong[mIdx] <= progress) mIdx++
        // Maneuvers crossed since the last update. Between two fixes the car may jump over the "now" band; the
        // prompt must not be lost, so the last crossed maneuver that never got its "now" gets it here. The older
        // ones are superseded (silently marked), and so is everything when the position was re-synchronised
        // (rejoined the route elsewhere): a prompt for a turn that is already behind would only confuse.
        for (k in passedFrom until mIdx) {
            if (mAnnounced[k] >= LEVEL_NOW) continue
            if (!resynced && k == mIdx - 1 && !nowUnreliable(mAlong[k])) fire(k, LEVEL_NOW, 0.0) else mAnnounced[k] = LEVEL_NOW
        }
        if (allowArrival) {
            while (nextStop < stopAlong.size && progress >= stopAlong[nextStop] - config.arrivalRadiusMeters) {
                val point = geometry.pointAt(stopAlong[nextStop])
                val skipped = resynced && progress - stopAlong[nextStop] > config.arrivalRadiusMeters
                onEvent?.invoke(if (skipped) NavEvent.StopSkipped(nextStop, point) else NavEvent.StopReached(nextStop, point))
                nextStop++
            }
        }
        if (mIdx < maneuvers.size) announce(mAlong[mIdx] - progress)

        val limit = limitBySegment[geometry.segmentAt(progress)]
        if (limit > 0) {
            // Whole km/h, as the speedometer shows it: a float round trip of 50 km/h (13.888889 m/s) is 50.0000004
            // and must not count as "over" a 50 km/h limit. Over means strictly above limit + tolerance.
            val kmh = Math.round(speed * 3.6).toDouble()
            val allowed = limit + config.speedToleranceKmh
            overSpeed = if (overSpeed) kmh > allowed - config.speedHysteresisKmh else kmh > allowed
        } else {
            overSpeed = false
        }

        if (allowArrival) {
            val remaining = geometry.totalMeters - progress
            val stopped = stillSince >= 0 && t - stillSince >= config.arrivalStopMillis
            if (remaining <= config.arrivalRadiusMeters || (remaining <= config.arrivalStopRadiusMeters && stopped)) {
                if (mIdx == maneuvers.size - 1 && mAnnounced[mIdx] < LEVEL_NOW) fire(mIdx, LEVEL_NOW, remaining)
                status = NavStatus.ARRIVED
                progress = geometry.totalMeters
                speed = 0.0
                overSpeed = false
            }
        }
    }

    private fun announce(distance: Double) {
        val bands = config.announcements
        var level = when {
            distance <= bands.now.metersAt(speed) -> LEVEL_NOW
            distance <= bands.near.metersAt(speed) -> LEVEL_NEAR
            distance <= bands.far.metersAt(speed) -> LEVEL_FAR
            else -> -1
        }
        if (level == LEVEL_NOW && nowUnreliable(mAlong[mIdx])) level = LEVEL_NEAR
        if (level > mAnnounced[mIdx]) fire(mIdx, level, distance)
    }

    private fun fire(index: Int, level: Int, distance: Double) {
        mAnnounced[index] = level
        val kind = when (level) {
            LEVEL_NOW -> AnnouncementKind.NOW
            LEVEL_NEAR -> AnnouncementKind.NEAR
            else -> AnnouncementKind.FAR
        }
        onAnnouncement?.invoke(Announcement(maneuvers[index], kind, distance.roundToInt().coerceAtLeast(0)))
    }

    fun snapshot(): NavState {
        val total = geometry.totalMeters
        val remaining = max(0.0, total - progress)
        val next = if (mIdx < maneuvers.size) ManeuverInfo(maneuvers[mIdx], mAlong[mIdx] - progress) else null
        val following = if (mIdx + 1 < maneuvers.size) ManeuverInfo(maneuvers[mIdx + 1], mAlong[mIdx + 1] - progress) else null
        val limit = limitBySegment[geometry.segmentAt(progress)]
        return NavState(
            status = status,
            position = geometry.pointAt(progress),
            bearingDegrees = geometry.bearingAt(progress),
            traveledMeters = progress,
            remainingMeters = remaining,
            remainingSeconds = if (total > 0) plan.durationSeconds * remaining / total else 0.0,
            nextManeuver = next,
            followingManeuver = following,
            speedLimitKmh = if (limit > 0) limit else null,
            overSpeedLimit = overSpeed,
            lanes = next?.maneuver?.lanes ?: emptyList(),
            estimated = estimated,
            speedMps = speed,
            offRouteMeters = if (lastFixTime < 0) 0.0 else offDistance,
            routeRevision = revision,
            stopsRemaining = stopAlong.size - nextStop,
            nextStopMeters = if (nextStop < stopAlong.size) max(0.0, stopAlong[nextStop] - progress) else null,
            errorMeters = if (estimated) errorMeters else 0.0,
            inTunnel = status == NavStatus.NO_SIGNAL && lossSpan >= 0,
        )
    }

    private companion object {
        const val LEVEL_FAR = 0
        const val LEVEL_NEAR = 1
        const val LEVEL_NOW = 2
        const val CLOCK_BACK_MILLIS = 5_000L
        const val CLOCK_RESET_FIXES = 3
        const val LONG_GAP_SECONDS = 30.0
        const val JUMP_SLACK_METERS = 60.0
        const val JUMP_CONFIRM_FIXES = 3
    }
}
