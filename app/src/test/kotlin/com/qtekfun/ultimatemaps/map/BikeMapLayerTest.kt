package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.bikeshare.BikeShareDataset
import com.qtekfun.ultimatemaps.core.bikeshare.BikeShareRepository
import com.qtekfun.ultimatemaps.core.bikeshare.BikeShareSettings
import com.qtekfun.ultimatemaps.core.bikeshare.BikeStation
import com.qtekfun.ultimatemaps.core.bikeshare.BikeSystem
import com.qtekfun.ultimatemaps.core.bikeshare.InMemoryBikeShareSettingsStore
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.BikePin
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

/** The layer's decisions on the JVM: off by default, minimum zoom, thinning, bounded pin count, redraw only on change. */
class BikeMapLayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val system = BikeSystem("bicing", "Bicing", "Credit CC BY 4.0")

    private fun station(id: String, lat: Double, lon: Double, capacity: Int = 20) =
        BikeStation("0:$id", system, id, "S $id", LatLon(lat, lon), capacity)

    private val three = listOf(station("a", 40.1, -3.9), station("b", 40.5, -3.5), station("c", 40.9, -3.1))
    private val view = GeoBounds(40.0, -4.0, 41.0, -3.0)
    private val drawn = CopyOnWriteArrayList<List<BikePin>>()

    private fun repo(list: List<BikeStation> = three) = BikeShareRepository().also { it.install(BikeShareDataset(1_700_000_000L, listOf(system), list)) }
    private fun on() = InMemoryBikeShareSettingsStore(BikeShareSettings(enabled = true))
    private fun layer(r: BikeShareRepository, s: InMemoryBikeShareSettingsStore) =
        BikeMapLayer(scope, Dispatchers.IO, r, s.settings, { drawn += it }, 10).also { it.start() }

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout waiting for $what" }
            Thread.sleep(5)
        }
    }

    private fun settle() = Thread.sleep(150)

    @After fun tearDown() = scope.cancel()

    @Test fun offByDefaultItDrawsNothing() {
        val l = BikeMapLayer(scope, Dispatchers.IO, repo(), InMemoryBikeShareSettingsStore().settings, { drawn += it }, 10).also { it.start() }
        l.onViewport(view, 15.0)
        settle()
        assertTrue(drawn.isEmpty(), "nothing is rendered, not even an empty list")
        l.stop()
    }

    @Test fun theLiveSwitchAloneDrawsNothing() {
        val l = BikeMapLayer(scope, Dispatchers.IO, repo(), InMemoryBikeShareSettingsStore(BikeShareSettings(liveAvailability = true)).settings, { drawn += it }, 10).also { it.start() }
        l.onViewport(view, 15.0)
        settle()
        assertTrue(drawn.isEmpty())
        l.stop()
    }

    @Test fun drawsTheStationsInView() {
        val l = layer(repo(), on())
        l.onViewport(view, 14.5)
        await("draw") { drawn.isNotEmpty() }
        assertEquals(listOf("0:a", "0:b", "0:c"), drawn.last().sortedBy { it.point.lat }.map { it.id })
        l.stop()
    }

    @Test fun belowTheMinimumZoomNothingIsDrawn() {
        val l = layer(repo(), on())
        l.onViewport(view, BikeMapLayer.MIN_ZOOM - 0.5)
        settle()
        assertTrue(drawn.isEmpty())
        l.onViewport(view, BikeMapLayer.MIN_ZOOM + 0.5)
        await("draw") { drawn.isNotEmpty() }
        l.onViewport(view, BikeMapLayer.MIN_ZOOM - 1)
        await("clear") { drawn.size >= 2 }
        assertTrue(drawn.last().isEmpty(), "zooming out clears the pins")
        l.stop()
    }

    @Test fun switchingOffClearsThePins() {
        val store = on()
        val l = layer(repo(), store)
        l.onViewport(view, 14.5)
        await("draw") { drawn.isNotEmpty() }
        store.update { it.copy(enabled = false) }
        await("off") { drawn.last().isEmpty() }
        l.stop()
    }

    @Test fun newDataRedrawsAndAnUnchangedResultDoesNotRender() {
        val r = repo(three.take(1))
        val l = layer(r, on())
        l.onViewport(view, 14.5)
        await("draw") { drawn.isNotEmpty() }
        val n = drawn.size
        l.onViewport(view, 14.5)
        settle()
        assertEquals(n, drawn.size, "same viewport, same result: no render call")
        r.install(BikeShareDataset(1_700_000_100L, listOf(system), three))
        await("redraw") { drawn.last().size == 3 }
        l.stop()
    }

    @Test fun zoomedOutOnlyTheBiggestStationOfACellSurvives() {
        val close = listOf(
            station("x", 40.5000, -3.5000, 10),
            station("y", 40.5001, -3.5001, 40),
            station("z", 40.5002, -3.5002, 20),
            station("far", 40.9, -3.1, 10),
        )
        assertEquals(listOf("y", "far"), BikeMapLayer.thin(close, 11.5).map { it.stationId }, "the biggest wins its cell, input order kept")
        assertEquals(close, BikeMapLayer.thin(close, BikeMapLayer.THIN_BELOW), "zoomed in nothing is dropped")
    }

    @Test fun theNumberOfPinsIsBoundedByZoom() {
        assertTrue(BikeMapLayer.limitFor(12.0) < BikeMapLayer.limitFor(15.0))
        val many = (0 until 3000).map { station("m$it", 40.0 + it * 0.0003, -3.5) }
        val l = layer(repo(many), on())
        l.onViewport(GeoBounds(40.0, -4.0, 41.0, -3.0), 15.0)
        await("draw") { drawn.isNotEmpty() }
        assertEquals(BikeMapLayer.limitFor(15.0), drawn.last().size)
        l.stop()
    }
}
