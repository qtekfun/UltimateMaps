package com.qtekfun.mapas.core.regions

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CatalogBaseTest {
    private val sha = "a".repeat(64)
    private fun asset(u: String, f: String) = """{"url":"$u","size":5,"sha256":"$sha","file":"$f"}"""

    private fun json(base: String = "", region: String = "") = """
        {"schema":1,"catalogVersion":"t" $base,
         "regions":[{"id":"es","name":"Spain","parent":null,"version":"261004" $region}]}
    """.trimIndent()

    @Test
    fun `old catalogs without base or comapsId still parse`() {
        val c = RegionCatalog.parse(json())
        assertNull(c.base)
        assertNull(c["es"]!!.comapsId)
    }

    @Test
    fun `base and comapsId round trip, relative urls resolved`() {
        val text = json(
            base = ""","base":{"version":"261004","world":${asset("w/World.mwm", "World.mwm")},"worldCoasts":${asset("https://x/c", "WorldCoasts.mwm")}}""",
            region = ""","comapsId":"Spain_La Rioja"""",
        )
        val c = RegionCatalog.parse(text, baseUrl = "https://h.example/rel/catalog.json")
        val b = assertNotNull(c.base)
        assertEquals("https://h.example/rel/w/World.mwm", b.world.url)
        assertEquals("https://x/c", b.worldCoasts.url)
        assertEquals(10, b.totalBytes)
        assertEquals("Spain_La Rioja", c["es"]!!.comapsId)
        val again = RegionCatalog.parse(c.toJson())
        assertEquals(c.base, again.base)
        assertEquals("Spain_La Rioja", again["es"]!!.comapsId)
    }

    @Test
    fun `bad base or comapsId are rejected`() {
        assertFailsWith<CatalogException> {
            RegionCatalog.parse(json(base = ""","base":{"version":"1","world":${asset("w", "../World.mwm")},"worldCoasts":${asset("c", "C.mwm")}}"""))
        }
        assertFailsWith<CatalogException> { RegionCatalog.parse(json(base = ""","base":{"version":"1"}""")) }
        assertFailsWith<CatalogException> { RegionCatalog.parse(json(region = ""","comapsId":"../x"""")) }
        assertFailsWith<CatalogException> { RegionCatalog.parse(json(region = ""","comapsId":".hidden"""")) }
    }
}
