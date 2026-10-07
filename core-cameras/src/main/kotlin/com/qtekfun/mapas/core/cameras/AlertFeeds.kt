package com.qtekfun.mapas.core.cameras

import com.qtekfun.mapas.core.map.LocationFix
import com.qtekfun.mapas.core.map.LocationSource
import com.qtekfun.mapas.core.nav.NavState
import com.qtekfun.mapas.core.nav.NavStatus
import com.qtekfun.mapas.core.nav.RouteGeometry
import com.qtekfun.mapas.core.routing.RoutePlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Route-based alerts: feeds the [AlertWarner] from the navigation's published state (the user projected on the
 * route, its speed) and the route being followed. Only fixes that are really on the route count (`ON_ROUTE`, not
 * dead-reckoned). It reads the flows of `NavigationController`; it owns no location source and starts nothing.
 */
class NavAlertFeed(
    private val scope: CoroutineScope,
    private val state: Flow<NavState?>,
    private val route: Flow<RoutePlan?>,
    private val warner: AlertWarner,
    private val banner: AlertBannerTracker? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private var jobs: List<Job> = emptyList()

    @Volatile private var geometry: RouteGeometry? = null

    fun start() {
        if (jobs.isNotEmpty()) return
        warner.reset()
        jobs = listOf(
            scope.launch {
                route.collect { plan ->
                    geometry = plan?.geometry?.takeIf { it.size >= 2 }?.let { RouteGeometry(it) }
                    banner?.dismiss() // distances along the old route mean nothing on the new one
                }
            },
            scope.launch {
                state.collect { s ->
                    val g = geometry
                    if (s != null && g != null && s.status == NavStatus.ON_ROUTE && !s.estimated) {
                        banner?.onRouteProgress(s.traveledMeters)
                        warner.onRouteFix(g, s.traveledMeters, s.position.lat, s.position.lon, s.speedMps.toFloat(), clock())
                    }
                }
            },
        )
    }

    override fun close() {
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        geometry = null
        banner?.clear()
    }
}

/**
 * Free-driving alerts: feeds the [AlertWarner] from a [LocationSource] of its own (never the navigation's, whose
 * listener a second `start` would replace). Fixes never leave the device and are not logged. [start] and [stop] are the
 * only places that touch the source; the caller decides when it runs (screen on, a switch on, no navigation).
 */
class FreeDrivingFeed(
    private val location: LocationSource,
    private val warner: AlertWarner,
    private val banner: AlertBannerTracker? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var running = false

    @Synchronized
    fun start() {
        if (running) return
        running = true
        warner.reset()
        location.start { fix: LocationFix ->
            banner?.onFreePosition(fix.point.lat, fix.point.lon)
            warner.onFreeFix(
                fix.point.lat, fix.point.lon, fix.bearingDegrees, fix.speedMps,
                if (fix.timeMillis != 0L) fix.timeMillis else clock(),
            )
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        location.stop()
        banner?.clear()
    }

    val isRunning: Boolean @Synchronized get() = running
}
