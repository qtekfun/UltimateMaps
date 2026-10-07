package com.qtekfun.mapas.core.regions

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogNamesTest {
    private fun json(region: String = "") = """
        {"schema":1,"catalogVersion":"t",
         "regions":[{"id":"spain_community-of-madrid","name":"Community of Madrid","parent":null,"version":"261004" $region}]}
    """.trimIndent()

    @Test
    fun `a catalog without names shows the English name in every language`() {
        val r = RegionCatalog.parse(json())["spain_community-of-madrid"]!!
        assertTrue(r.names.isEmpty())
        assertEquals("Community of Madrid", r.displayName("es"))
    }

    @Test
    fun `names are optional per language and fall back to the English name`() {
        val r = RegionCatalog.parse(json(""","names":{"ES":"Comunidad de Madrid","ca":" "}"""))["spain_community-of-madrid"]!!
        assertEquals("Comunidad de Madrid", r.displayName("es"))
        assertEquals("Comunidad de Madrid", r.displayName("ES"))
        assertEquals("Community of Madrid", r.displayName("en"))
        assertEquals("Community of Madrid", r.displayName("ca")) // blank entries are dropped
        assertEquals("Community of Madrid", r.name) // `name` stays the English one
    }

    @Test
    fun `names survive a round trip`() {
        val c = RegionCatalog.parse(json(""","names":{"es":"Comunidad de Madrid"}"""))
        assertEquals(mapOf("es" to "Comunidad de Madrid"), RegionCatalog.parse(c.toJson())["spain_community-of-madrid"]!!.names)
    }

    @Test
    fun `World and WorldCoasts are recognized as base files, a real region is not`() {
        fun region(id: String, comapsId: String?, parent: String? = null) = Region(id, id, parent, "1", comapsId = comapsId)
        assertTrue(region("world", "World").isBaseFile)
        assertTrue(region("worldcoasts", "WorldCoasts").isBaseFile)
        assertTrue(region("world", null).isBaseFile) // an older catalog without comapsId
        assertFalse(region("spain", "Spain").isBaseFile)
        assertFalse(region("world", "World", parent = "europe").isBaseFile) // only a top-level entry
    }
}
