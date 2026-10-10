package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The position and heading of the vehicle marker between two location fixes (pure maths, no clock of its own and no
 * allocation after construction: the caller passes the time and reads [lat], [lon] and [bearing] after [advance]).
 *
 * The fixes arrive about once a second, so drawing the marker only when one arrives makes it jump. Instead the
 * displayed point D chases a prediction T: the last fix plus the last speed times the time since the fix (the
 * prediction stops after [EXTRAPOLATE_MAX_MILLIS], so a late fix never sends the marker on). D moves at the last
 * speed plus a fraction of the error `T - D` per [TAU_MILLIS]:
 *
 * - On a route ([setRoute] and `onRoute`) everything happens in metres along the polyline, so the marker takes the
 *   corners and never leaves the line, and it never goes past the end of the route.
 * - Off the route (or with no route) the same law is applied to latitude and longitude along the fix's course.
 * - The error is corrected gently: the marker may slow down but moves backwards at most [MAX_BACK_MPS] (a stopped car
 *   that was predicted too far ahead slides back instead of jumping), and catches up at most at `3 * speed + 5` m/s.
 *   An error above [SNAP_METERS], a new route revision or a change between the two modes resynchronises at once.
 * - The heading follows the route heading at D (or the fix course) over [HEADING_TAU_MILLIS] by the shortest angle;
 *   below [NavCameraPlanner.STOPPED_MPS] it is kept, as a stopped receiver reports a random course.
 *
 * Not thread-safe: used from the main thread.
 */
class PuckInterpolator {
    /** Displayed position (NaN until the first fix). */
    var lat = Double.NaN
        private set
    var lon = Double.NaN
        private set

    /** Displayed heading, degrees clockwise from north in [0, 360). */
    var bearing = 0.0
        private set

    val hasPose: Boolean get() = !lat.isNaN()

    private var route: RouteGeometry? = null
    private var routeMode = false
    private var revision = Int.MIN_VALUE
    private val tmp = DoubleArray(2)

    private var fixT = 0L
    private var fixLat = 0.0
    private var fixLon = 0.0
    private var fixAlong = 0.0
    private var fixBearing = 0.0
    private var speed = 0.0
    private var lastT = 0L
    private var dAlong = 0.0

    /** The route the `along` values of the fixes refer to; call it before the fix that comes with a new route. */
    /** The speed of the last fix, m/s. */
    val speedMps: Double get() = speed

    fun setRoute(geometry: RouteGeometry?) {
        route = geometry
    }

    /** True when a fix is recent enough for the prediction to still be moving the marker at [t]. */
    fun isPredicting(t: Long): Boolean = hasPose && speed > MOVING_MPS && t - fixT < EXTRAPOLATE_MAX_MILLIS

    /**
     * A new fix at time [t] (the same clock as [advance]): its position, metres [along] the route, [speedMps], course
     * and the route [revision] (a change snaps the marker). [onRoute] false (off route, or no matched position) uses
     * the free mode.
     */
    fun onFix(t: Long, lat: Double, lon: Double, along: Double, speedMps: Double, bearingDegrees: Double, revision: Int, onRoute: Boolean) {
        val useRoute = onRoute && route != null
        val snap = !hasPose || revision != this.revision || useRoute != routeMode
        fixT = t
        fixLat = lat
        fixLon = lon
        fixAlong = along
        speed = speedMps.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        fixBearing = NavCameraPlanner.normalize(bearingDegrees.takeIf { it.isFinite() } ?: bearing)
        this.revision = revision
        routeMode = useRoute
        if (snap) {
            val first = !hasPose
            dAlong = along
            this.lat = lat
            this.lon = lon
            lastT = t
            if (first) bearing = fixBearing
        }
    }

