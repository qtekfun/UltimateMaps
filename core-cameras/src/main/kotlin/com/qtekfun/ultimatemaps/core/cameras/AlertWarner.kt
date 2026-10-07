package com.qtekfun.ultimatemaps.core.cameras

import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
import com.qtekfun.ultimatemaps.core.nav.RouteMatch

enum class AlertStage { FAR, NEAR }

/** One announcement to make. [distanceMeters] is the exact distance ahead; the phrase rounds it. */
data class AlertEvent(
    val target: AlertTarget,
    val stage: AlertStage,
    val distanceMeters: Int,
    val limitKmh: Int?,
    val speedKmh: Int?,
    /** True only when both the limit and the speed are known and the speed is above the limit. */
    val speeding: Boolean,
)

/**
 * Decides when to announce something ahead (a camera, a section, a mobile-radar zone, a stopped vehicle with a V16
 * beacon, an accident...). Pure logic: no clock, no threads, no Android; fixes and the time come in as arguments, so
 * tests drive it with a simulated location source.
 *
 * Two ways in, one set of rules:
 *  - [onRouteFix]: while navigating. A target counts only when it lies ON the route (within [ROUTE_LATERAL_METERS] of
 *    it) and ahead along it, so a camera on a parallel road is ignored; the distance is measured along the route.
 *  - [onFreeFix]: free driving. A target counts when it is within [CONE_DEGREES] of the direction of travel.
 * In both, a target with a known axis (road direction) counts only when the travel direction agrees with it
 * ([AlertTarget.sense]); one without counts for both directions.
 *
 * Rules (numbers are design values, not measured; see docs/phase2/cameras-implementation.md):
 *  - nothing below [MIN_SPEED_MPS] (walking, stopped) and, in free driving, nothing without a direction of travel;
 *  - look-ahead = speed x [LOOKAHEAD_SECONDS], between [MIN_LOOKAHEAD_METERS] and [MAX_LOOKAHEAD_METERS];
 *  - one FAR announcement per target group (the two ends of an incident, a zone) per approach, only for categories
 *    the user enabled; only the nearest eligible target per fix, and at least [MIN_GAP_MILLIS] between announcements;
 *  - a NEAR announcement at [NEAR_METERS] for cameras and sections when the known limit is exceeded;
 *  - "only if speeding" suppresses FAR for cameras with a known limit that is not exceeded (unknown limit: warns);
 *  - a target closer than [MIN_ANNOUNCE_METERS] is marked as passed silently (too late to be useful);
 *  - a group is forgotten once the vehicle is [FORGET_FACTOR] x look-ahead away, so the next approach warns again.
 */
