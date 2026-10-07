package com.qtekfun.ultimatemaps.core.fuel

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FuelModelTest {
    private val lpg = FuelType("lpg", "GLP")
    private fun st(id: String, lat: Double, lon: Double, price: Double?) =
        FuelStation(id, "X", "calle", "m", "p", LatLon(lat, lon), prices = if (price == null) emptyMap() else mapOf("lpg" to price))

    @Test fun filtersByBoundsAndFuelAndSortsByPrice() {
        val repo = InMemoryFuelRepository(listOf(st("a", 40.0, -3.0, 0.9), st("b", 40.1, -3.1, 0.8), st("c", 50.0, 0.0, 0.5), st("d", 40.05, -3.05, null)))
        val r = repo.stationsIn(LatLonBounds(39.9, -3.5, 40.5, -2.5), lpg)
        assertEquals(listOf("b", "a"), r.map { it.id })
        assertEquals(1, repo.stationsIn(LatLonBounds(39.9, -3.5, 40.5, -2.5), lpg, limit = 1).size)
        assertNull(repo.station("zzz"))
    }

    @Test fun settingsAreOffByDefault() {
        val s = FuelSettings()
        assertEquals(false, s.enabled); assertEquals(emptySet(), s.downloadedFuels); assertNull(s.mapFuel)
    }
}
