package com.qtekfun.mapas.places

import com.qtekfun.mapas.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceShareTest {
    private val madrid = LatLon(40.4168, -3.7038)

    @Test fun osmLinkMarksThePointAndCentresTheMap() {
        assertEquals(
            "https://www.openstreetmap.org/?mlat=40.416800&mlon=-3.703800#map=17/40.416800/-3.703800",
            PlaceShare.osmLink(madrid),
        )
    }

    @Test fun osmLinkUsesDotsWhateverTheLocale() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("es", "ES"))
            assertTrue("mlat=40.416800&mlon=-3.703800" in PlaceShare.osmLink(madrid))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test fun textHasTheNameTheGeoUriAndTheOsmLinkOnSeparateLines() {
        val text = PlaceShare.text(PlaceInfo("Cafe Central", madrid, "Calle Mayor 1", "cafe"))
        val lines = text.lines()
        assertEquals(3, lines.size)
        assertEquals("Cafe Central", lines[0])
        assertEquals("geo:0,0?q=40.416800,-3.703800(Cafe%20Central)", lines[1])
        assertEquals(PlaceShare.osmLink(madrid), lines[2])
    }

    @Test fun anUnnamedPlaceSharesOnlyTheLinks() {
        val lines = PlaceShare.text(PlaceInfo("  ", madrid)).lines()
        assertEquals(listOf("geo:40.416800,-3.703800", PlaceShare.osmLink(madrid)), lines)
    }
}
