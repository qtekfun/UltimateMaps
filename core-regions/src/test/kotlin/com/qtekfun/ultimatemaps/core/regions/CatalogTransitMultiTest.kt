package com.qtekfun.ultimatemaps.core.regions

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The catalog lists several transit indexes (one per city or metro area); older shapes stay readable. */
class CatalogTransitMultiTest {
    private val sha = "c".repeat(64)

    private fun entry(id: String, nameKey: String = "city", bounds: String = "[39.0,-1.0,39.9,-0.2]") =
        """{"id":"$id","$nameKey":"City $id","url":"transit-$id.umti","size":10,"sha256":"$sha","file":"transit-$id.umti",
        "validFrom":"2026-10-07","validTo":"2026-11-05","timezone":"Europe/Madrid","bounds":$bounds,"attribution":["Powered by MITRAMS"]}"""

    private fun json(transit: String) =
        """{"schema":1,"catalogVersion":"t","transit":$transit,"regions":[{"id":"es","name":"Spain","parent":null,"version":"261004"}]}"""

    @Test
    fun `several indexes keep their order and round trip`() {
        val c = RegionCatalog.parse(json("[${entry("madrid")},${entry("valencia")},${entry("bilbao")}]"))
        assertEquals(listOf("madrid", "valencia", "bilbao"), c.transit.map { it.id })
        assertEquals(c.transit, RegionCatalog.parse(c.toJson()).transit)
    }

    @Test
    fun `a single object in the transit block is read as a list of one`() {
        val c = RegionCatalog.parse(json(entry("madrid")))
        assertEquals(listOf("madrid"), c.transit.map { it.id })
    }

    @Test
    fun `name is accepted in place of city and bounds are optional`() {
        val c = RegionCatalog.parse(json("[${entry("sevilla", nameKey = "name")}]"))
        assertEquals("City sevilla", c.transit.single().city)
        val noBox = RegionCatalog.parse(json("[${entry("x").replace(""","bounds":[39.0,-1.0,39.9,-0.2]""", "")}]"))
        assertNull(noBox.transit.single().bounds)
    }

    @Test
    fun `an upside down box is rejected`() {
        assertFailsWith<CatalogException> { RegionCatalog.parse(json("[${entry("x", bounds = "[40.0,-1.0,39.0,-0.2]")}]")) }
    }

    @Test
    fun `the tighter of two boxes has the smaller area`() {
        assert(TransitBounds(40.0, -4.0, 41.0, -3.0).area < TransitBounds(36.0, -9.0, 43.0, 3.0).area)
    }
}
