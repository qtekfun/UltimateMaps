package com.qtekfun.mapas.fuel

import com.qtekfun.mapas.core.fuel.FuelRepository
import com.qtekfun.mapas.core.fuel.FuelStation
import com.qtekfun.mapas.places.PlacesController
import com.qtekfun.mapas.route.RoutePreviewController
import com.qtekfun.mapas.route.StopResult

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
        val result = route.addStop(station.toPlaceInfo(category()))
        if (result == StopResult.ADDED) card.close() else card.notice = result
    }

    fun save(station: FuelStation) {
        places.toggleSaved(station.toPlaceInfo(category())) { saved -> if (card.station?.id == station.id) card.saved = saved }
    }
}
