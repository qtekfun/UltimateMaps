package com.qtekfun.ultimatemaps.bikeshare

import com.qtekfun.ultimatemaps.core.bikeshare.BikeAvailability
import com.qtekfun.ultimatemaps.core.bikeshare.BikeShareRepository
import com.qtekfun.ultimatemaps.core.bikeshare.BikeStation
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.nav.AddStopResult
import com.qtekfun.ultimatemaps.route.RoutePreviewController
import com.qtekfun.ultimatemaps.route.StopResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Glue between a tap on a bike-share station, its card, the route and the optional live counts. Main thread only. No
 * Android here, so it is unit-tested on the JVM. It mirrors `ChargerCardController` (Go replaces the destination, Add stop
 * edits the preview or, while navigating, re-plans the trip in progress).
 *
 * Live availability: while the card is open and [live] answers (it answers null while the switch is off, so nothing is asked
 * of the network then), the counts are asked for when the card opens and again every [refreshMillis] (the repository itself
 * never sends more than one request per 30 s per system). Closing the card or opening another one stops it.
 *
 * @param category text used as the name when the station has none (localised by the caller).
 * @param onOpened called when the card opens (the panel expands the sheet and closes the other cards).
 */
class BikeCardController(
    private val repository: BikeShareRepository,
    private val route: RoutePreviewController,
    val card: BikeCardState,
    private val category: () -> String? = { null },
    private val onOpened: () -> Unit = {},
    private val beforeGo: () -> Unit = {},
    /** True while a trip is being navigated: "Add stop" then edits the trip in progress through [navStops]. */
    private val navigating: () -> Boolean = { false },
    /** Adds a stop to the trip in progress (`NavScreenController.addStop`); null where there is no navigation. */
    private val navStops: (suspend (LatLon) -> AddStopResult)? = null,
    private val scope: CoroutineScope? = null,
    /** Live counts of a station, or null (switch off, no feed, failure). Called only while the card is open. */
    private val live: suspend (BikeStation) -> BikeAvailability? = { null },
    private val refreshMillis: Long = 30_000L,
) {
    private var liveJob: Job? = null

    /** The map reported a tap on station [id]: while a route origin is being picked it is that origin, else its card. */
    fun onStationTap(id: String) {
        val station = repository.station(id) ?: return
        if (route.state.pickingOrigin) {
            route.pickOrigin(station.location, station.name.ifBlank { null })
            return
        }
        card.open(station)
        onOpened()
        startLive(station)
    }

    /** The card was closed from outside (another card opened, or the user closed it): stop asking for live counts. */
    fun close() {
        liveJob?.cancel()
        liveJob = null
        card.close()
    }

    /** Stops the live loop without touching the card (the card closes itself through its own "Close"). */
    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
    }

    private fun startLive(station: BikeStation) {
        liveJob?.cancel()
        val launcher = scope ?: return
        liveJob = launcher.launch {
            while (isActive && card.station?.id == station.id) {
                val a = try { live(station) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                if (card.station?.id == station.id) card.availability = a
                delay(refreshMillis)
            }
        }
    }

    /** "Go": a new route from the current location (or the chosen origin) to the station; replaces any stops. */
    fun go(station: BikeStation) {
        beforeGo()
        close()
        route.start(station.toPlaceInfo(category()))
    }

    /** "Add stop": inserts it before the destination. Closes the card when added, otherwise says why not. */
    fun addStop(station: BikeStation) {
        val nav = navStops
        val launcher = scope
        if (navigating() && nav != null && launcher != null) {
            addNavStop(station, nav, launcher)
            return
        }
        val result = route.addStop(station.toPlaceInfo(category()))
        if (result == StopResult.ADDED) close() else card.notice = result
    }

    /** During navigation: re-plan the trip through the station. The card shows "adding" meanwhile and closes on success. */
    private fun addNavStop(station: BikeStation, nav: suspend (LatLon) -> AddStopResult, launcher: CoroutineScope) {
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
            if (result == AddStopResult.ADDED) close() else if (card.station?.id == station.id) card.navNotice = result
        }
    }
}
