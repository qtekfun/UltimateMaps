package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Leganes (my location) to Gran Via on the published Madrid index (`~/mapas-data/bench/transit-madrid.umti`); does nothing
 * when the file is not on this machine. With buses switched off, a journey "walk to the Cercanias station, train, walk" exists
 * and must be found even though the nearest stops of the origin are all bus stops: with the default walking cap of the app
 * (60 min) and with "no limit" (0).
 */
class RealLeganesGranViaFilterTest {
    private val file = File(System.getProperty("user.home"), "mapas-data/bench/transit-madrid.umti")
    private val day = LocalDate.parse("2026-10-14").toEpochDay().toInt()
    private val from = LatLon(40.3277, -3.7613)
    private val to = LatLon(40.4200, -3.7050)

    private fun check(cap: Int) {
        val index = file.inputStream().buffered().use { TransitIndexIo.read(it) }
        val planner = TransitPlanner(index)
        val all = planner.plan(from, to, day, 17 * 3600, PlanOptions(maxTotalWalkSec = cap))
        println("== cap $cap, all modes: ${all.size}")
        for (j in all) println("[${j.note}] walk=${j.walkSec / 60} min\n" + j.format(index))
        val noBus = planner.plan(from, to, day, 17 * 3600, PlanOptions(modes = TransitMode.ALL - TransitMode.BUS, maxTotalWalkSec = cap))
        println("== cap $cap, no bus: ${noBus.size}")
        for (j in noBus) println("[${j.note}] walk=${j.walkSec / 60} min\n" + j.format(index))
        val vehicle = noBus.filter { it.rideCount > 0 }
        assertTrue(vehicle.isNotEmpty(), "a train journey exists without buses (cap $cap)")
        for (j in vehicle) for (leg in j.legs) if (leg is Leg.Ride) {
            assertTrue(TransitMode.ofRouteType(index.lineType[leg.line]) != TransitMode.BUS, "boarded a bus")
        }
        assertTrue(vehicle.any { it.legs.filterIsInstance<Leg.Ride>().any { r -> index.lineType[r.line] == 2 } }, "rides Cercanias")
        assertTrue(all.any { it.rideCount > 0 })
        assertTrue(all.none { it.note == JourneyNote.LONG_WALK_TO_STATION }, "all modes: no widening needed")
    }

    @Test
    fun `leganes to gran via without buses finds the train with the 60 minute cap`() {
        if (!file.exists()) return
        check(3600)
    }

    @Test
    fun `leganes to gran via without buses finds the train with no walking limit`() {
        if (!file.exists()) return
        check(0)
    }
}
