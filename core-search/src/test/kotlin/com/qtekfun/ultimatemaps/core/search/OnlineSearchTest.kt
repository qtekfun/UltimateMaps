package com.qtekfun.ultimatemaps.core.search

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlineSearchTest {
    @Test fun `the bias is rounded to a hundredth of a degree`() {
        val p = OnlineSearch.roundBias(LatLon(40.416775, -3.703790))
        assertEquals(40.42, p.lat, 1e-9)
        assertEquals(-3.70, p.lon, 1e-9)
        val q = OnlineSearch.roundBias(LatLon(-33.8688197, 151.2092955))
        assertEquals(-33.87, q.lat, 1e-9)
        assertEquals(151.21, q.lon, 1e-9)
    }

    @Test fun `a blank or unusable address means the default`() {
        assertEquals(OnlineSearch.DEFAULT_PHOTON_URL, OnlineSearch.effectiveUrl(""))
        assertEquals(OnlineSearch.DEFAULT_PHOTON_URL, OnlineSearch.effectiveUrl("http://photon.example.org"))
        assertEquals(OnlineSearch.DEFAULT_PHOTON_URL, OnlineSearch.effectiveUrl("not a url"))
        assertEquals(OnlineSearch.DEFAULT_PHOTON_URL, OnlineSearch.effectiveUrl("https://user:pw@photon.example.org"))
        assertEquals("https://photon.example.org", OnlineSearch.effectiveUrl(" https://photon.example.org/ "))
    }

    @Test fun `the host to allow follows the configured address`() {
        assertEquals("photon.komoot.io", OnlineSearch.host(""))
        assertEquals("photon.example.org", OnlineSearch.host("https://Photon.Example.org/api"))
    }

    @Test fun `validity needs https and a host`() {
        assertTrue(OnlineSearch.isValidUrl("https://photon.example.org"))
        assertFalse(OnlineSearch.isValidUrl("http://photon.example.org"))
        assertFalse(OnlineSearch.isValidUrl("https://"))
        assertFalse(OnlineSearch.isValidUrl(""))
    }

    @Test fun `only the languages the public instance knows are sent`() {
        val es = Locale.forLanguageTag("es")
        assertNull(OnlineSearch.photonLanguage(PlaceLanguagePref.ES, es))
        assertNull(OnlineSearch.photonLanguage(PlaceLanguagePref.LOCAL, Locale.GERMAN))
        assertNull(OnlineSearch.photonLanguage(PlaceLanguagePref.AUTO, es))
        assertEquals("en", OnlineSearch.photonLanguage(PlaceLanguagePref.EN, es))
        assertEquals("de", OnlineSearch.photonLanguage(PlaceLanguagePref.AUTO, Locale.GERMAN))
        assertEquals("fr", OnlineSearch.photonLanguage(PlaceLanguagePref.AUTO, Locale.FRENCH))
        assertNull(OnlineSearch.photonLanguage(PlaceLanguagePref.AUTO, Locale.forLanguageTag("pt")))
    }

    @Test fun `no geocoder never connects`() {
        assertFalse(NoOnlineGeocoder.enabled)
    }
}
