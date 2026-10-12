package com.qtekfun.ultimatemaps.link

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun textSearchIsReturnedForTheSearchBox() {
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

    @Test
    fun linkPlaceWithoutNameUsesItsCoordinatesAsTheName() {
        val o = assertIs<LinkOutcome.ShowPlace>(LinkHandler.handle("geo:40.4168,-3.7038"))
        val info = o.toPlaceInfo()
        assertEquals("40.41680, -3.70380", info.name)
        assertEquals(o.point, info.point)
    }

    @Test
    fun linkPlaceKeepsItsLabelAsTheName() {
        val o = assertIs<LinkOutcome.ShowPlace>(LinkHandler.handle("https://maps.apple.com/?ll=41.3851,2.1734&q=Barcelona"))
        assertEquals("Barcelona", o.toPlaceInfo().name)
    }

    // --- Text shared into the app (ACTION_SEND) ---

    @Test
    fun sharedLongGoogleLinkLandsOnThePlaceWithTheLinkName() {
        val o = assertIs<LinkOutcome.ShowPlace>(
            LinkHandler.handleShared("Torre Glories\nhttps://www.google.com/maps/place/Torre+Glories/@41.4036,2.1894,17z"),
        )
        assertEquals(LatLon(41.4036, 2.1894), o.point)
        assertEquals("Torre Glories", o.toPlaceInfo().name)
        assertEquals(o.point, o.pinPoint())
    }

    @Test
    fun sharedPointWithoutALabelTakesTheSharedName() {
        val o = assertIs<LinkOutcome.ShowPlace>(
            LinkHandler.handleShared("Mi bar\nCalle Falsa 1\nhttps://www.google.com/maps/@40.4168,-3.7038,17z"),
        )
        assertEquals("Mi bar", o.toPlaceInfo().name)
    }

    @Test
    fun sharedShortLinkIsReturnedForExpansionWithItsNameAndNeverPinned() {
        val o = assertIs<LinkOutcome.ShortLink>(LinkHandler.handleShared("Plaza Mayor\nhttps://maps.app.goo.gl/AbCd123"))
        assertEquals("https://maps.app.goo.gl/AbCd123", o.url)
        assertEquals("Plaza Mayor", o.name)
        assertNull(o.pinPoint())
        // The view path still never resolves anything by itself.
        assertEquals(LinkOutcome.ShortLinkNotResolved, LinkHandler.handle("https://maps.app.goo.gl/AbCd123"))
    }

    @Test
    fun expandedShortLinkIsClassifiedWithTheSharedName() {
        val expanded = "https://www.google.com/maps/place/@40.4168,-3.7038,17z/data=!3m1!4b1!4m6!3m5!8m2!3d40.4168!4d-3.7038"
        val o = assertIs<LinkOutcome.ShowPlace>(LinkHandler.classify(expanded, "Plaza Mayor"))
        assertEquals("Plaza Mayor", o.toPlaceInfo().name)
    }

    @Test
    fun sharedTextWithoutAMapLinkIsUnrecognized() {
        assertEquals(LinkOutcome.Unrecognized, LinkHandler.handleShared("hello https://example.com"))
        assertEquals(LinkOutcome.Unrecognized, LinkHandler.handleShared(null))
    }

    @Test
    fun sharedCoordinatesAndGeoUris() {
        assertIs<LinkOutcome.ShowPlace>(LinkHandler.handleShared("40.4168, -3.7038"))
        assertIs<LinkOutcome.ShowPlace>(LinkHandler.handleShared("geo:40.4168,-3.7038"))
    }

    @Test
    fun sharedGoogleSearchLinkRunsAnOfflineSearch() {
        val o = assertIs<LinkOutcome.Search>(LinkHandler.handleShared("https://www.google.com/maps/search/?api=1&query=Plaza%20Mayor"))
        assertEquals("Plaza Mayor", o.query)
    }
}
