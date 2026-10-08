package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Looks at the published Madrid index (`~/mapas-data/bench/transit-madrid.umti`, from the data release) when it is on this
 * machine; does nothing otherwise. With trains switched off, no journey may board a line whose route type is a train.
 *
 * The index has unrelated lines called "C2": the Cercanias train (route type 2) and the EMT bus "Circular 2" (route type 3).
 * The bus is a legitimate result when trains are off; the filter works per line type, never by name.
 */
class RealMadridTrainFilterTest {
    private val file = File(System.getProperty("user.home"), "mapas-data/bench/transit-madrid.umti")

    @Test
    fun `atocha to alcala with trains off never boards a train line`() {
        if (!file.exists()) return
        val index = file.inputStream().buffered().use { TransitIndexIo.read(it) }
        val c2Types = (0 until index.lineCount).filter { index.lineShortName[it] == "C2" }.map { index.lineType[it] }.toSet()
        assertTrue(c2Types.containsAll(setOf(2, 3)), "the Madrid index mixes a rail C2 and a bus C2: $c2Types")
        val planner = TransitPlanner(index)
        val from = LatLon(40.4066, -3.6892)
        val to = LatLon(40.4821, -3.3637)
        val options = PlanOptions(modes = TransitMode.ALL - TransitMode.TRAIN)
        for (iso in listOf("2026-10-14", "2026-10-17", "2026-10-18")) {
            val epochDay = LocalDate.parse(iso).toEpochDay().toInt()
            val journeys = planner.plan(from, to, epochDay, 17 * 3600, options) +
                planner.planNextDepartures(from, to, epochDay, 17 * 3600, 5, options = options)
            for (j in journeys) for (leg in j.legs) if (leg is Leg.Ride) {
                val type = index.lineType[leg.line]
                assertTrue(TransitMode.ofRouteType(type) != TransitMode.TRAIN, "$iso boarded train line ${index.lineShortName[leg.line]} (type $type)")
            }
        }
    }
}
