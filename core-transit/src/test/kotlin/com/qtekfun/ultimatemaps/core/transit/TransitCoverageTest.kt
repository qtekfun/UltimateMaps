package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.regions.TransitBounds
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Which index serves a trip when the catalog lists several (boxes of the real configs, points from memory). */
class TransitCoverageTest {
    private val madrid = CoverageEntry("madrid", "Madrid", TransitBounds(39.8, -4.6, 41.2, -3.0))
    private val barcelona = CoverageEntry("barcelona", "Barcelona and Catalonia", TransitBounds(40.5, 0.15, 42.9, 3.35))
    private val valencia = CoverageEntry("valencia", "Valencia", TransitBounds(39.0, -1.0, 39.9, -0.2))
    private val girona = CoverageEntry("girona", "Girona", TransitBounds(41.9, 2.7, 42.1, 2.9))
    private val noBox = CoverageEntry("nobox", "No box", null)
    private val all = listOf(noBox, madrid, barcelona, valencia)

    private val sol = LatLon(40.4168, -3.7038)
    private val plazaCatalunya = LatLon(41.3870, 2.1701)
    private val ruzafa = LatLon(39.4600, -0.3770)
    private val sagrada = LatLon(41.4036, 2.1744)

    @Test
    fun `a trip inside one box picks that index whatever the list order`() {
        assertEquals("barcelona", TransitCoverage.covering(all, plazaCatalunya, sagrada)?.id)
        assertEquals("barcelona", TransitCoverage.covering(all.reversed(), plazaCatalunya, sagrada)?.id)
        assertEquals("madrid", TransitCoverage.covering(all, sol, sol)?.id)
    }

    @Test
    fun `an entry without a box never covers anything`() {
        assertNull(TransitCoverage.covering(listOf(noBox), sol, sol))
        assertNull(TransitCoverage.serving(listOf(noBox), sol))
    }

    @Test
    fun `overlapping boxes choose the tighter one and ties go to the smaller id`() {
        val both = listOf(barcelona, girona)
        assertEquals("girona", TransitCoverage.covering(both, LatLon(41.98, 2.82), LatLon(41.97, 2.80))?.id)
        // from Girona to Barcelona only the large box holds both ends
        assertEquals("barcelona", TransitCoverage.covering(both, LatLon(41.98, 2.82), plazaCatalunya)?.id)
        val twin = CoverageEntry("a-twin", "Twin", barcelona.bounds)
        assertEquals("a-twin", TransitCoverage.covering(listOf(barcelona, twin), plazaCatalunya, sagrada)?.id)
    }

    @Test
    fun `a trip between two indexes is reported as across`() {
        assertNull(TransitCoverage.covering(all, sol, plazaCatalunya))
        val (a, b) = assertNotNull(TransitCoverage.across(all, sol, plazaCatalunya))
        assertEquals("madrid" to "barcelona", a.id to b.id)
        val (c, d) = assertNotNull(TransitCoverage.across(all, ruzafa, plazaCatalunya))
        assertEquals("valencia" to "barcelona", c.id to d.id)
    }

    @Test
    fun `across is null when one index serves both or an end has no data`() {
        assertNull(TransitCoverage.across(all, plazaCatalunya, sagrada))
        assertNull(TransitCoverage.across(all, sol, LatLon(37.39, -5.99))) // Sevilla: not listed
        assertNull(TransitCoverage.across(emptyList(), sol, plazaCatalunya))
    }
}
