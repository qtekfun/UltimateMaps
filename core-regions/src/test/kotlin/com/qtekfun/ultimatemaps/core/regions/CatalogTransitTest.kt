package com.qtekfun.ultimatemaps.core.regions

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CatalogTransitTest {
    private val sha = "b".repeat(64)

    private fun entry(
        id: String = "madrid", validFrom: String = "2026-10-07", validTo: String = "2026-11-05",
        attribution: String = """["Powered by CRTM"]""", timezone: String = "Europe/Madrid", url: String = "transit-madrid.umti",
    ) = """{"id":"$id","city":"Madrid","url":"$url","size":99,"sha256":"$sha","file":"transit-madrid.umti",
        "validFrom":"$validFrom","validTo":"$validTo","timezone":"$timezone","bounds":[39.8,-4.6,41.2,-3.0],"attribution":$attribution}"""

    private fun json(vararg entries: String, block: Boolean = true) = """
        {"schema":1,"catalogVersion":"t" ${if (block) ""","transit":[${entries.joinToString(",")}]""" else ""},
         "regions":[{"id":"es","name":"Spain","parent":null,"version":"261004"}]}
    """.trimIndent()

    @Test
    fun `catalogs without a transit block stay valid`() {
        assertTrue(RegionCatalog.parse(json(block = false)).transit.isEmpty())
    }

    @Test
    fun `transit block round trips and resolves the relative url`() {
        val c = RegionCatalog.parse(json(entry()), baseUrl = "https://h.example/rel/catalog.json")
        val t = c.transit.single()
        assertEquals("https://h.example/rel/transit-madrid.umti", t.asset.url)
        assertEquals(99, t.asset.sizeBytes)
        assertEquals(sha, t.asset.sha256)
        assertEquals("2026-11-05", t.validTo)
        assertEquals("Europe/Madrid", t.timezone)
        assertEquals(listOf("Powered by CRTM"), t.attribution)
        assertTrue(assertNotNull(t.bounds).contains(40.4, -3.7))
        assertEquals(c.transit, RegionCatalog.parse(c.toJson()).transit)
    }

    @Test
    fun `invalid transit entries are rejected`() {
        assertFailsWith<CatalogException> { RegionCatalog.parse(json(entry(attribution = "[]"))) } // attribution is mandatory
        assertFailsWith<CatalogException> { RegionCatalog.parse(json(entry(validFrom = "2026-12-01"))) } // ends before it starts
        assertFailsWith<CatalogException> { RegionCatalog.parse(json(entry(validTo = "soon"))) }
        assertFailsWith<CatalogException> { RegionCatalog.parse(json(entry(timezone = "Mars/Base"))) }
        assertFailsWith<CatalogException> { RegionCatalog.parse(json(entry(), entry())) } // duplicate id
        assertFailsWith<CatalogException> { RegionCatalog.parse(json(entry(id = "bad id"))) }
    }
}
