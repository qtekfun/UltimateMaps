package com.qtekfun.mapas.places

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.mapas.core.data.SpecialPlace
import com.qtekfun.mapas.core.data.SpecialSlot
import com.qtekfun.mapas.core.geo.LatLon
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-line feedback of the quick places; mapped to strings by the UI. */
enum class QuickMessage { HOME_SET, WORK_SET, PARKING_SET, PARKING_CLEARED, WAITING_FOR_LOCATION, HOME_UNSET, WORK_UNSET }

/** Observable state of the quick places. Written from the main thread only. */
class QuickPlacesState {
    var home by mutableStateOf<SpecialPlace?>(null)
    var work by mutableStateOf<SpecialPlace?>(null)
    var parking by mutableStateOf<SpecialPlace?>(null)
    var message by mutableStateOf<QuickMessage?>(null)
}

/**
 * Home, Work and "I parked here": one place each, stored on the device only (never in the lists, never sent).
 * Home and Work are set from a place card; the parked car is the user's current position.
 *
 * @param location the last known user position (memory only); null when unknown.
 * @param requestLocation asks for the permission and a fix; [onUserLocation] completes a pending "park here".
 * @param onParking tells the map where to draw the parked-car marker (null removes it).
 * @param parkingName localized name of the parked car, stored with the place.
 */
class QuickPlacesController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val service: Lazy<PlacesService>,
    private val location: () -> LatLon?,
    private val requestLocation: () -> Unit,
    private val onParking: (LatLon?) -> Unit,
    private val parkingName: () -> String,
) {
    val state = QuickPlacesState()
    private var parkPending = false

    /** Reloads the three places from the database (call on start). */
    fun refresh() {
        scope.launch {
            val loaded = withContext(io) {
                val s = service.value
                Triple(s.special(SpecialSlot.HOME), s.special(SpecialSlot.WORK), s.special(SpecialSlot.PARKING))
            }
            state.home = loaded.first
            state.work = loaded.second
            state.parking = loaded.third
            onParking(loaded.third?.point)
        }
    }

    fun place(slot: SpecialSlot): SpecialPlace? = when (slot) {
        SpecialSlot.HOME -> state.home
        SpecialSlot.WORK -> state.work
        SpecialSlot.PARKING -> state.parking
    }

    /** What a tap on a chip opens as a route destination, or null when the slot is empty. */
    fun destination(slot: SpecialSlot): PlaceInfo? = place(slot)?.let { PlaceInfo(it.name, it.point) }

    fun dismissMessage() {
        state.message = null
    }

    /** Stores [info] as Home or Work (from its place card). The parked car is set with [parkHere]. */
    fun setFromCard(slot: SpecialSlot, info: PlaceInfo) {
        require(slot != SpecialSlot.PARKING) { "the parked car is set with parkHere()" }
        scope.launch {
            val stored = withContext(io) { service.value.setSpecial(slot, info.name, info.point); service.value.special(slot) }
            if (slot == SpecialSlot.HOME) state.home = stored else state.work = stored
            state.message = if (slot == SpecialSlot.HOME) QuickMessage.HOME_SET else QuickMessage.WORK_SET
        }
    }

    /** Tapping an empty Home or Work chip explains how to set it. */
    fun hintUnset(slot: SpecialSlot) {
        state.message = if (slot == SpecialSlot.HOME) QuickMessage.HOME_UNSET else QuickMessage.WORK_UNSET
    }

    /** Marks the current position as where the car is parked; without a position it asks for one and waits. */
    fun parkHere() {
        val here = location()
        if (here == null) {
            parkPending = true
            state.message = QuickMessage.WAITING_FOR_LOCATION
            requestLocation()
            return
        }
        park(here)
    }

    /** A new user position arrived: completes a pending [parkHere]. */
    fun onUserLocation() {
        if (!parkPending) return
        val here = location() ?: return
        parkPending = false
        park(here)
    }

    private fun park(here: LatLon) {
        parkPending = false
        val name = parkingName()
        scope.launch {
            val stored = withContext(io) { service.value.setSpecial(SpecialSlot.PARKING, name, here); service.value.special(SpecialSlot.PARKING) }
            state.parking = stored
            state.message = QuickMessage.PARKING_SET
            onParking(stored?.point)
        }
    }

    fun clearParking() {
        parkPending = false
        scope.launch {
            withContext(io) { service.value.clearSpecial(SpecialSlot.PARKING) }
            state.parking = null
            state.message = QuickMessage.PARKING_CLEARED
            onParking(null)
        }
    }
}
