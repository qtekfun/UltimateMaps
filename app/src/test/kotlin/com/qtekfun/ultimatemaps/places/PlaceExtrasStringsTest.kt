package com.qtekfun.ultimatemaps.places

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plain JVM checks on the strings and sources of the place extras, coordinate search, sharing and scale bar. */
class PlaceExtrasStringsTest {
    private fun strings(path: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(File(path).readText()).filter { !it.value.contains("translatable=\"false\"") }.associate { it.groupValues[1] to it.groupValues[2] }

    private val en = strings("src/main/res/values/strings_place_extras.xml")
    private val es = strings("src/main/res/values-es/strings_place_extras.xml")

    @Test fun spanishHasEveryEnglishKeyAndNothingElse() {
        assertTrue(en.isNotEmpty())
        assertEquals(en.keys, es.keys)
    }

    @Test fun placeholdersMatchBetweenLanguages() {
        fun placeholders(s: String) = Regex("%\\d\\$[sd]").findAll(s).map { it.value }.toList().sorted()
        for ((k, v) in en) assertEquals(placeholders(v), placeholders(es.getValue(k)), k)
    }

    @Test fun noKeyIsDefinedTwiceInTheOtherStringFiles() {
        val others = File("src/main/res/values").listFiles { f -> f.name.endsWith(".xml") && f.name != "strings_place_extras.xml" }!!
        val seen = others.flatMap { strings(it.path).keys }.toSet()
        assertEquals(emptySet(), en.keys intersect seen)
    }

    /** Positions, queries and phone numbers must never reach the log; the new sources do not use `android.util.Log`. */
    @Test fun theNewSourcesDoNotLog() {
        val files = listOf(
            "places/PlaceExtrasSection.kt", "places/PlaceShare.kt", "nav/EtaShare.kt", "ui/ScaleBar.kt",
        ).map { File("src/main/kotlin/com/qtekfun/ultimatemaps/$it") }
        for (f in files) {
            assertTrue(f.exists(), f.path)
            val text = f.readText()
            assertFalse("android.util.Log" in text || Regex("\\bLog\\.[a-z]\\(").containsMatchIn(text) || "println(" in text, f.path)
        }
    }

    /** The dialer opens with the number filled in (`ACTION_DIAL`): the app never needs the call permission. */
    @Test fun phoneUsesTheDialerWithoutThePermission() {
        val host = File("src/main/kotlin/com/qtekfun/ultimatemaps/search/PanelHost.kt").readText()
        assertTrue("Intent.ACTION_DIAL" in host)
        assertFalse("ACTION_CALL" in host)
        assertFalse("CALL_PHONE" in File("src/main/AndroidManifest.xml").readText())
    }
}
