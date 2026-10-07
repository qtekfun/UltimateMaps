package com.qtekfun.mapas.map

import com.qtekfun.mapas.core.map.MapTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The packaged sprites and glyphs must exist and cover what the style asks for (M0). */
class MapAssetsTest {
    private val dir = File("src/main/assets/map")

    @Test
    fun spritesExistForBothThemesAndDensities() {
        for (t in listOf("light", "dark")) for (f in listOf("$t.json", "$t.png", "$t@2x.json", "$t@2x.png")) {
            assertTrue(File(dir, "sprites/$f").length() > 100, "missing sprite $f")
        }
    }

    @Test
    fun everyFontOfTheStyleHasItsLatinRange() {
        for (theme in MapTheme.entries) {
            val style = File("src/main/assets", StyleTemplate.assetName(theme)).readText()
            assertTrue(style.contains("\"type\":\"symbol\""), "style has no label layers (generate it with lang)")
            val fonts = Regex("\"(Noto Sans (?:Regular|Medium|Italic))\"").findAll(style).map { it.groupValues[1] }.toSet()
            assertTrue(fonts.isNotEmpty())
            for (f in fonts) assertTrue(File(dir, "fonts/$f/0-255.pbf").length() > 100, "missing glyphs for $f")
        }
    }

    @Test
    fun packagedAssetsStaySmall() {
        val total = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue(total < 3L shl 20, "assets grew to $total bytes")
    }
}
