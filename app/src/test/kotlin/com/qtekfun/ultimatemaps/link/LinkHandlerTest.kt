package com.qtekfun.ultimatemaps.link

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class LinkHandlerTest {
    @Test
    fun geoUriShowsPlace() {
        val o = assertIs<LinkOutcome.ShowPlace>(LinkHandler.handle("geo:40.4168,-3.7038?z=15"))
        assertEquals(LatLon(40.4168, -3.7038), o.point)
    }

    @Test
    fun googleMapsLongLinkShowsPlace() {
        val o = assertIs<LinkOutcome.ShowPlace>(LinkHandler.handle("https://www.google.com/maps/@40.4168,-3.7038,17z"))
        assertEquals(40.4168, o.point.lat, 1e-6)
    }

    @Test
    fun appleMapsLinkShowsPlace() {
        assertIs<LinkOutcome.ShowPlace>(LinkHandler.handle("https://maps.apple.com/?ll=41.3851,2.1734&q=Barcelona"))
    }

    @Test
    fun wazeLinkShowsPlace() {
        assertIs<LinkOutcome.ShowPlace>(LinkHandler.handle("https://waze.com/ul?ll=40.4168,-3.7038&navigate=yes"))
    }

    @Test
    fun textSearchOnlyWarns() {
        val o = assertIs<LinkOutcome.Search>(LinkHandler.handle("geo:0,0?q=Plaza%20Mayor"))
        assertEquals("Plaza Mayor", o.query)
    }

    @Test
    fun shortLinksAreNeverResolved() {
        assertEquals(LinkOutcome.ShortLinkNotResolved, LinkHandler.handle("https://maps.app.goo.gl/abc123"))
        assertEquals(LinkOutcome.ShortLinkNotResolved, LinkHandler.handle("https://goo.gl/maps/abc123"))
    }

    @Test
    fun garbageIsUnrecognized() {
        assertEquals(LinkOutcome.Unrecognized, LinkHandler.handle(null))
        assertEquals(LinkOutcome.Unrecognized, LinkHandler.handle(""))
        assertEquals(LinkOutcome.Unrecognized, LinkHandler.handle("https://example.com/hello"))
    }

    @Test
    fun routeLinkShowsItsDestination() {
        val o = LinkHandler.handle("https://www.google.com/maps/dir/?api=1&destination=40.4168,-3.7038")
        assertNotNull(o)
        assertIs<LinkOutcome.ShowPlace>(o)
    }
}
