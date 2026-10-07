package com.qtekfun.ultimatemaps.chargers

import com.qtekfun.ultimatemaps.core.chargers.Charger
import com.qtekfun.ultimatemaps.core.chargers.ChargerRepository
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.nav.AddStopResult
import com.qtekfun.ultimatemaps.route.RoutePreviewController
import com.qtekfun.ultimatemaps.route.StopResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Glue between a tap on a charger, its card and the route. Main thread only. No Android here, so it is unit-tested on the
 * JVM. It mirrors `FuelCardController` (Go replaces the destination, Add stop edits the preview or, while navigating,
 * re-plans the trip in progress).
 *
 * @param category text used as the name when the charger has no operator, network or name (localised by the caller).
 * @param onOpened called when the card opens (the panel expands the sheet and closes the other cards).
 */
class ChargerCardController(
    private val repository: ChargerRepository,
    private val route: RoutePreviewController,
    val card: ChargerCardState,
    private val category: () -> String? = { null },
    private val onOpened: () -> Unit = {},
    private val beforeGo: () -> Unit = {},
    /** True while a trip is being navigated: "Add stop" then edits the trip in progress through [navStops]. */
    private val navigating: () -> Boolean = { false },
    /** Adds a stop to the trip in progress (`NavScreenController.addStop`); null where there is no navigation. */
    private val navStops: (suspend (LatLon) -> AddStopResult)? = null,
    private val scope: CoroutineScope? = null,
) {
    /** The map reported a tap on charger [id]: while a route origin is being picked it is that origin, else its card. */
    fun onChargerTap(id: String) {
        val charger = repository.charger(id) ?: return
        if (route.state.pickingOrigin) {
            route.pickOrigin(charger.location, charger.title.ifBlank { null })
            return
        }
        card.open(charger)
        onOpened()
    }

    /** "Go": a new route from the current location (or the chosen origin) to the charger; replaces any stops. */
    fun go(charger: Charger) {
        beforeGo()
        card.close()
        route.start(charger.toPlaceInfo(category()))
    }

    /** "Add stop": inserts it before the destination. Closes the card when added, otherwise says why not. */
    fun addStop(charger: Charger) {
        val nav = navStops
        val launcher = scope
        if (navigating() && nav != null && launcher != null) {
            addNavStop(charger, nav, launcher)
            return
        }
        val result = route.addStop(charger.toPlaceInfo(category()))
        if (result == StopResult.ADDED) card.close() else card.notice = result
    }

    /** During navigation: re-plan the trip through the charger. The card shows "adding" meanwhile and closes on success. */
    private fun addNavStop(charger: Charger, nav: suspend (LatLon) -> AddStopResult, launcher: CoroutineScope) {
        if (card.adding) return
        card.adding = true
        card.navNotice = null
        card.notice = null
        launcher.launch {
            val result = try {
                nav(charger.location)
            } catch (e: kotlinx.coroutines.CancellationException) {
                card.adding = false
                throw e
            } catch (_: Exception) {
                AddStopResult.NO_ROUTE
            }
            card.adding = false
            if (result == AddStopResult.ADDED) card.close() else if (card.charger?.id == charger.id) card.navNotice = result
        }
    }
}
