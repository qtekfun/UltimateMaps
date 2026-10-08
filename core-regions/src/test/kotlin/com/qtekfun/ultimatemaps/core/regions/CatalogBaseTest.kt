package com.qtekfun.ultimatemaps.core.regions

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

    @Test
    fun `optional cameras file round trips with a relative url and is validated`() {
        val c = RegionCatalog.parse(json(base = ""","cameras":${asset("speedcams-es.bin", "speedcams-es.bin")}"""), baseUrl = "https://h.example/rel/catalog.json")
        val cams = assertNotNull(c.cameras)
        assertEquals("https://h.example/rel/speedcams-es.bin", cams.url)
        assertEquals(RegionCatalog.parse(c.toJson()).cameras, cams)
        assertNull(RegionCatalog.parse(json()).cameras, "catalogs without it stay valid")
        assertFailsWith<CatalogException> {
            RegionCatalog.parse(json(base = ""","cameras":{"url":"x","size":5,"sha256":"zz","file":"speedcams-es.bin"}"""))
        }
        assertFailsWith<CatalogException> { RegionCatalog.parse(json(base = ""","cameras":${asset("x", "../evil.bin")}""")) }
    }

    @Test
    fun `optional chargers file round trips with a relative url and is validated`() {
        val c = RegionCatalog.parse(json(base = ""","chargers":${asset("chargers-es.bin", "chargers-es.bin")}"""), baseUrl = "https://h.example/rel/catalog.json")
        val file = assertNotNull(c.chargers)
        assertEquals("https://h.example/rel/chargers-es.bin", file.url)
        assertEquals(RegionCatalog.parse(c.toJson()).chargers, file)
        assertNull(c.cameras, "the two optional files are independent")
        assertNull(RegionCatalog.parse(json()).chargers, "catalogs without it stay valid")
        assertFailsWith<CatalogException> {
            RegionCatalog.parse(json(base = ""","chargers":{"url":"x","size":5,"sha256":"zz","file":"chargers-es.bin"}"""))
        }
        assertFailsWith<CatalogException> { RegionCatalog.parse(json(base = ""","chargers":${asset("x", "../evil.bin")}""")) }
    }

    @Test
    fun `optional zbe file round trips with a relative url and is validated`() {
        val c = RegionCatalog.parse(json(base = ""","zbe":${asset("zbe-es.bin", "zbe-es.bin")}"""), baseUrl = "https://h.example/rel/catalog.json")
        val file = assertNotNull(c.zbe)
        assertEquals("https://h.example/rel/zbe-es.bin", file.url)
        assertEquals(RegionCatalog.parse(c.toJson()).zbe, file)
        assertNull(c.chargers, "the optional files are independent")
        assertNull(RegionCatalog.parse(json()).zbe, "catalogs without it stay valid")
        assertFailsWith<CatalogException> {
            RegionCatalog.parse(json(base = ""","zbe":{"url":"x","size":5,"sha256":"zz","file":"zbe-es.bin"}"""))
        }
        assertFailsWith<CatalogException> { RegionCatalog.parse(json(base = ""","zbe":${asset("x", "../evil.bin")}""")) }
    }

    @Test
    fun `optional bikeshare file round trips with a relative url and is validated`() {
        val c = RegionCatalog.parse(json(base = ""","bikeshare":${asset("bikeshare-es.bin", "bikeshare-es.bin")}"""), baseUrl = "https://h.example/rel/catalog.json")
        val file = assertNotNull(c.bikeshare)
        assertEquals("https://h.example/rel/bikeshare-es.bin", file.url)
        assertEquals(RegionCatalog.parse(c.toJson()).bikeshare, file)
        assertNull(c.zbe, "the optional files are independent")
        assertNull(RegionCatalog.parse(json()).bikeshare, "catalogs without it stay valid")
        assertFailsWith<CatalogException> {
            RegionCatalog.parse(json(base = ""","bikeshare":{"url":"x","size":5,"sha256":"zz","file":"bikeshare-es.bin"}"""))
        }
        assertFailsWith<CatalogException> { RegionCatalog.parse(json(base = ""","bikeshare":${asset("x", "../evil.bin")}""")) }
    }

    @Test
    fun `optional routes file round trips with a relative url and is validated`() {
        val c = RegionCatalog.parse(json(base = ""","routes":${asset("routes-es.bin", "routes-es.bin")}"""), baseUrl = "https://h.example/rel/catalog.json")
        val file = assertNotNull(c.routes)
        assertEquals("https://h.example/rel/routes-es.bin", file.url)
        assertEquals(RegionCatalog.parse(c.toJson()).routes, file)
        assertNull(c.chargers, "the optional files are independent")
        assertNull(RegionCatalog.parse(json()).routes, "catalogs without it stay valid")
        assertFailsWith<CatalogException> {
            RegionCatalog.parse(json(base = ""","routes":{"url":"x","size":5,"sha256":"zz","file":"routes-es.bin"}"""))
        }
        assertFailsWith<CatalogException> { RegionCatalog.parse(json(base = ""","routes":${asset("x", "../evil.bin")}""")) }
    }
}
