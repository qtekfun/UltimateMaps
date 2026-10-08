package com.qtekfun.ultimatemaps.trails

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.routes.RouteRepository
import com.qtekfun.ultimatemaps.core.routes.Trail
import com.qtekfun.ultimatemaps.route.RoutePreviewController

/**
 * Glue between a tap on a route line, its card and the route to its start. Main thread only. No Android here, so it is
 * unit-tested on the JVM. While a route origin is being picked the tap is that origin, as for any other tap on the map.
 *
 * @param category text used as the name when the route has neither name nor ref (localised by the caller).
 * @param onOpened called when the card opens (the panel expands the sheet and closes the other cards).
 */
class TrailCardController(
    private val repository: RouteRepository,
    private val route: RoutePreviewController,
    val card: TrailCardState,
    private val category: () -> String = { "" },
    private val onOpened: () -> Unit = {},
    private val beforeGo: () -> Unit = {},
    /** True while a trip is being navigated: route taps are ignored then (the navigation screen owns the map). */
    private val navigating: () -> Boolean = { false },
) {
    /** The map reported a tap on route [id] at [point]. */
    fun onTrailTap(id: Int, point: LatLon) {
        if (navigating()) return
        if (route.state.pickingOrigin) {
            route.pickOrigin(point, null)
            return
        }
        val trail = repository.trail(id) ?: return
        card.open(trail)
        onOpened()
    }

    /** "Route to the start": a new route from the current location (or the chosen origin) to the route's first point. */
    fun goToStart(trail: Trail) {
        val place = TrailCardText.startPlace(trail, category()) ?: return
        beforeGo()
        card.close()
        route.start(place)
    }
}
