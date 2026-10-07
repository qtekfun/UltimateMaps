package com.qtekfun.ultimatemaps.fuel

import com.qtekfun.ultimatemaps.core.fuel.FuelRepository
import com.qtekfun.ultimatemaps.core.fuel.FuelStation
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.nav.AddStopResult
import com.qtekfun.ultimatemaps.places.PlacesController
import com.qtekfun.ultimatemaps.route.RoutePreviewController
import com.qtekfun.ultimatemaps.route.StopResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Glue between a tap on a station, its card and the route / saved places. Main thread only. No Android here, so it
 * is unit-tested on the JVM.
 *
 * @param category text stored as the category when the station is saved (localised by the caller).
 * @param onOpened called when the card opens (the panel expands the sheet).
 */
class FuelCardController(
    private val repository: FuelRepository,
    private val route: RoutePreviewController,
    private val places: PlacesController,
    val card: FuelCardState,
    private val category: () -> String? = { null },
    private val onOpened: () -> Unit = {},
    private val beforeGo: () -> Unit = {},
    /** True while a trip is being navigated: "Add stop" then edits the trip in progress through [navStops]. */
    private val navigating: () -> Boolean = { false },
    /** Adds a stop to the trip in progress (`NavScreenController.addStop`); null where there is no navigation. */
    private val navStops: (suspend (LatLon) -> AddStopResult)? = null,
    private val scope: CoroutineScope? = null,
) {
    /** The map reported a tap on station [id]: while a route origin is being picked it is that origin, else its card. */
    fun onStationTap(id: String) {
        val station = repository.station(id) ?: return
        if (route.state.pickingOrigin) {
            route.pickOrigin(station.location, station.brand.ifBlank { null })
            return
        }
        card.open(station)
        places.isSaved(station.toPlaceInfo(category())) { saved -> if (card.station?.id == id) card.saved = saved }
        onOpened()
    }

    /** "Go": a new route from the current location (or the chosen origin) to the station; replaces any stops. */
    fun go(station: FuelStation) {
        beforeGo()
        card.close()
        route.start(station.toPlaceInfo(category()))
    }

    /** "Add stop": inserts it before the destination. Closes the card when added, otherwise says why not. */
    fun addStop(station: FuelStation) {
        val nav = navStops
        val launcher = scope
        if (navigating() && nav != null && launcher != null) {
            addNavStop(station, nav, launcher)
            return
        }
        val result = route.addStop(station.toPlaceInfo(category()))
        if (result == StopResult.ADDED) card.close() else card.notice = result
    }

    /** During navigation: re-plan the trip through the station. The card shows "adding" meanwhile and closes on success. */
    private fun addNavStop(station: FuelStation, nav: suspend (LatLon) -> AddStopResult, launcher: CoroutineScope) {
        if (card.adding) return
        card.adding = true
        card.navNotice = null
        card.notice = null
        launcher.launch {
            val result = try {
                nav(station.location)
            } catch (e: kotlinx.coroutines.CancellationException) {
                card.adding = false
                throw e
            } catch (_: Exception) {
                AddStopResult.NO_ROUTE
            }
            card.adding = false
            if (result == AddStopResult.ADDED) card.close() else if (card.station?.id == station.id) card.navNotice = result
        }
    }

    fun save(station: FuelStation) {
        places.toggleSaved(station.toPlaceInfo(category())) { saved -> if (card.station?.id == station.id) card.saved = saved }
    }
}
