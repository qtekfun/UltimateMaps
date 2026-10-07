package com.qtekfun.mapas.nav

import android.content.Context
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.nav.NavTrip
import com.qtekfun.mapas.core.nav.RouteProvider
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.RouteRequest
import com.qtekfun.mapas.search.CoMapsSearchBackend
import com.qtekfun.mapas.search.DirectoryInstalledRegions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File

/**
 * Reroutes of a navigation, computed by the native core (in its own process, see `:native-comaps`) with guidance.
 * [runInterruptible] turns the cancellation of the coroutine (the follower rejoined the route, the navigation
 * ended) into a thread interrupt, which the isolated-core client answers by killing the stuck calculation, so
 * abandoned reroutes never pile up. Any failure is "no route": the follower keeps the old one and retries.
 */
class CoreRouteProvider(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutSec: Int = ROUTE_TIMEOUT_SEC,
) : RouteProvider {
    private val app = context.applicationContext
    private val regions = DirectoryInstalledRegions(File(app.filesDir, "maps-core"))

    override suspend fun route(from: LatLon, bearingDegrees: Float?, via: List<LatLon>, destination: LatLon, trip: NavTrip): RoutePlan? =
        try {
            runInterruptible(io) {
                val maps = regions.coreMaps() ?: return@runInterruptible null
                val engine = CoMapsSearchBackend.prepareCore(app, maps).routingEngine(timeoutSec, withGuidance = true)
                engine.routeDetailed(RouteRequest(from, destination, via, trip.profile, trip.options)).plan
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    companion object {
        /** A reroute that takes longer than this is useless to a moving car; the follower will try again. */
        const val ROUTE_TIMEOUT_SEC = 25
    }
}
