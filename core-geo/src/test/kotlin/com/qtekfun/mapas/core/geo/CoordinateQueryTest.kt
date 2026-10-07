package com.qtekfun.mapas.core.geo

import com.qtekfun.mapas.core.geo.CoordinateQuery.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CoordinateQueryTest {
    private fun ok(text: String, lat: Double, lon: Double, kind: Kind, reference: LatLon? = null) {
        val p = assertNotNull(CoordinateQuery.parse(text, reference), "should parse: $text")
        assertEquals(lat, p.point.lat, 1e-6, "lat of $text")
        assertEquals(lon, p.point.lon, 1e-6, "lon of $text")
        assertEquals(kind, p.kind, "kind of $text")
    }

    private fun no(text: String, reference: LatLon? = null) =
        assertNull(CoordinateQuery.parse(text, reference), "should not parse: $text")

    @Test
    fun decimalPairs() {
        ok("40.4168, -3.7038", 40.4168, -3.7038, Kind.DECIMAL)
        ok("40.4168,-3.7038", 40.4168, -3.7038, Kind.DECIMAL)
        ok("40.4168 -3.7038", 40.4168, -3.7038, Kind.DECIMAL)
        ok("  -33.8688;151.2093 ", -33.8688, 151.2093, Kind.DECIMAL)
        ok("40.4168,3.7038", 40.4168, 3.7038, Kind.DECIMAL)
        ok("40.4168°, -3.7038°", 40.4168, -3.7038, Kind.DECIMAL)
        ok("40.5, 3", 40.5, 3.0, Kind.DECIMAL)
    }

    @Test
    fun decimalCommas() {
        ok("40,4168 -3,7038", 40.4168, -3.7038, Kind.DECIMAL)
        ok("40,4168; -3,7038", 40.4168, -3.7038, Kind.DECIMAL)
        ok("40,4168, -3,7038", 40.4168, -3.7038, Kind.DECIMAL)
    }

    @Test
    fun hemisphereLetters() {
        ok("40.4168N 3.7038W", 40.4168, -3.7038, Kind.DECIMAL)
        ok("40.4168 N, 3.7038 W", 40.4168, -3.7038, Kind.DECIMAL)
        ok("N 40.4168 W 3.7038", 40.4168, -3.7038, Kind.DECIMAL)
        ok("N40.4168, W3.7038", 40.4168, -3.7038, Kind.DECIMAL)
        ok("33.8688S 151.2093E", -33.8688, 151.2093, Kind.DECIMAL)
        ok("3.7038W 40.4168N", 40.4168, -3.7038, Kind.DECIMAL) // longitude first
        ok("40.4168N 3.7038O", 40.4168, -3.7038, Kind.DECIMAL) // Spanish "oeste"
        ok("40.4168n 3.7038w", 40.4168, -3.7038, Kind.DECIMAL)
    }

    @Test
    fun degreesMinutesSeconds() {
        val lat = 40 + 26 / 60.0 + 46 / 3600.0
        val lon = 3 + 42 / 60.0 + 14 / 3600.0
        ok("40°26'46\"N 3°42'14\"W", lat, -lon, Kind.DMS)
        ok("40° 26′ 46″ N, 3° 42′ 14″ W", lat, -lon, Kind.DMS)
        ok("40 26 46 N 3 42 14 W", lat, -lon, Kind.DMS)
        ok("N 40 26 46 W 3 42 14", lat, -lon, Kind.DMS)
        ok("40°26.7667'N 3°42.2333'W", 40 + 26.7667 / 60, -(3 + 42.2333 / 60), Kind.DMS)
        ok("40 26 46, -3 42 14", lat, -lon, Kind.DMS)
        ok("40° 26' -3° 42'", 40 + 26 / 60.0, -(3 + 42 / 60.0), Kind.DMS)
    }

    @Test
    fun rejectsWhatIsNotAPosition() {
        no("")
        no("Madrid")
        no("Calle Mayor 5")
        no("12 34") // two bare integers
        no("28001")
        no("91.0, 10.0") // latitude out of range
        no("10.0, 181.0")
        no("40.4168N 3.7038N") // two latitudes
        no("40.4168W 3.7038W")
        no("-40.4168S 3.7038E") // sign and hemisphere disagree
        no("40 61 00 N 3 0 0 W") // minutes out of range
        no("40.4168, -3.7038, 12.0") // three numbers
        no("cafe 40.4168, -3.7038")
        no("40.4168,")
        no("N S")
        no("+34 600 000 000")
        no("+34")
        no("1." + "2".repeat(100) + ", 3.0")
    }

    @Test
    fun fullPlusCodes() {
        val p = assertNotNull(CoordinateQuery.parse("8FVC2222+22"))
        assertEquals(Kind.PLUS_CODE, p.kind)
        assertEquals(47.0000625, p.point.lat, 1e-9)
        assertEquals(8.0000625, p.point.lon, 1e-9)
        assertEquals(Kind.PLUS_CODE, CoordinateQuery.parse("8fvc2222+22")?.kind)
        no("8FVC2222+2") // one digit after the separator
        no("8FVC2222+22 Zurich") // a locality is not supported
    }

    @Test
    fun shortPlusCodesNeedAReference() {
        // From the published short-code vectors: 9C3W9QCJ+2VX near 51.3701125, -1.217765625.
        no("9QCJ+2VX")
        val p = assertNotNull(CoordinateQuery.parse("9QCJ+2VX", LatLon(51.3701125, -1.217765625)))
        assertEquals(Kind.PLUS_CODE_SHORT, p.kind)
        assertEquals(OpenLocationCode.decode("9C3W9QCJ+2VX").center.lat, p.point.lat, 1e-9)
        assertEquals(OpenLocationCode.decode("9C3W9QCJ+2VX").center.lon, p.point.lon, 1e-9)
        no("+2VX", LatLon(51.37, -1.21)) // too short to be told from other text
        no("CJ+2VX", LatLon(51.37, -1.21))
    }

    @Test
    fun formatsDecimalWithDotsAndFiveDecimals() {
        assertEquals("40.41680, -3.70380", CoordinateQuery.formatDecimal(LatLon(40.4168, -3.7038)))
    }
}
