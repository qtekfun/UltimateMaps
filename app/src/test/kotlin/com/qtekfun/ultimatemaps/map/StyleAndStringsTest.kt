package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.map.MapTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StyleAndStringsTest {
    // Gradle runs unit tests with the module directory (app/) as working directory.
    private fun asset(name: String) = File("src/main/assets/$name").readText()

    @Test
    fun packagedStylesRenderWithoutLeftoverMarkers() {
        for (theme in MapTheme.entries) {
            val raw = asset(StyleTemplate.assetName(theme))
            assertTrue(raw.contains(StyleTemplate.DIR_MARKER) && raw.contains(StyleTemplate.PMTILES_MARKER))
            val out = StyleTemplate.render(raw, "/data/user/0/x/files/map", "/data/user/0/x/files/maps/es.pmtiles")
            assertFalse(out.contains("@MAPDIR@") || out.contains("@PMTILES@"))
            assertTrue(out.contains("pmtiles://file:///data/user/0/x/files/maps/es.pmtiles"))
            assertTrue(out.contains("file:///data/user/0/x/files/map/sprites/${theme.name.lowercase()}"))
            assertFalse(out.contains("http://") || out.contains("https://protomaps"), "style must not reference the network")
        }
    }

    @Test
    fun styleHasOsmAttribution() {
        assertTrue(asset("map/style-light.json").contains("OpenStreetMap"))
    }

    @Test
    fun templateEscapesPaths() {
        val out = StyleTemplate.render("\"@MAPDIR@\"", "a\"b\\c", "x")
        assertEquals("\"a\\\"b\\\\c\"", out)
    }

    @Test
    fun spanishAndEnglishStringsHaveSameKeys() {
        fun keys(f: String) = Regex("<string name=\"([^\"]+)\"(?![^>]*translatable=\"false\")").findAll(File(f).readText()).map { it.groupValues[1] }.toSet()
        val en = keys("src/main/res/values/strings.xml")
        val es = keys("src/main/res/values-es/strings.xml")
        assertEquals(en, es)
        assertTrue("about_title" in en)
    }
}
