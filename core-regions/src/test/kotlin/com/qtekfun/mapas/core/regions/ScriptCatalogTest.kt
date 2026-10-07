package com.qtekfun.mapas.core.regions

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The catalog written by `scripts/gen-region-catalog.py` (real hierarchy of countries.txt) parses with schema 1. */
class ScriptCatalogTest {
    @Test
    fun `output of gen-region-catalog parses and keeps the hierarchy`() {
        val text = javaClass.getResource("/catalog-from-script.json")!!.readText()
        val c = RegionCatalog.parse(text)
        val madrid = assertNotNull(c["spain_community-of-madrid"])
        assertEquals("spain", madrid.parentId)
        assertTrue(madrid.isDownloadable)
        assertEquals(30, madrid.totalBytes)
        assertTrue(c.children("spain").size > 10)
        assertEquals(listOf("spain_community-of-madrid"), c.downloadableUnder("spain").map { it.id })
        assertTrue(c.regions.any { it.parentId == null && !it.isDownloadable })
    }
}
