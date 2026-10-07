package com.qtekfun.ultimatemaps.places

import com.qtekfun.ultimatemaps.core.data.SpecialSlot
import com.qtekfun.ultimatemaps.core.geo.LatLon
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickPlacesTest {
    private val fx = PersonalFixture()
    private var here: LatLon? = null
    private val locationRequests = mutableListOf<Unit>()
    private val parkingMarkers = mutableListOf<LatLon?>()

    private fun controller() = QuickPlacesController(
        fx.scope, fx.io, fx.lazyService,
        location = { here },
        requestLocation = { locationRequests += Unit },
        onParking = { parkingMarkers += it },
        parkingName = { "Parked car" },
    )

    @After fun tearDown() = fx.close()

    private val casa = PlaceInfo("Calle Mayor 1", LatLon(40.4154, -3.7074))
    private val office = PlaceInfo("Oficina", LatLon(40.4500, -3.6900))

    @Test fun homeAndWorkAreSetFromAPlaceCardAndSurviveARestart() {
        val q = controller()
        q.setFromCard(SpecialSlot.HOME, casa)
        q.setFromCard(SpecialSlot.WORK, office)
        assertEquals(QuickMessage.WORK_SET, q.state.message)
        assertEquals(casa, q.destination(SpecialSlot.HOME))
        assertEquals(office, q.destination(SpecialSlot.WORK))

        val reopened = controller() // same database, fresh in-memory state
        assertNull(reopened.destination(SpecialSlot.HOME))
        reopened.refresh()
        assertEquals(casa, reopened.destination(SpecialSlot.HOME))
        assertEquals(office, reopened.destination(SpecialSlot.WORK))
    }

    @Test fun settingHomeAgainReplacesIt() {
        val q = controller()
        q.setFromCard(SpecialSlot.HOME, casa)
        q.setFromCard(SpecialSlot.HOME, office)
        assertEquals(office, q.destination(SpecialSlot.HOME))
        assertEquals(QuickMessage.HOME_SET, q.state.message)
    }

    @Test fun theParkedCarCannotBeSetFromACard() {
        val q = controller()
        var failed = false
        try { q.setFromCard(SpecialSlot.PARKING, casa) } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    @Test fun emptySlotsExplainHowToSetThem() {
        val q = controller()
        assertNull(q.destination(SpecialSlot.HOME))
        q.hintUnset(SpecialSlot.HOME)
        assertEquals(QuickMessage.HOME_UNSET, q.state.message)
        q.hintUnset(SpecialSlot.WORK)
        assertEquals(QuickMessage.WORK_UNSET, q.state.message)
        q.dismissMessage()
        assertNull(q.state.message)
    }

    @Test fun parkHereMarksTheCurrentPositionAndDrawsItOnTheMap() {
        here = LatLon(40.4, -3.7)
        val q = controller()
        q.parkHere()
        assertEquals(here, q.state.parking?.point)
        assertEquals("Parked car", q.state.parking?.name)
        assertEquals(QuickMessage.PARKING_SET, q.state.message)
        assertEquals(listOf<LatLon?>(here), parkingMarkers)
        assertTrue(locationRequests.isEmpty())
        assertEquals(here, q.destination(SpecialSlot.PARKING)?.point)
    }

    @Test fun parkHereWithoutAPositionAsksForOneAndCompletesWhenItArrives() {
        val q = controller()
        q.parkHere()
        assertEquals(1, locationRequests.size)
        assertEquals(QuickMessage.WAITING_FOR_LOCATION, q.state.message)
        assertNull(q.state.parking)

        q.onUserLocation() // still no position: keeps waiting
        assertNull(q.state.parking)

        here = LatLon(41.0, 2.0)
        q.onUserLocation()
        assertEquals(here, q.state.parking?.point)
        assertEquals(QuickMessage.PARKING_SET, q.state.message)

        here = LatLon(42.0, 3.0)
        q.onUserLocation() // no longer pending: later fixes do not move the marker
        assertEquals(LatLon(41.0, 2.0), q.state.parking?.point)
    }

    @Test fun markingAgainMovesTheMarkerAndClearingRemovesIt() {
        here = LatLon(40.4, -3.7)
        val q = controller()
        q.parkHere()
        here = LatLon(40.5, -3.8)
        q.parkHere()
        assertEquals(LatLon(40.5, -3.8), q.state.parking?.point)
        q.clearParking()
        assertNull(q.state.parking)
        assertEquals(QuickMessage.PARKING_CLEARED, q.state.message)
        assertNull(parkingMarkers.last())
        val reopened = controller().also { it.refresh() }
        assertNull(reopened.state.parking)
    }

    @Test fun clearingWhileWaitingCancelsThePendingPark() {
        val q = controller()
        q.parkHere()
        q.clearParking()
        here = LatLon(40.4, -3.7)
        q.onUserLocation()
        assertNull(q.state.parking)
    }

    @Test fun refreshRestoresTheParkedMarkerOnTheMap() {
        here = LatLon(40.4, -3.7)
        controller().parkHere()
        parkingMarkers.clear()
        val q = controller()
        q.refresh()
        assertNotNull(q.state.parking)
        assertEquals(listOf<LatLon?>(here), parkingMarkers)
    }
}