class AlertWarner(
    private val sources: List<AlertSource>,
    private val settings: () -> CameraSettings,
    private val onAlert: (AlertEvent) -> Unit,
) {
    private class Warned(var stage: AlertStage, var lat: Double, var lon: Double)

    private val warned = HashMap<String, Warned>()
    private val match = RouteMatch()
    private var lastAlertAt = Long.MIN_VALUE / 2
    private var prevLat = Double.NaN
    private var prevLon = Double.NaN
    private var derivedHeading = Double.NaN

    // Per-fix working state (no allocation in the hot path).
    private var curLat = 0.0
    private var curLon = 0.0
    private var curHeading = Double.NaN
    private var curSpeed = Double.NaN
    private var curLookahead = 0.0
    private var cfg = CameraSettings()
    private var route: RouteGeometry? = null
    private var curAlong = 0.0
    private var bestTarget: AlertTarget? = null
    private var bestDistance = Double.MAX_VALUE
    private var bestStage = AlertStage.FAR

    private val freeVisitor = AlertSource.Visitor { t -> considerFree(t) }
    private val routeVisitor = AlertSource.Visitor { t -> considerRoute(t) }

    /** Forgets everything (a new trip, the user turned a switch off). */
    @Synchronized
    fun reset() {
        warned.clear()
        lastAlertAt = Long.MIN_VALUE / 2
        prevLat = Double.NaN
        prevLon = Double.NaN
        derivedHeading = Double.NaN
    }

    /** Free driving: a position, the direction of travel (null: derived from the last movement) and the speed. */
    @Synchronized
    fun onFreeFix(lat: Double, lon: Double, headingDeg: Float?, speedMps: Float?, nowMillis: Long) {
        val heading = headingDeg?.toDouble()?.takeIf { it in 0.0..360.0 } ?: deriveHeading(lat, lon)
        updatePrev(lat, lon)
        if (heading.isNaN()) return
        if (!begin(lat, lon, heading, speedMps)) return
        route = null
        scan(freeVisitor)
        finish(nowMillis)
    }

    /** Navigating: the position projected on [geometry] (metres [alongMeters] from its start) and the speed. */
    @Synchronized
    fun onRouteFix(geometry: RouteGeometry, alongMeters: Double, lat: Double, lon: Double, speedMps: Float?, nowMillis: Long) {
        if (!begin(lat, lon, Double.NaN, speedMps)) return
        route = geometry
        curAlong = alongMeters
        scan(routeVisitor)
        finish(nowMillis)
    }

    private fun deriveHeading(lat: Double, lon: Double): Double {
        if (!prevLat.isNaN() && TargetGrid.distanceMeters(prevLat, prevLon, lat, lon) >= MIN_MOVE_METERS) {
            derivedHeading = TargetGrid.bearingDegrees(prevLat, prevLon, lat, lon)
        }
        return derivedHeading
    }

    private fun updatePrev(lat: Double, lon: Double) {
        if (prevLat.isNaN() || TargetGrid.distanceMeters(prevLat, prevLon, lat, lon) >= MIN_MOVE_METERS) {
            prevLat = lat
            prevLon = lon
        }
    }

    private fun begin(lat: Double, lon: Double, heading: Double, speedMps: Float?): Boolean {
        cfg = settings()
        if (!cfg.anything) {
            if (warned.isNotEmpty()) warned.clear()
            return false
        }
        val speed = speedMps?.toDouble()?.takeIf { it >= 0 } ?: Double.NaN
        if (!speed.isNaN() && speed < MIN_SPEED_MPS) return false
        curLat = lat; curLon = lon; curHeading = heading; curSpeed = speed
        curLookahead = (if (speed.isNaN()) MIN_LOOKAHEAD_METERS else speed * LOOKAHEAD_SECONDS)
            .coerceIn(MIN_LOOKAHEAD_METERS, MAX_LOOKAHEAD_METERS)
        bestTarget = null
        bestDistance = Double.MAX_VALUE
        prune()
        return true
    }

    private fun scan(visitor: AlertSource.Visitor) {
        for (s in sources) s.forEachNear(curLat, curLon, curLookahead + SCAN_MARGIN_METERS, visitor)
    }

    private fun prune() {
        if (warned.isEmpty()) return
        val limit = curLookahead * FORGET_FACTOR + SCAN_MARGIN_METERS
        val it = warned.values.iterator()
        while (it.hasNext()) {
            val w = it.next()
            if (TargetGrid.distanceMeters(curLat, curLon, w.lat, w.lon) > limit) it.remove()
        }
    }

    private fun enabled(c: AlertCategory): Boolean = when (c) {
        AlertCategory.FIXED_CAMERA, AlertCategory.SECTION -> cfg.fixedEnabled
        AlertCategory.MOBILE_ZONE -> cfg.mobileZonesEnabled
        AlertCategory.V16 -> cfg.v16Enabled
        AlertCategory.ACCIDENT, AlertCategory.CLOSURE, AlertCategory.CONGESTION, AlertCategory.OBSTACLE -> cfg.incidentsEnabled
    }

    /** Whether travelling at [travel] degrees is a direction [t] applies to. NaN travel: unknown, so yes. */
    private fun directionOk(t: AlertTarget, travel: Double): Boolean {
        val axis = t.axisDeg ?: return true
        if (travel.isNaN()) return true
        val along = TargetGrid.angleDiff(travel, axis.toDouble()) <= t.toleranceDeg
        val against = TargetGrid.angleDiff(travel, axis + 180.0) <= t.toleranceDeg
        return when (t.sense) {
            AxisSense.BOTH -> along || against
            AxisSense.ALONG -> along
            AxisSense.AGAINST -> against
        }
    }

    private fun considerFree(t: AlertTarget) {
        if (!enabled(t.category)) return
        val d = TargetGrid.distanceMeters(curLat, curLon, t.lat, t.lon)
        if (d > curLookahead) return
        val toTarget = TargetGrid.bearingDegrees(curLat, curLon, t.lat, t.lon)
        if (TargetGrid.angleDiff(toTarget, curHeading) > CONE_DEGREES) return
        if (!directionOk(t, curHeading)) return
        offer(t, d)
    }

    private fun considerRoute(t: AlertTarget) {
        if (!enabled(t.category)) return
        val g = route ?: return
        g.search(t.lat, t.lon, curAlong, curAlong + curLookahead + SCAN_MARGIN_METERS, Double.NaN, 0.0, match)
        if (match.distance > ROUTE_LATERAL_METERS) return
        val ahead = match.along - curAlong
        if (ahead < 0 || ahead > curLookahead) return
        if (!directionOk(t, match.segmentBearing)) return
        offer(t, ahead)
    }

    private fun offer(t: AlertTarget, distance: Double) {
        val w = warned[t.group]
        if (distance < MIN_ANNOUNCE_METERS) {
            if (w == null) warned[t.group] = Warned(AlertStage.NEAR, t.lat, t.lon) // passed without a word
            return
        }
        val stage: AlertStage
        if (w == null) {
            stage = AlertStage.FAR
            if (suppressedBySpeed(t)) return
        } else {
            if (w.stage == AlertStage.NEAR || distance > NEAR_METERS || !(t.category == AlertCategory.FIXED_CAMERA || t.category == AlertCategory.SECTION)) return
            if (!isSpeeding(t)) return
            stage = AlertStage.NEAR
        }
        if (distance < bestDistance) {
            bestTarget = t
            bestDistance = distance
            bestStage = stage
        }
    }

    private fun isSpeeding(t: AlertTarget): Boolean {
        val limit = t.limitKmh ?: return false
        return !curSpeed.isNaN() && curSpeed * 3.6 > limit
    }

    private fun suppressedBySpeed(t: AlertTarget): Boolean {
        if (!cfg.warnOnlyIfSpeeding) return false
        if (t.category != AlertCategory.FIXED_CAMERA && t.category != AlertCategory.SECTION) return false
        val limit = t.limitKmh ?: return false
        return curSpeed.isNaN() || curSpeed * 3.6 <= limit
    }

    private fun finish(now: Long) {
        val t = bestTarget ?: return
        if (now - lastAlertAt < MIN_GAP_MILLIS) return
        lastAlertAt = now
        val w = warned[t.group]
        if (w == null) warned[t.group] = Warned(bestStage, t.lat, t.lon) else { w.stage = bestStage; w.lat = t.lat; w.lon = t.lon }
        val kmh = if (curSpeed.isNaN()) null else Math.round(curSpeed * 3.6).toInt()
        onAlert(AlertEvent(t, bestStage, bestDistance.toInt(), t.limitKmh, kmh, isSpeeding(t)))
        bestTarget = null
    }

    companion object {
        const val MIN_SPEED_MPS = 2.5
        const val LOOKAHEAD_SECONDS = 30.0
        const val MIN_LOOKAHEAD_METERS = 400.0
        const val MAX_LOOKAHEAD_METERS = 1000.0
        const val NEAR_METERS = 250.0
        const val MIN_ANNOUNCE_METERS = 60.0
        const val CONE_DEGREES = 35.0
        const val ROUTE_LATERAL_METERS = 35.0
        const val MIN_GAP_MILLIS = 6_000L
        const val FORGET_FACTOR = 1.6
        private const val SCAN_MARGIN_METERS = 100.0
        private const val MIN_MOVE_METERS = 15.0
    }
}
