package com.qtekfun.mapas.core.nav

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.LocationFix
import com.qtekfun.mapas.core.routing.Maneuver
import com.qtekfun.mapas.core.routing.RoutePlan
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
    val plan: RoutePlan,
    private val config: NavConfig = NavConfig(),
    private val revision: Int = 0,
    private val onAnnouncement: ((Announcement) -> Unit)? = null,
) {
    val geometry = RouteGeometry(plan.geometry)

    private val maneuvers: Array<Maneuver> =
        plan.guidance.maneuvers.sortedBy { it.geometryIndex }.toTypedArray()
    private val mAlong = DoubleArray(maneuvers.size) { geometry.alongOfIndex(maneuvers[it].geometryIndex) }
    private val mAnnounced = IntArray(maneuvers.size) { -1 }
    private val limitBySegment = IntArray(geometry.size - 1) { -1 }.also { limits ->
        for (l in plan.guidance.speedLimits) {
            val from = l.startIndex.coerceIn(0, limits.size - 1)
            val to = max(l.endIndex, l.startIndex + 1).coerceIn(from + 1, limits.size)
            for (s in from until to) limits[s] = l.kmh ?: -1
        }
    }

    var status = NavStatus.ON_ROUTE
        private set

    /** Last usable raw fix, for starting a reroute; null before the first one. */
    val lastFixBearing: Float? get() = if (rawBearing.isNaN()) null else rawBearing
    fun lastFixPoint(): LatLon? = if (hasRaw) LatLon(rawLat, rawLon) else null

    private var progress = 0.0
    private var anchor = 0.0
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
    private var hasRaw = false
    private var rawLat = 0.0
    private var rawLon = 0.0
    private var rawBearing = Float.NaN
    private val match = RouteMatch()

    fun onFix(fix: LocationFix) {
        if (status == NavStatus.ARRIVED) return
        val acc = (fix.accuracyMeters ?: config.defaultAccuracyMeters).toDouble()
        // A fix with a useless accuracy is treated as no fix at all (it must not hide a signal loss).
        if (!(acc <= config.maxUsableAccuracyMeters)) return
        val t = fix.timeMillis
        val first = lastFixTime < 0
        val dt = if (first || t <= lastFixTime) 0.0 else (t - lastFixTime) / 1000.0
        val wasNoSignal = status == NavStatus.NO_SIGNAL

        rawLat = fix.point.lat
        rawLon = fix.point.lon
        rawBearing = fix.bearingDegrees ?: Float.NaN
        hasRaw = true
        val fixSpeed = fix.speedMps?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 } ?: Double.NaN
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
            anchor - config.backToleranceMeters - 2 * acc, anchor + reach,
            heading, config.headingPenaltyMeters, match,
        )

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

        var resync = first || wasNoSignal || dt > 10.0
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

        val along = match.along
        if (resync) {
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

        if (!fixSpeed.isNaN() && fixSpeed < config.arrivalStopSpeedMps) {
            if (stillSince < 0) stillSince = t
        } else {
            stillSince = -1
        }
        derive(t, allowArrival = true)
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
        val seconds = min(gap, config.estimateMaxMillis) / 1000.0
        val estimate = min(anchor + speed * seconds, max(anchor, geometry.totalMeters - 1.0))
        if (estimate > progress) progress = estimate
        derive(nowMillis, allowArrival = false)
        return true
    }

    /** The session marks that a reroute is running (only meaningful while off route). */
    fun setRerouting(active: Boolean) {
        if (active && status == NavStatus.OFF_ROUTE) status = NavStatus.REROUTING
        else if (!active && status == NavStatus.REROUTING) status = NavStatus.OFF_ROUTE
    }

    private fun derive(t: Long, allowArrival: Boolean) {
        while (mIdx > 0 && mAlong[mIdx - 1] > progress) mIdx--
        while (mIdx < maneuvers.size && mAlong[mIdx] <= progress) mIdx++
        if (mIdx < maneuvers.size) announce(mAlong[mIdx] - progress)

        val limit = limitBySegment[geometry.segmentAt(progress)]
        if (limit > 0) {
            val kmh = speed * 3.6
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
        val level = when {
            distance <= bands.now.metersAt(speed) -> LEVEL_NOW
            distance <= bands.near.metersAt(speed) -> LEVEL_NEAR
            distance <= bands.far.metersAt(speed) -> LEVEL_FAR
            else -> -1
        }
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
        )
    }

    private companion object {
        const val LEVEL_FAR = 0
        const val LEVEL_NEAR = 1
        const val LEVEL_NOW = 2
    }
}
