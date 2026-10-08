package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.GeoBounds
import com.qtekfun.ultimatemaps.core.map.TrailLine
import com.qtekfun.ultimatemaps.core.routes.InMemoryRouteSettingsStore
import com.qtekfun.ultimatemaps.core.routes.RouteRepository
import com.qtekfun.ultimatemaps.core.routes.RouteSettings
import com.qtekfun.ultimatemaps.core.routes.Trail
import com.qtekfun.ultimatemaps.core.routes.TrailDataset
import com.qtekfun.ultimatemaps.core.routes.TrailKind
import com.qtekfun.ultimatemaps.core.routes.TrailLevel
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

/** The layer's decisions on the JVM: off by default, minimum zoom, kinds, redraw only on change; and the style tables. */
class TrailMapLayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun line(lat: Double, lon: Double): IntArray = intArrayOf((lat * 1e6).toInt(), (lon * 1e6).toInt(), ((lat + 0.01) * 1e6).toInt(), ((lon + 0.01) * 1e6).toInt())

    private val data = TrailDataset(
        1_700_000_000L,
        listOf(
            Trail(0, TrailKind.HIKING, TrailLevel.NATIONAL, 0, false, "GR", "GR 1", "", listOf(line(40.2, -3.8))),
            Trail(1, TrailKind.CYCLING, TrailLevel.REGIONAL, 0, false, "", "CV-1", "", listOf(line(40.4, -3.6))),
        ),
    )
    private val view = GeoBounds(40.0, -4.0, 41.0, -3.0)
    private val drawn = CopyOnWriteArrayList<List<TrailLine>>()

    private fun repo() = RouteRepository().also { it.install(data) }

    private fun layer(store: InMemoryRouteSettingsStore) =
        TrailMapLayer(scope, Dispatchers.IO, repo(), store.settings, { drawn += it }, 10).also { it.start() }

    private fun on(s: RouteSettings = RouteSettings()) = InMemoryRouteSettingsStore(s.copy(enabled = true))

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
        val l = TrailMapLayer(scope, Dispatchers.IO, repo(), InMemoryRouteSettingsStore().settings, { drawn += it }, 10).also { it.start() }
        l.onViewport(view, 12.0)
        settle()
        assertTrue(drawn.isEmpty(), "nothing is rendered, not even an empty list")
        l.stop()
    }

    @Test fun drawsTheRoutesInViewWithLevelAndDash() {
        val l = layer(on())
        l.onViewport(view, 12.0)
        await("draw") { drawn.isNotEmpty() }
        val byId = drawn.last().associateBy { it.id }
        assertEquals(setOf(0, 1), byId.keys)
        assertEquals(2, byId.getValue(0).level)
        assertEquals(false, byId.getValue(0).cycling)
        assertEquals(1, byId.getValue(1).level)
        assertEquals(true, byId.getValue(1).cycling)
        l.stop()
    }

    @Test fun belowTheMinimumZoomNothingIsDrawnAndZoomedOutOnlyBigRoutes() {
        val l = layer(on())
        l.onViewport(view, RouteRepository.MIN_ZOOM - 0.5)
        settle()
        assertTrue(drawn.isEmpty())
        l.onViewport(view, 8.0)
        await("draw") { drawn.isNotEmpty() }
        assertEquals(listOf(0), drawn.last().map { it.id }, "zoom 8: national and international only")
        l.stop()
    }

    @Test fun theKindSwitchesAndTheMainSwitchRedrawOnlyWhenTheResultChanges() {
        val store = on()
        val l = layer(store)
        l.onViewport(view, 12.0)
        await("first draw") { drawn.size == 1 }
        l.onViewport(view, 12.0)
        settle()
        assertEquals(1, drawn.size, "same viewport and data: no redraw")
        store.update { it.copy(cycling = false) }
        await("walking only") { drawn.size == 2 }
        assertEquals(listOf(0), drawn.last().map { it.id })
        store.update { it.copy(enabled = false) }
        await("removed") { drawn.size == 3 }
        assertTrue(drawn.last().isEmpty(), "switching off removes the lines")
        l.stop()
    }

    @Test fun styleTables() {
        assertEquals(4, TrailStyle.LEVEL_COLORS.size)
        assertEquals(4, TrailStyle.LEVEL_COLORS.toSet().size, "one colour per level")
        assertEquals(TrailStyle.LEVEL_COLORS[3], TrailStyle.colorOf(99), "levels are clamped")
        assertEquals(TrailStyle.LEVEL_COLORS[0], TrailStyle.colorOf(-1))
        assertTrue(!TrailStyle.HIKING_DASH.contentEquals(TrailStyle.CYCLING_DASH), "walking and cycling differ by dash, not only by colour")
        assertTrue(TrailStyle.WIDTH_STOPS.zipWithNext().all { (a, b) -> a.first < b.first && a.second < b.second })
        val l = TrailLine(7, 2, true, listOf(LatLon(1.0, 1.0), LatLon(1.1, 1.1)))
        assertEquals(mapOf("id" to 7, "level" to 2, "cycling" to true), TrailStyle.properties(l))
        assertEquals(listOf(l), TrailStyle.drawable(listOf(l, TrailLine(8, 0, false, listOf(LatLon(1.0, 1.0))))))
    }
}
