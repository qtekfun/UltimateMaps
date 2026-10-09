package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The walking pills of the badge row: first, last, middle and only walks, rounding, zero length and ordering. */
class JourneyBadgesTest {
    private val p = LatLon(40.0, -3.0)
    private fun line(name: String, type: Int = 3) = LineInfo(name, name, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), type)

    private fun walk(fromSec: Long, toSec: Long) = ItineraryLeg.Walk(null, null, p, p, 100, fromSec, toSec)
    private fun ride(name: String, fromSec: Long, toSec: Long, type: Int = 3): ItineraryLeg.Ride {
        val a = ItineraryStop("A", p, fromSec, fromSec, null)
        val b = ItineraryStop("B", p, toSec, toSec, null)
        return ItineraryLeg.Ride(line(name, type), "Head", listOf(a, b))
    }

    private fun shape(legs: List<ItineraryLeg>) = Itinerary(legs).badges().map {
        when (it) {
            is JourneyBadge.Walk -> "W${it.minutes}"
            is JourneyBadge.Line -> "L${it.ride.line.shortName}"
        }
    }

    @Test
    fun `walk first, middle and last appear in order around the lines`() {
        val legs = listOf(
            walk(0, 360), ride("480", 400, 1000), ride("C5", 1000, 2000, 2), walk(2000, 2180), ride("C4b", 2300, 3000, 2), walk(3000, 3660),
        )
        assertEquals(listOf("W6", "L480", "LC5", "W3", "LC4b", "W11"), shape(legs))
    }

    @Test
    fun `a trip without walking has only line badges`() {
        assertEquals(listOf("L1", "L2"), shape(listOf(ride("1", 0, 100), ride("2", 100, 200))))
    }

    @Test
    fun `a walk-only trip is just its pill`() {
        assertEquals(listOf("W15"), shape(listOf(walk(0, 900))))
    }

    @Test
    fun `minutes are rounded up and sub-minute walks count as one`() {
        assertEquals(listOf("W1"), shape(listOf(walk(0, 1))))
        assertEquals(listOf("W1"), shape(listOf(walk(0, 60))))
        assertEquals(listOf("W2"), shape(listOf(walk(0, 61))))
        assertEquals(listOf("W2"), shape(listOf(walk(0, 120))))
        assertEquals(listOf("W6"), shape(listOf(walk(0, 301))))
    }

    @Test
    fun `zero-length walks are not shown`() {
        assertEquals(listOf("L1", "L2"), shape(listOf(walk(0, 0), ride("1", 0, 100), walk(100, 100), ride("2", 100, 200), walk(200, 200))))
        assertTrue(Itinerary(listOf(walk(5, 5))).badges().isEmpty())
    }

    @Test
    fun `consecutive walks are merged into one pill`() {
        assertEquals(listOf("W3", "L1"), shape(listOf(walk(0, 100), walk(100, 180), ride("1", 200, 300))))
    }

    @Test
    fun `ride index counts rides only`() {
        val badges = Itinerary(listOf(walk(0, 60), ride("1", 60, 100), walk(100, 160), ride("2", 160, 200))).badges()
        assertEquals(listOf(0, 1), badges.filterIsInstance<JourneyBadge.Line>().map { it.rideIndex })
    }
}
