package com.qtekfun.ultimatemaps.core.chargers

import com.qtekfun.ultimatemaps.core.cameras.LatLonBounds
import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargerFilterTest {
    private fun charger(id: String, lat: Double, lon: Double, vararg sockets: ChargerSocket) = Charger(
        id, LatLon(lat, lon), "", "", "", 0, sockets.toList(), ChargerFee.UNKNOWN, ChargerAccess.UNKNOWN, 0, "",
    )

    private val slowType2 = charger("a", 40.0, -3.0, ChargerSocket(SocketType.TYPE2, 2, 7.4))
    private val fastCcs = charger("b", 40.1, -3.0, ChargerSocket(SocketType.CCS, 2, 150.0), ChargerSocket(SocketType.TYPE2, 1, 22.0))
    private val chademoOnly = charger("c", 40.2, -3.0, ChargerSocket(SocketType.CHADEMO, 1, 50.0))
    private val schuko = charger("d", 40.3, -3.0, ChargerSocket(SocketType.SCHUKO, 0, null))
    private val noInfo = charger("e", 40.4, -3.0)
    private val tesla = charger("f", 40.5, -3.0, ChargerSocket(SocketType.TESLA, 8, 250.0))
    private val all = listOf(slowType2, fastCcs, chademoOnly, schuko, noInfo, tesla)

    private fun ids(s: ChargerSettings) = all.filter(s::accepts).map { it.id }

    @Test fun byDefaultNothingIsFilteredOutAndTheLayerIsOff() {
        val s = ChargerSettings()
        assertFalse(s.enabled)
        assertFalse(s.socketFilterActive)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), ids(s), "other plugs and unknown plugs show while no plug filter is active")
    }

    @Test fun aNarrowerPlugSelectionKeepsOnlyStationsWithAKnownSelectedPlug() {
        assertEquals(listOf("a", "b"), ids(ChargerSettings(sockets = setOf(SocketType.TYPE2))))
        assertEquals(listOf("b"), ids(ChargerSettings(sockets = setOf(SocketType.CCS))))
        assertEquals(listOf("b", "c"), ids(ChargerSettings(sockets = setOf(SocketType.CCS, SocketType.CHADEMO))))
        assertEquals(listOf("d"), ids(ChargerSettings(sockets = setOf(SocketType.SCHUKO))))
    }

    @Test fun minimumPowerHidesSlowAndUnknownStations() {
        assertEquals(listOf("b", "c", "f"), ids(ChargerSettings(minPower = MinPower.KW_50)))
        assertEquals(listOf("b", "f"), ids(ChargerSettings(minPower = MinPower.KW_100)))
        assertEquals(listOf("b", "c", "f"), ids(ChargerSettings(minPower = MinPower.KW_22)), "7.4 kW is below 22 kW")
    }

    @Test fun thePowerOfThePlugsTheUserCanUseIsWhatCounts() {
        // fastCcs has a 150 kW CCS and a 22 kW Type 2: for a Type 2 only car the best usable output is 22 kW.
        assertTrue(ChargerSettings(sockets = setOf(SocketType.TYPE2), minPower = MinPower.KW_22).accepts(fastCcs))
        assertFalse(ChargerSettings(sockets = setOf(SocketType.TYPE2), minPower = MinPower.KW_50).accepts(fastCcs))
        assertTrue(ChargerSettings(sockets = setOf(SocketType.CCS), minPower = MinPower.KW_100).accepts(fastCcs))
    }

    @Test fun anEmptyOrForeignSelectionNormalizesToAllPlugs() {
        assertEquals(SocketType.FILTERABLE, ChargerSettings(sockets = emptySet()).normalized().sockets)
        assertEquals(SocketType.FILTERABLE, ChargerSettings(sockets = setOf(SocketType.TESLA)).normalized().sockets)
        assertEquals(setOf(SocketType.CCS), ChargerSettings(sockets = setOf(SocketType.CCS, SocketType.TESLA)).normalized().sockets)
        val store = InMemoryChargerSettingsStore()
        store.update { it.copy(sockets = emptySet()) }
        assertEquals(SocketType.FILTERABLE, store.settings.value.sockets)
    }

    @Test fun minPowerParsesNamesAndFallsBackToAny() {
        assertEquals(MinPower.KW_50, MinPower.ofName("KW_50"))
        assertEquals(MinPower.ANY, MinPower.ofName("nonsense"))
        assertEquals(MinPower.ANY, MinPower.ofName(null))
    }

    // ------------------------------------------------------------------ repository

    private fun repo(list: List<Charger> = all) = ChargerRepository().also { it.install(ChargerDataset(1_700_000_000L, ChargerSources.OSM, list)) }
    private val world = LatLonBounds(-90.0, -180.0, 90.0, 180.0)

    @Test fun viewportQueryFindsOnlyWhatIsInsideAndPassesTheFilter() {
        val r = repo()
        assertEquals(listOf("b", "c"), r.chargersIn(LatLonBounds(40.05, -3.5, 40.25, -2.5), ChargerSettings(), 100).map { it.id }.sorted())
        assertEquals(emptyList(), r.chargersIn(LatLonBounds(40.05, -2.5, 40.25, -2.0), ChargerSettings(), 100), "longitude is checked too")
        assertEquals(listOf("b"), r.chargersIn(LatLonBounds(40.0, -4.0, 41.0, -2.0), ChargerSettings(sockets = setOf(SocketType.CCS)), 100).map { it.id })
    }

    @Test fun overTheLimitTheMostPowerfulAreKept() {
        val r = repo()
        assertEquals(listOf("f", "b"), r.chargersIn(world, ChargerSettings(), 2).map { it.id })
    }

    @Test fun lookupByIdAndEmptyRepository() {
        val r = repo()
        assertEquals("c", r.charger("c")?.id)
        assertNull(r.charger("zzz"))
        assertEquals(1_700_000_000_000L, r.generatedMillis.value)
        r.install(null)
        assertEquals(0, r.data.chargers.size)
        assertNull(r.generatedMillis.value)
        assertEquals(emptyList(), r.chargersIn(world, ChargerSettings(), 10))
    }

    @Test fun theBinarySearchBoundaryIsInclusive() {
        val r = repo(listOf(charger("x", 40.0, -3.0), charger("y", 41.0, -3.0)))
        assertEquals(listOf("x", "y"), r.chargersIn(LatLonBounds(40.0, -3.0, 41.0, -3.0), ChargerSettings(), 10).map { it.id })
        assertEquals(listOf("y"), r.chargersIn(LatLonBounds(40.0000001, -4.0, 41.0, -2.0), ChargerSettings(), 10).map { it.id })
    }

    @Test fun titleAndFastness() {
        assertEquals("", noInfo.title)
        assertTrue(tesla.isFast)
        assertFalse(slowType2.isFast)
        assertFalse(noInfo.isFast)
        assertEquals("Op", noInfo.copy(operator = "Op", network = "Net").title)
        assertEquals("Net", noInfo.copy(network = "Net", name = "N").title)
        assertEquals("N", noInfo.copy(name = "N").title)
    }
}
