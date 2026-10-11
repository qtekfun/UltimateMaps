package com.qtekfun.ultimatemaps.core.search

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HouseNumberHintTest {
    private fun r(name: String) = SearchResult(name, LatLon(40.0, -3.0))
    private val streets = listOf(r("Calle del Pensamiento"), r("Calle Pensamiento"))

    @Test fun `a number with only streets in the results is reported`() {
        assertEquals("2", HouseNumberHint.missingNumber("calle del pensamiento 2", streets))
        assertEquals("2", HouseNumberHint.missingNumber("Calle Pensamiento, 2", streets))
        assertEquals("12B", HouseNumberHint.missingNumber("calle del pensamiento 12B", streets))
        assertEquals("5", HouseNumberHint.missingNumber("calle del pensamiento nº 5", streets))
        assertEquals("5", HouseNumberHint.missingNumber("calle del pensamiento n.º 5", streets))
    }

    @Test fun `a result that has the number clears it`() {
        assertNull(HouseNumberHint.missingNumber("calle pensamiento 2", listOf(r("Calle Pensamiento, 2"), r("Calle del Pensamiento"))))
        assertNull(HouseNumberHint.missingNumber("calle 30", listOf(r("Calle 30"))), "a street called 30")
        assertNull(HouseNumberHint.missingNumber("calle pensamiento 12b", listOf(r("Calle Pensamiento, 12B"))))
    }

    @Test fun `queries without a house number say nothing`() {
        assertNull(HouseNumberHint.missingNumber("calle del pensamiento", streets))
        assertNull(HouseNumberHint.missingNumber("farmacia", streets))
        assertNull(HouseNumberHint.missingNumber("28915", streets), "a bare number")
        assertNull(HouseNumberHint.missingNumber("madrid 2", emptyList()), "nothing found: the usual message")
    }

    @Test fun `roads, kilometres and exits are not house numbers`() {
        assertNull(HouseNumberHint.missingNumber("autovia a 6", streets))
        assertNull(HouseNumberHint.missingNumber("m 30", streets))
        assertNull(HouseNumberHint.missingNumber("a-6 km 12", streets))
        assertNull(HouseNumberHint.missingNumber("salida 7", streets))
        assertNull(HouseNumberHint.missingNumber("ctra 340", streets))
    }
}
