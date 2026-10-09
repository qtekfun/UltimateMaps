package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Leganes to Monforte de Lemos (Barrio del Pilar) on the published Madrid index (`~/mapas-data/bench/transit-madrid.umti`);
 * does nothing when the file is not on this machine. The index has no metro, so the last part is a bus or a walk.
 *
 * Before the alternatives, only the earliest arrival per number of rides was searched and the egress was limited to 800 m,
 * so "Cercanias to Chamartin, then walk" could never appear. The listing is printed to the test output.
 */
class RealLeganesAlternativesTest {
    private val file = File(System.getProperty("user.home"), "mapas-data/bench/transit-madrid.umti")
    private val day = LocalDate.parse("2026-10-14").toEpochDay().toInt()
    private val monforte = LatLon(40.4768, -3.7122)
    private val leganesCentre = LatLon(40.3277, -3.7613)

    private fun index() = file.inputStream().buffered().use { TransitIndexIo.read(it) }

    private fun check(index: TransitIndex, from: LatLon, label: String) {
        val planner = TransitPlanner(index)
        val js = planner.plan(from, monforte, day, 9 * 3600)
        println("== $label: ${js.size} journeys")
        for (j in js) println("[${j.note}] walk=${j.walkSec / 60} min\n" + j.format(index))
        val vehicle = js.filter { it.rideCount > 0 }
        assertTrue(vehicle.size in 2..6, "bounded set of alternatives: ${vehicle.size}")
        assertNull(vehicle.first().note, "the fastest comes first and needs no label")
        // nothing is dominated in arrival, walking and rides
        for (a in vehicle) for (b in vehicle) if (a !== b) {
            assertTrue(!(a.arriveSec <= b.arriveSec && a.walkSec <= b.walkSec && a.rideCount <= b.rideCount), "dominated:\n${b.format(index)}")
        }
        // the default list respects the 15 minute cap; more walking only as marked extras, at most two
        assertTrue(vehicle.filter { it.note != JourneyNote.WALK_THE_REST }.all { it.walkSec <= 900 })
        val rest = vehicle.filter { it.note == JourneyNote.WALK_THE_REST }
        assertTrue(rest.size in 1..2, "a Cercanias plus walking alternative: ${rest.size}")
        for (r in rest) {
            assertTrue(r.walkSec > 900)
            val last = r.legs.last()
            assertTrue(last is Leg.Walk && last.meters > 1500, "walks the rest")
            assertTrue(r.legs.filterIsInstance<Leg.Ride>().any { index.lineType[it.line] == 2 }, "rides Cercanias first")
            assertTrue(r.rideCount < vehicle.first().rideCount, "and changes less than the fastest")
        }
    }

    @Test
    fun `cercanias plus a longer walk is offered from the centre of Leganes`() {
        if (!file.exists()) return
        check(index(), leganesCentre, "Leganes centre")
    }

    @Test
    fun `cercanias plus a longer walk is offered from the station`() {
        if (!file.exists()) return
        val index = index()
        val station = (0 until index.stopCount).first { index.stopName[it] == "Leganés" }
        check(index, LatLon(index.stopLat[station] / 1e6, index.stopLon[station] / 1e6), "Leganes station")
    }
}
