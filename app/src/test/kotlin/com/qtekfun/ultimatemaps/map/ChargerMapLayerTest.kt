package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.chargers.Charger
import com.qtekfun.ultimatemaps.core.chargers.ChargerAccess
import com.qtekfun.ultimatemaps.core.chargers.ChargerDataset
import com.qtekfun.ultimatemaps.core.chargers.ChargerFee
import com.qtekfun.ultimatemaps.core.chargers.ChargerRepository
import com.qtekfun.ultimatemaps.core.chargers.ChargerSettings
import com.qtekfun.ultimatemaps.core.chargers.ChargerSocket
import com.qtekfun.ultimatemaps.core.chargers.InMemoryChargerSettingsStore
import com.qtekfun.ultimatemaps.core.chargers.MinPower
import com.qtekfun.ultimatemaps.core.chargers.SocketType
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.ChargerPin
import com.qtekfun.ultimatemaps.core.map.GeoBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The layer's decisions on the JVM: off by default, minimum zoom, filters, thinning, redraw only on change. */
class ChargerMapLayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun charger(id: String, lat: Double, lon: Double, vararg sockets: ChargerSocket) = Charger(
        id, LatLon(lat, lon), "Op $id", "", "", 0, sockets.toList(), ChargerFee.UNKNOWN, ChargerAccess.UNKNOWN, 0, "",
    )

    private val slow = ChargerSocket(SocketType.TYPE2, 2, 7.4)
    private val fast = ChargerSocket(SocketType.CCS, 2, 150.0)

    private val three = listOf(
        charger("a", 40.1, -3.9, slow),
        charger("b", 40.5, -3.5, fast),
        charger("c", 40.9, -3.1, ChargerSocket(SocketType.CHADEMO, 1, 50.0)),
    )

    private val view = GeoBounds(40.0, -4.0, 41.0, -3.0)
    private val drawn = CopyOnWriteArrayList<List<ChargerPin>>()

    private fun repo(list: List<Charger> = three) = ChargerRepository().also { it.install(ChargerDataset(1_700_000_000L, 1, list)) }

    private fun layer(repository: ChargerRepository, store: InMemoryChargerSettingsStore, debounce: Long = 10) =
        ChargerMapLayer(scope, Dispatchers.IO, repository, store.settings, { drawn += it }, debounce).also { it.start() }

    private fun on(s: ChargerSettings = ChargerSettings()) = InMemoryChargerSettingsStore(s.copy(enabled = true))

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout waiting for $what" }
            Thread.sleep(5)
        }
    }

    /** Lets any pending debounce expire, to assert that nothing happened. */
    private fun settle() = Thread.sleep(150)

    @After fun tearDown() = scope.cancel()

    @Test fun offByDefaultItDrawsNothing() {
        val l = ChargerMapLayer(scope, Dispatchers.IO, repo(), InMemoryChargerSettingsStore().settings, { drawn += it }, 10).also { it.start() }
        l.onViewport(view, 14.0)
        settle()
        assertTrue(drawn.isEmpty(), "nothing is rendered, not even an empty list")
        l.stop()
    }

    @Test fun drawsTheStationsInViewWithTheirFastFlag() {
        val l = layer(repo(), on())
        l.onViewport(view, 12.0)
        await("draw") { drawn.isNotEmpty() }
        val byLat = drawn.last().sortedBy { it.point.lat }
        assertEquals(listOf(false, true, true), byLat.map { it.fast }, "7.4 kW is normal, 150 kW and 50 kW are fast")
        l.stop()
    }

    @Test fun belowTheMinimumZoomNothingIsDrawn() {
        val l = layer(repo(), on())
        l.onViewport(view, ChargerMapLayer.MIN_ZOOM - 0.5)
        settle()
        assertTrue(drawn.isEmpty(), "no pins and no redraw below the minimum zoom")
        l.onViewport(view, ChargerMapLayer.MIN_ZOOM + 0.5)
        await("draw") { drawn.isNotEmpty() }
        l.onViewport(view, ChargerMapLayer.MIN_ZOOM - 1)
        await("clear") { drawn.size >= 2 }
        assertTrue(drawn.last().isEmpty(), "zooming out clears the pins")
        l.stop()
    }

    @Test fun theFiltersDecideWhatIsDrawn() {
        val store = on(ChargerSettings(sockets = setOf(SocketType.CCS)))
        val l = layer(repo(), store)
        l.onViewport(view, 14.0)
        await("draw") { drawn.isNotEmpty() }
        assertEquals(1, drawn.last().size)
        store.update { it.copy(sockets = SocketType.FILTERABLE, minPower = MinPower.KW_50) }
        await("min power") { drawn.last().size == 2 }
        store.update { it.copy(enabled = false) }
        await("off") { drawn.last().isEmpty() }
        l.stop()
    }

    @Test fun newDataRedrawsAndAnUnchangedResultDoesNotRender() {
        val r = repo(three.take(1))
        val l = layer(r, on())
        l.onViewport(view, 14.0)
        await("draw") { drawn.isNotEmpty() }
        val n = drawn.size
        l.onViewport(view, 14.0)
        settle()
        assertEquals(n, drawn.size, "same viewport, same result: no render call")
        r.install(ChargerDataset(1_700_000_100L, 1, three))
        await("redraw") { drawn.last().size == 3 }
        l.stop()
    }

    @Test fun zoomedOutOnlyTheMostPowerfulStationOfACellSurvives() {
        // Three stations a few metres apart; at zoom 9.5 a cell is several kilometres wide.
        val close = listOf(
            charger("x", 40.5000, -3.5000, slow),
            charger("y", 40.5001, -3.5001, fast),
            charger("z", 40.5002, -3.5002, ChargerSocket(SocketType.CHADEMO, 1, 50.0)),
            charger("far", 40.9, -3.1, slow),
        )
        val kept = ChargerMapLayer.thin(close, 9.5).map { it.id }
        assertEquals(listOf("y", "far"), kept, "the 150 kW station wins its cell and the order of the input is kept")
        assertEquals(close, ChargerMapLayer.thin(close, ChargerMapLayer.THIN_BELOW), "zoomed in nothing is dropped")
    }

    @Test fun theNumberOfPinsIsBoundedByZoom() {
        assertTrue(ChargerMapLayer.limitFor(9.0) < ChargerMapLayer.limitFor(14.0))
        val many = (0 until 2000).map { charger("m$it", 40.0 + it * 0.0004, -3.5, slow) }
        val l = layer(repo(many), on())
        l.onViewport(GeoBounds(40.0, -4.0, 41.0, -3.0), 14.0)
        await("draw") { drawn.isNotEmpty() }
        assertEquals(ChargerMapLayer.limitFor(14.0), drawn.last().size)
        l.stop()
    }
}
