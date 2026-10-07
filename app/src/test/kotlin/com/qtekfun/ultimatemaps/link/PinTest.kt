package com.qtekfun.ultimatemaps.link

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PinTest {
    @Test
    fun placeLinkSetsThePin() {
        assertEquals(LatLon(40.4168, -3.7038), LinkHandler.handle("geo:40.4168,-3.7038").pinPoint())
    }

    @Test
    fun linksWithoutPlaceClearThePin() {
        assertNull(LinkHandler.handle("https://maps.app.goo.gl/abc123").pinPoint())
        assertNull(LinkHandler.handle("https://example.org/nada").pinPoint())
        assertNull(LinkHandler.handle(null).pinPoint())
    }
}
