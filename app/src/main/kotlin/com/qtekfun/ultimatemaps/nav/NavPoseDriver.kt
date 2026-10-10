package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.CameraPadding
import com.qtekfun.ultimatemaps.core.map.CameraState
import com.qtekfun.ultimatemaps.core.map.MapEngine
import com.qtekfun.ultimatemaps.core.nav.NavState
import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
import kotlin.math.abs
import kotlin.math.max

/**
 * Draws the vehicle marker and the follow camera once per display frame instead of once per fix, so both glide and
 * stay in sync (the camera centre IS the interpolated marker position plus an offset that decays to zero).
 *
 * The cause of the jerks this replaces: the marker was set once per fix (1 Hz) and the camera got a throttled
 * `animateCamera` toward each fix, two unrelated motions, so the marker stepped on the map and, as the camera glided
 * on its own timeline, also against the screen.
 *
 * Per frame, [frame] advances the [PuckInterpolator], eases zoom, tilt, bearing and padding toward the target set by
 * [retarget] (time constant [FOLLOW_TAU_MILLIS], or [TRANSITION_TAU_MILLIS] after a start, recenter or 2D/3D switch),
 * decays the centre offset, and pushes the result to the [engine] only when something moved. The maths allocates
 * nothing; the values handed to the map API (a few small objects per push) are the map library's own interface.
 * No clock and no scheduling here: the host calls [frame] from the display frame callback.
 */
class NavPoseDriver(private val engine: MapEngine) {
    val puck = PuckInterpolator()

    /**
     * The phone's compass heading (degrees from true north), or null when there is none or it is switched off. While the vehicle
     * is (almost) standing the arrow shows it instead of the course the route implies, so a driver who is facing the wrong
     * way at the start sees it.
     */
    var deviceHeading: () -> Float? = { null }

    private var following = false
    private var hasTarget = false
    private var tauMillis = FOLLOW_TAU_MILLIS
    private var tgtZoom = 0.0
    private var tgtTilt = 0.0
    private var tgtBearing = 0.0
    private var tgtLeft = 0.0
    private var tgtTop = 0.0
    private var tgtRight = 0.0
    private var tgtBottom = 0.0
    private var zoom = 0.0
    private var tilt = 0.0
    private var camBearing = 0.0
    private var left = 0.0
    private var top = 0.0
    private var right = 0.0
    private var bottom = 0.0
    private var offLat = 0.0
    private var offLon = 0.0
    private var lastFrameAt = Long.MIN_VALUE
    private var lastPushAt = Long.MIN_VALUE
    private var pushedLat = Double.NaN
    private var pushedLon = Double.NaN
    private var pushedCamLat = Double.NaN
    private var pushedCamLon = Double.NaN
    private var pushedZoom = Double.NaN
    private var pushedTilt = Double.NaN
    private var pushedBearing = Double.NaN
    private var pushedTop = Double.NaN
    private var pushedHeading = Double.NaN

    fun setRoute(geometry: RouteGeometry?) = puck.setRoute(geometry)

    /** A new snapshot of the navigation, received at [nowMillis]. */
    fun onFix(nav: NavState, nowMillis: Long) {
        puck.onFix(
            nowMillis, nav.position.lat, nav.position.lon, nav.traveledMeters, nav.speedMps,
            nav.bearingDegrees.toDouble(), nav.routeRevision,
            onRoute = nav.offRouteMeters <= OFF_ROUTE_METERS,
        )
    }

    /**
     * The camera wanted now. [transition] eases there slowly from the camera as it is on the map ([current]), like the
     * start of a trip, a recenter or a 2D/3D switch; otherwise it is a normal follow update.
     */
    fun retarget(target: CameraState, transition: Boolean, current: CameraState) {
        tgtZoom = target.zoom
        tgtTilt = target.tilt
        tgtBearing = target.bearing
        tgtLeft = target.padding.left.toDouble()
        tgtTop = target.padding.top.toDouble()
        tgtRight = target.padding.right.toDouble()
        tgtBottom = target.padding.bottom.toDouble()
        if (transition || !hasTarget) {
            tauMillis = TRANSITION_TAU_MILLIS
            lastFrameAt = Long.MIN_VALUE // the first frame of an ease is the camera as it is: no time has passed
            zoom = current.zoom
            tilt = current.tilt
            camBearing = current.bearing
            left = current.padding.left.toDouble()
            top = current.padding.top.toDouble()
            right = current.padding.right.toDouble()
            bottom = current.padding.bottom.toDouble()
            if (puck.hasPose) {
                offLat = current.center.lat - puck.lat
                offLon = current.center.lon - puck.lon
            } else {
                offLat = 0.0
                offLon = 0.0
            }
        }
        hasTarget = true
        following = true
        pushedCamLat = Double.NaN // the first frame after a retarget always pushes
    }

    /** Stop moving the camera (the user took it, or the overview): the marker goes on gliding. */
    fun release() {
        following = false
        hasTarget = false
        tauMillis = FOLLOW_TAU_MILLIS
    }

    /** Navigation ended: forget the pose so that the next trip starts without a glide. */
    fun reset() {
        release()
        puck.reset()
        lastFrameAt = Long.MIN_VALUE
        lastPushAt = Long.MIN_VALUE
        pushedLat = Double.NaN
        pushedLon = Double.NaN
        pushedCamLat = Double.NaN
        pushedHeading = Double.NaN
    }

