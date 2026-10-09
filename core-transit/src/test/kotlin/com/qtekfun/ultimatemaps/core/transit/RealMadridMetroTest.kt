package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Looks at a Madrid index that has the Metro de Madrid feed (`~/mapas-data/bench/transit-madrid.umti`, from the data release,
 * or the file named by the environment variable UM_MADRID_METRO_INDEX); does nothing when the file is absent or when the index
 * was built without a Metro feed (no line of route type 1), which is what happens while the CRTM Metro calendar is expired.
 * With a Metro feed it checks the stations and plans a Metro trip.
 */
class RealMadridMetroTest {
    private val file = File(System.getenv("UM_MADRID_METRO_INDEX") ?: (System.getProperty("user.home") + "/mapas-data/bench/transit-madrid.umti"))

    private fun stop(index: TransitIndex, name: String): LatLon {
        val s = (0 until index.stopCount).first { index.stopName[it].contains(name, ignoreCase = true) }
        return LatLon(index.stopLat[s] / 1e6, index.stopLon[s] / 1e6)
    }

    @Test
    fun `the metro lines and stations are in the index and a metro trip is planned`() {
        if (!file.exists()) return
        val index = file.inputStream().buffered().use { TransitIndexIo.read(it) }
        val metroLines = (0 until index.lineCount).filter { index.lineType[it] == 1 }
        if (metroLines.isEmpty()) return
        assertTrue(metroLines.size >= 12, "Metro de Madrid has lines 1-12 and R: ${metroLines.map { index.lineShortName[it] }}")
        val names = index.stopName.toSet()
        for (n in listOf("Sol", "Nuevos Ministerios", "Aeropuerto T-4")) assertTrue(names.any { it.contains(n, ignoreCase = true) }, "station $n is missing; sample: ${names.take(12)} of ${names.size}")
        println("METRO monforte present=${names.any { it.contains("Monforte", ignoreCase = true) }}")
        val validity = index.validity() ?: error("no validity")
        var day = LocalDate.ofEpochDay(LocalDate.now().toEpochDay().coerceIn(validity.firstDay.toLong(), validity.lastDay.toLong()))
        while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) day = day.minusDays(1)
        assertTrue(day.toEpochDay().toInt() in validity, "no weekday inside the validity window")
        val planner = TransitPlanner(index)
        val options = PlanOptions(modes = setOf(TransitMode.METRO))
        val journeys = planner.plan(stop(index, "Sol"), stop(index, "Aeropuerto T-4"), day.toEpochDay().toInt(), 9 * 3600, options)
        val rides = journeys.flatMap { it.legs }.filterIsInstance<Leg.Ride>()
        assertTrue(rides.isNotEmpty() && rides.all { index.lineType[it.line] == 1 }, "Sol to T4 by metro found no metro ride on $day")
    }
}
