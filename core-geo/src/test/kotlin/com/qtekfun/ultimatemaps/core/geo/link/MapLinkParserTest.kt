package com.qtekfun.ultimatemaps.core.geo.link

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapLinkParserTest {

    private fun coords(link: String): MapLink.Coordinates = assertIs<MapLink.Coordinates>(MapLinkParser.parse(link))

    private fun assertPoint(lat: Double, lon: Double, p: LatLon) {
        assertEquals(lat, p.lat, 1e-9)
        assertEquals(lon, p.lon, 1e-9)
    }

    // ------------------------------------------------------------ geo:

    @Test fun geoSimple() {
        val c = coords("geo:40.4168,-3.7038")
        assertPoint(40.4168, -3.7038, c.point)
        assertNull(c.zoom)
    }

    @Test fun geoWithZoomAndUncertainty() {
        val c = coords("geo:47.6,-122.3;u=35?z=11")
        assertPoint(47.6, -122.3, c.point)
        assertEquals(11.0, c.zoom)
    }

    @Test fun geoWithAltitude() = assertPoint(48.2, 16.37, coords("geo:48.2,16.37,170").point)

    @Test fun geoZeroZeroQueryIsTextSearchWithoutBias() {
        val s = assertIs<MapLink.TextSearch>(MapLinkParser.parse("geo:0,0?q=pizza%20madrid"))
        assertEquals("pizza madrid", s.query)
        assertNull(s.near)
    }

    @Test fun geoQueryWithCoordsAndLabel() {
        val c = coords("geo:0,0?q=40.4168,-3.7038(Puerta%20del%20Sol)")
        assertPoint(40.4168, -3.7038, c.point)
        assertEquals("Puerta del Sol", c.label)
    }

    @Test fun geoQueryNearPoint() {
        val s = assertIs<MapLink.TextSearch>(MapLinkParser.parse("geo:41.38,2.17?q=cafe"))
        assertEquals("cafe", s.query)
        assertPoint(41.38, 2.17, s.near!!)
    }

    @Test fun geoWithoutCoordinatesButQuery() {
        assertEquals("farmacia", assertIs<MapLink.TextSearch>(MapLinkParser.parse("geo:?q=farmacia")).query)
    }

    @Test fun geoInvalidLatitudeIsUnrecognized() {
        assertIs<MapLink.Unrecognized>(MapLinkParser.parse("geo:123.0,5.0"))
    }

    // ------------------------------------------------------------ Google

    @Test fun googleViewport() {
        val c = coords("https://www.google.com/maps/@40.4167754,-3.7037902,15z")
        assertPoint(40.4167754, -3.7037902, c.point)
        assertEquals(15.0, c.zoom)
    }

    @Test fun googleViewportSatelliteMetersHasNoZoom() {
        val c = coords("https://www.google.com/maps/@40.4167754,-3.7037902,1500m/data=!3m1!1e3")
        assertNull(c.zoom)
    }

    @Test fun googlePlaceWithViewport() {
        val c = coords("https://www.google.com/maps/place/Puerta+del+Sol,+28013+Madrid/@40.4169019,-3.7056721,17z")
        assertPoint(40.4169019, -3.7056721, c.point)
        assertEquals("Puerta del Sol, 28013 Madrid", c.label)
        assertEquals(17.0, c.zoom)
    }

    @Test fun googlePlacePrefersDataPinOverViewport() {
        val c = coords(
            "https://www.google.com/maps/place/Torre+Eiffel/@48.8583701,2.2919064,17z/" +
                "data=!3m1!4b1!4m6!3m5!1s0x47e66e2964e34e2d:0x8ddca9ee380ef7e0!8m2!3d48.8583701!4d2.2944813!16zL20vMDJfMjg2",
        )
        assertPoint(48.8583701, 2.2944813, c.point)
        assertEquals("Torre Eiffel", c.label)
    }

    @Test fun googlePlaceWithoutCoordinatesIsTextSearch() {
        val s = assertIs<MapLink.TextSearch>(MapLinkParser.parse("https://www.google.com/maps/place/Sagrada+Familia"))
        assertEquals("Sagrada Familia", s.query)
    }

    @Test fun googlePlaceWithEncodedUtf8Name() {
        val s = assertIs<MapLink.TextSearch>(
            MapLinkParser.parse("https://www.google.es/maps/place/Pe%C3%B1%C3%ADscola"),
        )
        assertEquals("Peñíscola", s.query)
    }

    @Test fun googlePlaceNamedByCoordinates() {
        assertPoint(41.40338, 2.17403, coords("https://www.google.com/maps/place/41.40338,2.17403").point)
    }

    @Test fun googlePlaceNamedByDms() {
        val c = coords("https://www.google.com/maps/place/40%C2%B026'46.3%22N+79%C2%B058'55.9%22W/@40.4461,-79.9822,17z")
        assertEquals(40.4461, c.point.lat, 1e-3)
        assertEquals(-79.9822, c.point.lon, 1e-3)
    }

    @Test fun googleQueryCoordinates() {
        assertPoint(37.7749, -122.4194, coords("https://maps.google.com/?q=37.7749,-122.4194").point)
    }

    @Test fun googleQueryText() {
        val s = assertIs<MapLink.TextSearch>(MapLinkParser.parse("https://maps.google.com/maps?q=hospital+la+paz&hl=es"))
        assertEquals("hospital la paz", s.query)
    }

    @Test fun googleQueryTextNearLl() {
        val s = assertIs<MapLink.TextSearch>(MapLinkParser.parse("https://maps.google.com/maps?q=bakery&ll=52.52,13.40&z=14"))
        assertPoint(52.52, 13.40, s.near!!)
        assertEquals(14.0, s.zoom)
    }

    @Test fun googleLlOnly() {
        val c = coords("https://www.google.com/maps?ll=51.5074,-0.1278&z=12")
        assertPoint(51.5074, -0.1278, c.point)
        assertEquals(12.0, c.zoom)
    }

    @Test fun googleDataPinInSearchUrl() {
        val c = coords("https://www.google.com/maps/place//data=!4m2!3m1!1s0x0:0x0!3d35.6586!4d139.7454")
        assertPoint(35.6586, 139.7454, c.point)
    }

    @Test fun googleSearchPath() {
        val s = assertIs<MapLink.TextSearch>(
            MapLinkParser.parse("https://www.google.com/maps/search/gasolinera/@40.4,-3.7,14z"),
        )
        assertEquals("gasolinera", s.query)
        assertPoint(40.4, -3.7, s.near!!)
    }

    @Test fun googleSearchApi1() {
        val s = assertIs<MapLink.TextSearch>(
            MapLinkParser.parse("https://www.google.com/maps/search/?api=1&query=museo%20del%20prado"),
        )
        assertEquals("museo del prado", s.query)
    }

    @Test fun googleDirApi1() {
        val r = assertIs<MapLink.Route>(
            MapLinkParser.parse(
                "https://www.google.com/maps/dir/?api=1&origin=Madrid&destination=40.4,-3.7&travelmode=walking&dir_action=navigate",
            ),
        )
        assertEquals("Madrid", r.origin?.text)
        assertPoint(40.4, -3.7, r.destination!!.point!!)
        assertEquals(TravelMode.WALKING, r.mode)
        assertTrue(r.navigate)
    }

    @Test fun googleDirPathWithWaypoints() {
        val r = assertIs<MapLink.Route>(
            MapLinkParser.parse("https://www.google.com/maps/dir/Madrid/Toledo/Sevilla/@38.5,-4.5,8z/data=!3m1!4b1!4m2"),
        )
        assertEquals("Madrid", r.origin?.text)
        assertEquals("Sevilla", r.destination?.text)
        assertEquals(listOf("Toledo"), r.waypoints.map { it.text })
    }

    @Test fun googleDirDestinationOnly() {
        val r = assertIs<MapLink.Route>(MapLinkParser.parse("https://www.google.com/maps/dir//41.3851,2.1734"))
        assertNull(r.origin)
        assertPoint(41.3851, 2.1734, r.destination!!.point!!)
    }

    @Test fun googleClassicSaddrDaddr() {
        val r = assertIs<MapLink.Route>(
            MapLinkParser.parse("https://maps.google.com/maps?saddr=40.41,-3.70&daddr=Valencia&dirflg=w"),
        )
        assertPoint(40.41, -3.70, r.origin!!.point!!)
        assertEquals("Valencia", r.destination?.text)
    }

    @Test fun googleRegionalDomain() {
        assertPoint(48.85, 2.35, coords("https://www.google.fr/maps/@48.85,2.35,12z").point)
        assertPoint(51.5, -0.12, coords("https://www.google.co.uk/maps/@51.5,-0.12,12z").point)
    }

    @Test fun googleNonMapsPathIsUnrecognized() {
        assertIs<MapLink.Unrecognized>(MapLinkParser.parse("https://www.google.com/search?q=madrid"))
    }

    @Test fun uppercaseSchemeAndHostAreAccepted() {
        assertPoint(10.0, 20.0, coords("HTTPS://WWW.GOOGLE.COM/maps/@10,20,5z").point)
    }

    // ------------------------------------------------------------ short links (no network)

    @Test fun shortMapsAppGooGl() {
        val l = MapLinkParser.parse("https://maps.app.goo.gl/AbCdEf123456")
        assertEquals(MapLink.ShortLink("https://maps.app.goo.gl/AbCdEf123456"), l)
    }

    @Test fun shortGooGlMaps() {
        assertIs<MapLink.ShortLink>(MapLinkParser.parse("https://goo.gl/maps/xYz789"))
    }

    @Test fun gooGlNonMapsIsUnrecognized() {
        assertIs<MapLink.Unrecognized>(MapLinkParser.parse("https://goo.gl/abc"))
    }

    @Test fun shortLinkKeepsOriginalTextTrimmed() {
        val l = assertIs<MapLink.ShortLink>(MapLinkParser.parse("  https://maps.app.goo.gl/q1  "))
        assertEquals("https://maps.app.goo.gl/q1", l.url)
    }

    // ------------------------------------------------------------ Apple

    @Test fun appleLl() = assertPoint(40.7128, -74.006, coords("https://maps.apple.com/?ll=40.7128,-74.0060").point)

    @Test fun appleLlWithQueryLabelsThePin() {
        val c = coords("http://maps.apple.com/?ll=50.894967,4.341626&q=Grand%20Place&z=16")
        assertEquals("Grand Place", c.label)
        assertEquals(16.0, c.zoom)
    }

    @Test fun appleQueryOnly() {
        val s = assertIs<MapLink.TextSearch>(MapLinkParser.parse("https://maps.apple.com/?q=Coffee&sll=37.33,-122.03"))
        assertEquals("Coffee", s.query)
        assertPoint(37.33, -122.03, s.near!!)
    }

    @Test fun appleDirections() {
        val r = assertIs<MapLink.Route>(
            MapLinkParser.parse("https://maps.apple.com/?saddr=Cupertino&daddr=37.7749,-122.4194&dirflg=r"),
        )
        assertEquals("Cupertino", r.origin?.text)
        assertPoint(37.7749, -122.4194, r.destination!!.point!!)
        assertEquals(TravelMode.TRANSIT, r.mode)
    }

    @Test fun appleDestinationAddressText() {
        val r = assertIs<MapLink.Route>(MapLinkParser.parse("https://maps.apple.com/?daddr=1+Infinite+Loop,+Cupertino&dirflg=d"))
        assertEquals("1 Infinite Loop, Cupertino", r.destination?.text)
        assertEquals(TravelMode.DRIVING, r.mode)
    }

    @Test fun applePlaceCoordinateFormat() {
        val c = coords("https://maps.apple.com/place?coordinate=48.8584,2.2945&name=Eiffel%20Tower")
        assertPoint(48.8584, 2.2945, c.point)
        assertEquals("Eiffel Tower", c.label)
    }

    // ------------------------------------------------------------ Waze

    @Test fun wazeLl() = assertPoint(32.0853, 34.7818, coords("https://waze.com/ul?ll=32.0853,34.7818").point)

    @Test fun wazeNavigateYes() {
        val r = assertIs<MapLink.Route>(MapLinkParser.parse("https://www.waze.com/ul?ll=45.6906304,-120.810983&navigate=yes&zoom=17"))
        assertTrue(r.navigate)
        assertNull(r.origin)
        assertPoint(45.6906304, -120.810983, r.destination!!.point!!)
    }

    @Test fun wazeQueryText() {
        val s = assertIs<MapLink.TextSearch>(MapLinkParser.parse("https://waze.com/ul?q=66%20Acacia%20Avenue"))
        assertEquals("66 Acacia Avenue", s.query)
    }

    @Test fun wazeQueryWithNavigate() {
        val r = assertIs<MapLink.Route>(MapLinkParser.parse("https://waze.com/ul?q=Louvre&navigate=yes"))
        assertEquals("Louvre", r.destination?.text)
        assertTrue(r.navigate)
    }

    @Test fun wazeGeohash() {
        // "ezs42" is the canonical geohash example: about 42.605, -5.603.
        val c = coords("https://waze.com/ul/hezs42")
        assertEquals(42.605, c.point.lat, 0.03)
        assertEquals(-5.603, c.point.lon, 0.03)
    }

    @Test fun wazeSchemeLink() {
        val r = assertIs<MapLink.Route>(MapLinkParser.parse("waze://?ll=40.4,-3.7&navigate=yes"))
        assertTrue(r.navigate)
    }

    @Test fun wazeLiveMapDirections() {
        val r = assertIs<MapLink.Route>(
            MapLinkParser.parse("https://www.waze.com/live-map/directions?to=ll.40.4168%2C-3.7038&from=ll.41.38%2C2.17"),
        )
        assertPoint(40.4168, -3.7038, r.destination!!.point!!)
        assertPoint(41.38, 2.17, r.origin!!.point!!)
    }

    // ------------------------------------------------------------ garbage

    @Test fun unrelatedInputIsUnrecognized() {
        for (s in listOf("", "hello", "https://example.com/maps/@1,2,3z", "mailto:a@b.c", "40.4,-3.7")) {
            assertIs<MapLink.Unrecognized>(MapLinkParser.parse(s), s)
        }
    }

    @Test fun malformedPercentEscapeDoesNotThrow() {
        MapLinkParser.parse("https://maps.google.com/?q=100%+match")
    }

    @Test fun outOfRangeCoordinatesInQueryFallBackToText() {
        assertIs<MapLink.TextSearch>(MapLinkParser.parse("https://maps.google.com/?q=999,999"))
    }
}
