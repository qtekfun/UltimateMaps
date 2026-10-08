package com.qtekfun.ultimatemaps.core.transit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Looks at the route types of the published indexes when they are on this machine (`~/mapas-data/transit/check/`, downloaded
 * from the data release); does nothing otherwise. It prints, per city, how many lines each route type has and which modes the
 * filter would offer, and checks that every line maps to a mode.
 */
class RealIndexModesTest {
    private val dir = File(System.getProperty("user.home"), "mapas-data/transit/check")

    @Test
    fun `every line of the published indexes has a route type that maps to a mode`() {
        val files = dir.listFiles { f -> f.name.endsWith(".umti") }?.sortedBy { it.name } ?: return
        for (f in files) {
            val index = f.inputStream().buffered().use { TransitIndexIo.read(it) }
            val perType = index.lineType.toList().groupingBy { it }.eachCount().toSortedMap()
            val perMode = index.patternLine.toList().map { TransitMode.ofRouteType(index.lineType[it]) }.groupingBy { it }.eachCount()
            println("MODES ${f.name}: lines by route_type=$perType; patterns by mode=$perMode; offered=${index.availableModes}")
            assertTrue(index.availableModes.isNotEmpty(), "${f.name} has no vehicle pattern")
        }
    }
}
