package com.qtekfun.mapas.core.cameras

import com.qtekfun.mapas.core.nav.AnnouncementConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** What the visual alert shows: the thing ahead, how far (rounded to 10 m, already decreasing as the driver approaches) and its limit if known. */
data class AlertBannerState(val category: AlertCategory, val distanceMeters: Int, val limitKmh: Int?)

/**
 * The state behind the visual alert (a chip with the camera icon, the distance and the limit). It shows the last thing
 * the [AlertWarner] announced, whether or not the voice is muted or off, and counts the distance down as the driver
 * approaches until it is passed. Pure and clock-free: positions arrive as arguments.
 *
 * Route mode ([onRouteProgress], fed with the metres travelled along the route): the distance falls with the progress and
 * the alert disappears when it reaches zero. Free mode ([onFreePosition]): the straight distance to the target is used and
 * the alert disappears when the driver moves away from it again (more than [PASSED_SLACK_METERS] beyond the closest
 * approach, which absorbs GPS jitter). [clear] when a feed stops. Call the progress method BEFORE giving the same fix to the
 * warner, so an alert raised by that fix gets the right baseline.
 */
class AlertBannerTracker {
    private enum class Mode { NONE, ROUTE, FREE }

    private val shown = MutableStateFlow<AlertBannerState?>(null)
    val state: StateFlow<AlertBannerState?> = shown.asStateFlow()

    private var mode = Mode.NONE
    private var lastAlong = Double.NaN

    private var event: AlertEvent? = null
    private var baseAlong = Double.NaN
    private var closest = Double.MAX_VALUE

    @Synchronized
    fun onRouteProgress(alongMeters: Double) {
        mode = Mode.ROUTE
        lastAlong = alongMeters
        val e = event ?: return
        if (baseAlong.isNaN()) return
        val remaining = e.distanceMeters - (alongMeters - baseAlong)
        if (remaining <= 0) clearLocked() else publish(e, remaining)
    }

    @Synchronized
    fun onFreePosition(lat: Double, lon: Double) {
        mode = Mode.FREE
        val e = event ?: return
        val d = TargetGrid.distanceMeters(lat, lon, e.target.lat, e.target.lon)
        if (d > closest + PASSED_SLACK_METERS) {
            clearLocked()
        } else {
            if (d < closest) closest = d
            publish(e, d)
        }
    }

    /** The warner announced [e]: show it. A newer announcement replaces the one on screen. */
    @Synchronized
    fun onAlert(e: AlertEvent) {
        event = e
        baseAlong = if (mode == Mode.ROUTE) lastAlong else Double.NaN
        closest = e.distanceMeters.toDouble()
        publish(e, e.distanceMeters.toDouble())
    }

    /** A feed stopped: nothing is shown and the mode is forgotten. */
    @Synchronized
    fun clear() {
        mode = Mode.NONE
        lastAlong = Double.NaN
        clearLocked()
    }

    /** Only the alert goes (the route changed); the mode stays. */
    @Synchronized
    fun dismiss() = clearLocked()

    private fun clearLocked() {
        event = null
        baseAlong = Double.NaN
        closest = Double.MAX_VALUE
        shown.value = null
    }

    private fun publish(e: AlertEvent, meters: Double) {
        shown.value = AlertBannerState(e.target.category, (meters / 10.0).roundToInt() * 10, e.limitKmh)
    }

    companion object {
        /** Design value, not measured: how far beyond the closest approach counts as "passed" in free driving. */
        const val PASSED_SLACK_METERS = 40.0
    }
}

/**
 * Decides whether an alert may be SPOKEN. While a maneuver is due within the distance at which its own "near" prompt is
 * spoken (the navigation's [AnnouncementConfig.near] band at the current speed), the driver is about to be told what to
 * do: a camera alert would delay or talk over that prompt, so it is not spoken (the visual alert still shows).
 */
object ManeuverGuard {
    private val near = AnnouncementConfig().near

    fun blocksVoice(nextManeuverMeters: Double?, speedMps: Double): Boolean {
        val m = nextManeuverMeters ?: return false
        return m <= near.metersAt(speedMps.coerceAtLeast(0.0))
    }
}
