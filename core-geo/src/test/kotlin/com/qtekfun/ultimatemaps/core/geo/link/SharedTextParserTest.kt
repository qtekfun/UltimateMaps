package com.qtekfun.ultimatemaps.core.geo.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SharedTextParserTest {

    @Test fun googleMapsShareIsNameAddressAndShortLink() {
        val s = SharedTextParser.extract("Plaza Mayor\nPl. Mayor, 28012 Madrid\nhttps://maps.app.goo.gl/AbCd123xYz")
        assertNotNull(s)
        assertEquals("https://maps.app.goo.gl/AbCd123xYz", s.url)
        assertEquals("Plaza Mayor", s.name)
    }

    @Test fun bareUrlHasNoName() {
        val s = SharedTextParser.extract("https://maps.app.goo.gl/AbCd123xYz")
        assertNotNull(s)
        assertEquals("https://maps.app.goo.gl/AbCd123xYz", s.url)
        assertNull(s.name)
    }

    @Test fun nameAndUrlOnTheSameLine() {
        val s = SharedTextParser.extract("Cafe Central - https://www.google.com/maps/place/Cafe+Central/@40.4,-3.7,17z")
        assertNotNull(s)
        assertEquals("https://www.google.com/maps/place/Cafe+Central/@40.4,-3.7,17z", s.url)
        assertEquals("Cafe Central", s.name)
    }

    @Test fun trailingPunctuationAndAngleBracketsAreNotPartOfTheLink() {
        assertEquals(
            "https://maps.app.goo.gl/abc",
            SharedTextParser.extract("Look: <https://maps.app.goo.gl/abc>.")?.url,
        )
        assertEquals(
            "https://maps.app.goo.gl/abc",
            SharedTextParser.extract("(see https://maps.app.goo.gl/abc), thanks")?.url,
        )
    }

    @Test fun firstMapLinkWinsOverOtherLinks() {
        val s = SharedTextParser.extract("Menu https://example.com/menu and map https://maps.apple.com/?ll=41.3851,2.1734&q=Barcelona")
        assertEquals("https://maps.apple.com/?ll=41.3851,2.1734&q=Barcelona", s?.url)
    }

    @Test fun geoUriInsideText() {
        assertEquals("geo:40.4168,-3.7038?q=40.4168,-3.7038(Home)", SharedTextParser.extract("Home geo:40.4168,-3.7038?q=40.4168,-3.7038(Home)")?.url)
    }

    @Test fun bareCoordinatesBecomeAGeoLink() {
        val s = SharedTextParser.extract("40.4168, -3.7038")
        assertEquals("geo:40.4168,-3.7038", s?.url)
    }

    @Test fun plainTextAndNonMapLinksAreNothing() {
        assertNull(SharedTextParser.extract("see you at 8 at the bar"))
        assertNull(SharedTextParser.extract("https://example.com/page"))
        assertNull(SharedTextParser.extract(""))
        assertNull(SharedTextParser.extract(null))
    }

    @Test fun veryLongNamesAreCut() {
        val s = SharedTextParser.extract("N".repeat(500) + "\nhttps://maps.app.goo.gl/x")
        assertEquals(120, s?.name?.length)
    }

    @Test fun resultFeedsTheParser() {
        val s = SharedTextParser.extract("Torre\nhttps://www.google.com/maps/place/Torre/@41.4036,2.1744,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d41.4036!4d2.1744")
        assertNotNull(s)
        val link = MapLinkParser.parse(s.url)
        assertEquals(41.4036, (link as MapLink.Coordinates).point.lat, 1e-9)
    }
}
