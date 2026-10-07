package com.qtekfun.ultimatemaps.cameras

import com.qtekfun.ultimatemaps.nav.NavEventSink

/**
 * Tells [CameraAlerts] when a navigation starts and ends, so route-based alerts replace free-driving ones. The alerts
 * object is resolved on use (null while no switch has ever been on), so registering this sink does not build it.
 */
class AlertNavSink(private val alerts: () -> CameraAlerts?) : NavEventSink {
    override fun onNavigationStarted(simulated: Boolean) {
        alerts()?.onNavigationStarted()
    }

    override fun onNavigationEnded(arrived: Boolean) {
        alerts()?.onNavigationEnded()
    }
}
