package com.qtekfun.ultimatemaps.places

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plain JVM checks on the files of the personal features (Gradle runs the tests from the `app/` directory). */
class PersonalStringsAndPrivacyTest {
    private fun strings(path: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(File(path).readText()).associate { it.groupValues[1] to it.groupValues[2] }

    private val en = strings("src/main/res/values/strings_personal.xml")
    private val es = strings("src/main/res/values-es/strings_personal.xml")

    @Test fun spanishHasEveryEnglishKeyAndNothingElse() {
        assertTrue(en.isNotEmpty())
        assertEquals(en.keys, es.keys)
    }

    @Test fun placeholdersMatchBetweenLanguages() {
        fun placeholders(s: String) = Regex("%\\d\\$[sd]").findAll(s).map { it.value }.toList().sorted()
        for ((k, v) in en) assertEquals(placeholders(v), placeholders(es.getValue(k)), k)
    }

    @Test fun noKeyIsDefinedTwiceInTheOtherStringFiles() {
        val others = File("src/main/res/values").listFiles { f -> f.name.endsWith(".xml") && f.name != "strings_personal.xml" }!!
        val seen = others.flatMap { strings(it.path).keys }.toSet()
        assertEquals(emptySet(), en.keys intersect seen)
    }

    /** Positions and queries must never reach the log: the personal-feature sources do not use `android.util.Log`. */
    @Test fun thePersonalSourcesDoNotLog() {
        val files = listOf(
            "places/QuickPlaces.kt", "places/QuickPlacesRow.kt", "places/ListStyle.kt", "places/ListStyleEditor.kt",
            "search/SearchHistory.kt", "settings/HistorySettingsSection.kt",
            "emergency/EmergencyModel.kt", "emergency/EmergencyScreen.kt", "emergency/EmergencyActivity.kt",
            "shortcuts/AppShortcuts.kt",
        ).map { File("src/main/kotlin/com/qtekfun/ultimatemaps/$it") }
        for (f in files) {
            assertTrue(f.exists(), f.path)
            val text = f.readText()
            assertFalse("android.util.Log" in text || Regex("\\bLog\\.[a-z]\\(").containsMatchIn(text) || "println(" in text, f.path)
        }
    }

    @Test fun theManifestAddsNoPhoneOrSmsPermission() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (p in listOf("CALL_PHONE", "SEND_SMS", "READ_SMS", "READ_CONTACTS")) assertFalse(p in manifest, p)
    }
}
