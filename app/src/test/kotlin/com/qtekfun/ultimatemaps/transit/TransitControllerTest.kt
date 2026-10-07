package com.qtekfun.ultimatemaps.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.TransitMapLeg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transit mode logic with the planner on a made-up feed. Everything runs on the caller (Unconfined), the clock is
 * injected: no real time, no waiting for other threads.
 */
class TransitControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val drawn = mutableListOf<List<TransitMapLeg>>()
    private var lookup: TransitLookup = TransitLookup.Ready(TransitTestSupport.service(), "Testville")
    private var clock = TransitTestSupport.clockAt("2026-10-14T07:55:00")

    private fun controller() = TransitController(scope, Dispatchers.Unconfined, { _, _ -> lookup }, object : TransitClock {
        override fun now() = clock.now()
        override fun zone() = clock.zone()
    }, showItinerary = { drawn += it })

    @After fun tearDown() = scope.cancel()

    @Test
    fun plansFromTheInjectedClockAndDrawsTheFirstItinerary() {
        val c = controller()
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        assertEquals(TransitPhase.DONE, c.state.phase)
        val it = assertNotNull(c.state.current)
        assertEquals("M1", it.rides.single().line.shortName)
        assertEquals("08:00", TransitFormat.time(it.rides.single().departAt, c.state.zone)) // 07:55 now: the 08:00 train
        assertEquals("Testville", c.state.city)
        assertEquals(listOf("Powered by Test Agency (https://agency.example/). Processed data."), c.state.attribution)
        assertEquals(LocalDate.parse("2026-10-31"), c.state.validTo)
        // walking legs dashed and grey, the ride solid in the line colour
        val legs = drawn.last()
        assertTrue(legs.first().dashed && legs.last().dashed)
        val ride = legs.single { !it.dashed }
        assertEquals(0xFF0000FF.toInt(), ride.color)
        assertEquals(3, ride.points.size) // stop-to-stop straight lines: A, B, C
    }

    @Test
    fun aLaterDepartureReplansAndChangesTheTimes() {
        val c = controller()
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        c.departAt(LocalDateTime.parse("2026-10-14T09:30:00"))
        assertEquals(TransitPhase.DONE, c.state.phase)
        assertTrue(c.state.current!!.departAt >= LocalDateTime.parse("2026-10-14T09:30:00").atZone(TransitTestSupport.zone).toEpochSecond())
        c.departNow()
        assertNull(c.state.departure)
        assertEquals("08:00", TransitFormat.time(c.state.current!!.rides.single().departAt, c.state.zone))
    }

    @Test
    fun departLaterStartsFromTheClockAndStepsByFifteenMinutesAndOneDay() {
        val c = controller()
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        c.departLater()
        assertEquals(LocalDateTime.parse("2026-10-14T07:55:00"), c.state.departure)
        c.shiftMinutes(15)
        assertEquals(LocalDateTime.parse("2026-10-14T08:10:00"), c.state.departure)
        c.shiftDays(1)
        assertEquals(LocalDateTime.parse("2026-10-15T08:10:00"), c.state.departure)
    }

    @Test
    fun anExpiredFeedRefusesToPlanAndNamesTheLastValidDay() {
        val c = controller()
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        c.departAt(LocalDateTime.parse("2026-11-03T08:00:00"))
        assertEquals(TransitPhase.ERROR, c.state.phase)
        assertEquals(TransitError.EXPIRED, c.state.error)
        assertEquals(LocalDate.parse("2026-10-31"), c.state.errorDate)
        assertTrue(c.state.itineraries.isEmpty())
        assertTrue(drawn.last().isEmpty(), "nothing stays on the map")
        // the clock itself past the end: also refused
        clock = TransitTestSupport.clockAt("2026-12-01T08:00:00")
        c.departNow()
        assertEquals(TransitError.EXPIRED, c.state.error)
    }

    @Test
    fun beforeTheDataStartsIsRefusedToo() {
        val c = controller()
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        c.departAt(LocalDateTime.parse("2026-09-01T08:00:00"))
        assertEquals(TransitError.NOT_YET_VALID, c.state.error)
        assertEquals(LocalDate.parse("2026-10-01"), c.state.errorDate)
    }

    @Test
    fun explainsMissingDataWithoutPlanning() {
        val c = controller()
        lookup = TransitLookup.NoData
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        assertEquals(TransitError.NO_DATA, c.state.error)
        lookup = TransitLookup.NotDownloaded("Madrid")
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        assertEquals(TransitError.NOT_DOWNLOADED, c.state.error)
        assertEquals("Madrid", c.state.errorCity)
        lookup = TransitLookup.OutsideCoverage
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        assertEquals(TransitError.OUTSIDE_COVERAGE, c.state.error)
        lookup = TransitLookup.Unreadable
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        assertEquals(TransitError.INTERNAL, c.state.error)
    }

    @Test
    fun noConnectionAndMissingOrigin() {
        val c = controller()
        c.plan(TransitTestSupport.nearA, LatLon(41.5, -3.0))
        assertEquals(TransitError.NO_ROUTE, c.state.error)
        c.plan(null, TransitTestSupport.nearC)
        assertEquals(TransitPhase.NEEDS_ORIGIN, c.state.phase)
    }

    @Test
    fun selectingAnOptionOpensTheCardAndRedrawsAndBackReturns() {
        val c = controller()
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        assertTrue(c.state.itineraries.size >= 2, "the next departures are offered as further options")
        val before = drawn.size
        c.select(1)
        assertEquals(1, c.state.selected)
        assertTrue(c.state.detail)
        assertEquals(before + 1, drawn.size)
        c.select(99) // ignored
        assertEquals(1, c.state.selected)
        c.back()
        assertTrue(!c.state.detail)
    }

    @Test
    fun clearForgetsTheTripAndTheMap() {
        val c = controller()
        c.plan(TransitTestSupport.nearA, TransitTestSupport.nearC)
        c.clear()
        assertEquals(TransitPhase.IDLE, c.state.phase)
        assertTrue(c.state.itineraries.isEmpty())
        assertTrue(drawn.last().isEmpty())
        c.departAt(LocalDateTime.parse("2026-10-14T09:00:00")) // no trip any more: nothing happens
        assertEquals(TransitPhase.IDLE, c.state.phase)
    }
}