    /** True while another frame can change what is drawn (a fix is being predicted, or the camera still eases). */
    fun needsFrames(nowMillis: Long): Boolean {
        if (!puck.hasPose) return false
        if (puck.isPredicting(nowMillis)) return true
        if (!following || !hasTarget) return pushedLat.isNaN()
        return abs(zoom - tgtZoom) > EPS_ZOOM || abs(tilt - tgtTilt) > EPS_DEG || abs(top - tgtTop) > EPS_PX ||
            NavCameraPlanner.angularDistance(camBearing, tgtBearing) > EPS_DEG ||
            abs(offLat) > EPS_OFFSET_DEG || abs(offLon) > EPS_OFFSET_DEG || pushedCamLat.isNaN()
    }

    /**
     * One display frame at [nowMillis]. [minIntervalMillis] caps the push rate (battery saver); returns true when
     * something was pushed to the map.
     */
    fun frame(nowMillis: Long, minIntervalMillis: Long = 0L): Boolean {
        if (!puck.hasPose) return false
        if (lastPushAt != Long.MIN_VALUE && nowMillis - lastPushAt < minIntervalMillis) return false
        val dt = if (lastFrameAt == Long.MIN_VALUE) 0.0 else (nowMillis - lastFrameAt).coerceIn(0L, PuckInterpolator.MAX_STEP_MILLIS).toDouble()
        lastFrameAt = nowMillis
        puck.advance(nowMillis)
        if (following && hasTarget) {
            zoom = PuckInterpolator.approach(zoom, tgtZoom, dt, tauMillis)
            tilt = PuckInterpolator.approach(tilt, tgtTilt, dt, tauMillis)
            camBearing = PuckInterpolator.approachAngle(camBearing, tgtBearing, dt, tauMillis)
            left = PuckInterpolator.approach(left, tgtLeft, dt, tauMillis)
            top = PuckInterpolator.approach(top, tgtTop, dt, tauMillis)
            right = PuckInterpolator.approach(right, tgtRight, dt, tauMillis)
            bottom = PuckInterpolator.approach(bottom, tgtBottom, dt, tauMillis)
            val keep = Math.exp(-dt / tauMillis)
            offLat *= keep
            offLon *= keep
            if (abs(tgtZoom - zoom) < EPS_ZOOM && abs(offLat) < EPS_OFFSET_DEG && abs(offLon) < EPS_OFFSET_DEG) tauMillis = FOLLOW_TAU_MILLIS
        }
        val driven = if (following && hasTarget) camBearing else puck.bearing
        val heading = if (puck.speedMps < COMPASS_BELOW_MPS) deviceHeading()?.toDouble() ?: driven else driven
        val puckMoved = puck.lat != pushedLat || puck.lon != pushedLon
        val headingMoved = pushedHeading.isNaN() || NavCameraPlanner.angularDistance(heading, pushedHeading) > EPS_DEG / 4
        if (puckMoved || headingMoved || pushedHeading.isNaN()) {
            if (headingMoved) engine.setUserHeading(heading.toFloat())
            engine.showUserLocation(LatLon(puck.lat, puck.lon))
            pushedLat = puck.lat
            pushedLon = puck.lon
            pushedHeading = heading
            lastPushAt = nowMillis
            if (!(following && hasTarget)) return true
        } else if (!(following && hasTarget)) {
            return false
        }
        val camLat = puck.lat + offLat
        val camLon = puck.lon + offLon
        val camMoved = pushedCamLat.isNaN() || camLat != pushedCamLat || camLon != pushedCamLon || zoom != pushedZoom ||
            tilt != pushedTilt || camBearing != pushedBearing || top != pushedTop
        if (!camMoved) return puckMoved
        engine.animateTo(
            CameraState(
                LatLon(camLat.coerceIn(-90.0, 90.0), camLon.coerceIn(-180.0, 180.0)),
                zoom.coerceIn(0.0, 24.0), camBearing, tilt.coerceIn(0.0, 85.0),
                CameraPadding(max(0, left.toInt()), max(0, top.toInt()), max(0, right.toInt()), max(0, bottom.toInt())),
            ),
            0,
        )
        pushedCamLat = camLat
        pushedCamLon = camLon
        pushedZoom = zoom
        pushedTilt = tilt
        pushedBearing = camBearing
        pushedTop = top
        lastPushAt = nowMillis
        return true
    }

    companion object {
        /** The normal follow update: zoom, tilt, bearing and padding settle in about a second. */
        const val FOLLOW_TAU_MILLIS = 250.0

        /** Start, recenter and 2D/3D switch: settles in about 1.2 s (3.4 time constants). */
        const val TRANSITION_TAU_MILLIS = 350.0

        /** Beyond this the fix is not on the route (the matched position is a guess): the marker follows the course. */
        const val OFF_ROUTE_METERS = 40.0

        /** Below this speed the compass, not the route, turns the arrow. */
        const val COMPASS_BELOW_MPS = 1.5

        private const val EPS_ZOOM = 0.002
        private const val EPS_DEG = 0.05
        private const val EPS_PX = 0.5
        private const val EPS_OFFSET_DEG = 1e-8
    }
}
