package com.qtekfun.mapas.core.search

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceLanguageTest {
    private val spanish = Locale.forLanguageTag("es-ES")
    private val english = Locale.forLanguageTag("en-GB")
    private val german = Locale.forLanguageTag("de-DE")

    @Test fun autoFollowsTheAppLanguageAndFallsBackToEnglish() {
        assertEquals("es", PlaceLanguagePref.AUTO.coreLocale(spanish))
        assertEquals("en", PlaceLanguagePref.AUTO.coreLocale(english))
        assertEquals("en", PlaceLanguagePref.AUTO.coreLocale(german))
    }

    @Test fun anExplicitLanguageIgnoresThePhone() {
        assertEquals("es", PlaceLanguagePref.ES.coreLocale(english))
        assertEquals("en", PlaceLanguagePref.EN.coreLocale(spanish))
    }

    @Test fun localNamesKeepTheTextLanguageAndAddTheSuffix() {
        assertEquals("es;local", PlaceLanguagePref.LOCAL.coreLocale(spanish))
        assertEquals("en;local", PlaceLanguagePref.LOCAL.coreLocale(english))
        assertEquals("es", PlaceLanguagePref.LOCAL.textLanguage(spanish))
    }

    @Test fun theCoreLocaleStringCanBeTakenApart() {
        assertEquals("es", PlaceLanguagePref.languageOf("es;local"))
        assertEquals("es", PlaceLanguagePref.languageOf("es"))
        assertEquals("en", PlaceLanguagePref.languageOf(""))
        assertTrue(PlaceLanguagePref.isLocal("es;local"))
        assertFalse(PlaceLanguagePref.isLocal("es"))
        assertFalse(PlaceLanguagePref.isLocal("es;other"))
    }

    @Test fun anUnknownStoredNameIsAuto() {
        assertEquals(PlaceLanguagePref.AUTO, PlaceLanguagePref.fromName(null))
        assertEquals(PlaceLanguagePref.AUTO, PlaceLanguagePref.fromName("klingon"))
        PlaceLanguagePref.entries.forEach { assertEquals(it, PlaceLanguagePref.fromName(it.name)) }
    }
}