    /** Moves the displayed pose to time [t]. Cheap: a few multiplications, no allocation. */
    fun advance(t: Long) {
        if (!hasPose) return
        val dt = (t - lastT).coerceIn(0L, MAX_STEP_MILLIS).toDouble() / 1000.0
        lastT = max(lastT, t)
        val sinceFix = (t - fixT).coerceIn(0L, EXTRAPOLATE_MAX_MILLIS).toDouble() / 1000.0
        val vEff = if (t - fixT < EXTRAPOLATE_MAX_MILLIS) speed else 0.0
        val tau = TAU_MILLIS / 1000.0
        val targetBearing: Double
        val g = route
        if (routeMode && g != null) {
            val target = min(fixAlong + speed * sinceFix, g.totalMeters)
            val err = target - dAlong
            if (abs(err) > SNAP_METERS) {
                dAlong = target
            } else {
                val rate = (vEff + err / tau).coerceIn(-MAX_BACK_MPS, 3.0 * vEff + 5.0)
                var next = dAlong + rate * dt
                if (err >= 0.0 && next > target) next = target // never overshoot the prediction going forward
                dAlong = next
            }
            dAlong = dAlong.coerceIn(0.0, g.totalMeters)
            g.pointInto(dAlong, tmp)
            lat = tmp[0]
            lon = tmp[1]
            targetBearing = g.bearingAt(dAlong).toDouble()
        } else {
            val rad = Math.toRadians(fixBearing)
            val cosLat = max(cos(Math.toRadians(fixLat)), 1e-6)
            val vLat = vEff * cos(rad) / METERS_PER_DEGREE
            val vLon = vEff * sin(rad) / (METERS_PER_DEGREE * cosLat)
            val tLat = fixLat + speed * sinceFix * cos(rad) / METERS_PER_DEGREE
            val tLon = fixLon + speed * sinceFix * sin(rad) / (METERS_PER_DEGREE * cosLat)
            val eLat = tLat - lat
            val eLon = tLon - lon
            if (hypot(eLat * METERS_PER_DEGREE, eLon * METERS_PER_DEGREE * cosLat) > SNAP_METERS) {
                lat = tLat
                lon = tLon
            } else {
                lat += (vLat + eLat / tau) * dt
                lon += (vLon + eLon / tau) * dt
            }
            targetBearing = fixBearing
        }
        if (speed >= NavCameraPlanner.STOPPED_MPS) bearing = approachAngle(bearing, targetBearing, dt * 1000.0, HEADING_TAU_MILLIS)
    }

    /** Forgets everything (navigation ended): the next fix places the marker without any glide. */
    fun reset() {
        lat = Double.NaN
        lon = Double.NaN
        revision = Int.MIN_VALUE
        speed = 0.0
    }

    companion object {
        /** Time constant of the correction towards the prediction. */
        const val TAU_MILLIS = 400.0

        /** How long after a fix the prediction keeps moving: a late fix does not send the marker on. */
        const val EXTRAPOLATE_MAX_MILLIS = 1_500L

        const val HEADING_TAU_MILLIS = 250.0

        /** The slowest the marker may slide backwards when it was predicted too far ahead. */
        const val MAX_BACK_MPS = 2.0

        /** Error beyond which the marker is placed on the prediction at once (a reroute, a tunnel exit). */
        const val SNAP_METERS = 150.0

        /** A frame that comes after a stall counts for at most this long, so the maths cannot overshoot. */
        const val MAX_STEP_MILLIS = 250L

        private const val MOVING_MPS = 0.1
        private const val METERS_PER_DEGREE = 111_194.9266

        /** [current] moved towards [target] by the shortest angle with a time constant of [tauMillis] over [dtMillis]. */
        fun approachAngle(current: Double, target: Double, dtMillis: Double, tauMillis: Double): Double {
            val f = 1.0 - exp(-dtMillis / tauMillis)
            return NavCameraPlanner.normalize(current + NavCameraPlanner.signedDelta(current, target) * f)
        }

        fun approach(current: Double, target: Double, dtMillis: Double, tauMillis: Double): Double =
            current + (target - current) * (1.0 - exp(-dtMillis / tauMillis))
    }
}
