package com.qtekfun.ultimatemaps.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceExtrasTest {
    @Test
    fun dialUriKeepsDigitsAndALeadingPlus() {
        assertEquals("tel:+34912345678", PlaceExtras.dialUri("+34 912 34 56 78"))
        assertEquals("tel:+34912345678", PlaceExtras.dialUri("+34-91-234-56-78"))
        assertEquals("tel:912345678", PlaceExtras.dialUri("(91) 234 56 78"))
        assertEquals("tel:+34912345678", PlaceExtras.dialUri("0034 912 345 678"))
        assertEquals("tel:+34912345678", PlaceExtras.dialUri("+34 912 345 678; +34 600 000 000"))
        assertEquals("tel:+34600000000", PlaceExtras.dialUri(" ; +34 600 000 000"))
    }

    @Test
    fun dialUriRejectsTextWithoutANumber() {
        assertNull(PlaceExtras.dialUri(null))
        assertNull(PlaceExtras.dialUri(""))
        assertNull(PlaceExtras.dialUri("call us"))
        assertNull(PlaceExtras.dialUri("12"))
    }

    @Test
    fun firstPhoneIsAsWritten() {
        assertEquals("+34 912 345 678", PlaceExtras.firstPhone("+34 912 345 678; +34 600 000 000"))
        assertNull(PlaceExtras.firstPhone(" ; "))
    }

    @Test
    fun webUriAcceptsOnlyHttpAndHttps() {
        assertEquals("https://example.com", PlaceExtras.webUri("example.com"))
        assertEquals("http://example.com/menu?x=1", PlaceExtras.webUri(" http://example.com/menu?x=1 "))
        assertEquals("HTTPS://www.example.es/a", PlaceExtras.webUri("HTTPS://www.example.es/a")) // scheme case is kept
        assertEquals("https://example.com:8080/x", PlaceExtras.webUri("example.com:8080/x"))
        assertNull(PlaceExtras.webUri("javascript:alert(1)"))
        assertNull(PlaceExtras.webUri("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(PlaceExtras.webUri("file:///etc/passwd"))
        assertNull(PlaceExtras.webUri("tel:+34912345678"))
        assertNull(PlaceExtras.webUri("not a url"))
        assertNull(PlaceExtras.webUri("localhost"))
        assertNull(PlaceExtras.webUri(""))
        assertNull(PlaceExtras.webUri(null))
    }

    @Test
    fun wheelchairValues() {
        assertEquals(Wheelchair.YES, Wheelchair.fromOsm("yes"))
        assertEquals(Wheelchair.YES, Wheelchair.fromOsm("Designated"))
        assertEquals(Wheelchair.LIMITED, Wheelchair.fromOsm("limited"))
        assertEquals(Wheelchair.NO, Wheelchair.fromOsm(" no "))
        assertNull(Wheelchair.fromOsm("unknown"))
        assertNull(Wheelchair.fromOsm(null))
    }

    @Test
    fun emptyExtras() {
        assertTrue(PlaceExtras().isEmpty)
        assertTrue(PlaceExtras(phone = " ", openingHours = "").isEmpty)
        assertFalse(PlaceExtras(wheelchair = Wheelchair.NO).isEmpty)
        assertFalse(PlaceExtras(website = "example.com").isEmpty)
    }
}
