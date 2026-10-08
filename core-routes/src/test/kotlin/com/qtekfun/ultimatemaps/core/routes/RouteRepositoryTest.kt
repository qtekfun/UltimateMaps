package com.qtekfun.ultimatemaps.core.routes

import com.qtekfun.ultimatemaps.core.cameras.LatLonBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteRepositoryTest {
    private fun repo(): RouteRepository = RouteRepository().also {
        it.install(RouteFile.parse(javaClass.getResourceAsStream("/routes/sample.bin")!!.readBytes()))
    }

    private val spain = LatLonBounds(42.0, -5.0, 44.0, -3.0)
    private val on = RouteSettings(enabled = true)

    @Test fun zoomChoosesTheLevelsDrawn() {
        val r = repo()
        assertEquals(setOf(0, 3), r.piecesIn(spain, 8.0, on).map { it.trail.id }.toSet(), "zoom 8: national and international")
        assertEquals(setOf(0, 1, 3), r.piecesIn(spain, 10.0, on).map { it.trail.id }.toSet(), "zoom 10: regional and up")
        assertEquals(setOf(0, 1, 2, 3), r.piecesIn(spain, 12.0, on).map { it.trail.id }.toSet(), "zoom 12: everything")
    }

    @Test fun kindFilterHidesWalkingOrBikes() {
        val r = repo()
        val bikesOnly = r.piecesIn(spain, 12.0, on.copy(hiking = false)).map { it.trail.kind }.toSet()
        assertEquals(setOf(TrailKind.CYCLING, TrailKind.MTB), bikesOnly)
        val walkOnly = r.piecesIn(spain, 12.0, on.copy(cycling = false)).map { it.trail.kind }.toSet()
        assertEquals(setOf(TrailKind.HIKING), walkOnly)
    }

    @Test fun onlyWhatTouchesTheViewportIsReturned() {
        val r = repo()
        // A small window around the first point of "Ruta de los Molinos" (43.30, -4.20)
        val window = LatLonBounds(43.295, -4.205, 43.305, -4.195)
        val ids = r.piecesIn(window, 14.0, on).map { it.trail.id }.toSet()
        assertEquals(setOf(2), ids)
        assertTrue(r.piecesIn(LatLonBounds(10.0, 10.0, 11.0, 11.0), 14.0, on).isEmpty())
    }

    @Test fun aLongLineCrossingTheViewportWithoutAVertexInsideIsKept() {
        val line = intArrayOf(43_000_000, -5_000_000, 43_000_000, -3_000_000) // two vertices far apart
        val t = Trail(0, TrailKind.HIKING, TrailLevel.NATIONAL, 0, false, "x", "", "", listOf(line))
        val r = RouteRepository().also { it.install(TrailDataset(1, listOf(t))) }
        val pieces = r.piecesIn(LatLonBounds(42.99, -4.01, 43.01, -3.99), 12.0, on)
        assertEquals(1, pieces.size)
        assertEquals(2, pieces[0].points.size)
    }

    @Test fun thinningKeepsTheEndsAndTheBudgetCutsLowLevelsFirst() {
        val n = 101
        val line = IntArray(n * 2) { if (it % 2 == 0) 43_000_000 + it * 100 else -4_000_000 + it * 100 }
        val hi = Trail(0, TrailKind.HIKING, TrailLevel.INTERNATIONAL, 0, false, "hi", "", "", listOf(line))
        val lo = Trail(1, TrailKind.HIKING, TrailLevel.LOCAL, 0, false, "lo", "", "", listOf(line))
        val r = RouteRepository().also { it.install(TrailDataset(1, listOf(hi, lo))) }
        val all = LatLonBounds(42.0, -5.0, 44.0, -3.0)
        val far = r.piecesIn(all, 8.0, on)
        assertEquals(1, far.size)
        assertEquals(26, far[0].points.size, "every 4th of 101 points plus the last")
        assertEquals(line[0] / 1e6, far[0].points.first().lat, 1e-9)
        assertEquals(line[(n - 1) * 2] / 1e6, far[0].points.last().lat, 1e-9)
        val tight = r.piecesIn(all, 13.0, on, maxPoints = 150)
        assertEquals(listOf(0, 1), tight.map { it.trail.id }, "the second still fits: the budget is checked before adding")
        val tighter = r.piecesIn(all, 13.0, on, maxPoints = 50)
        assertEquals(listOf(0), tighter.map { it.trail.id }, "budget spent on the highest level")
    }

    @Test fun installAndLookup() {
        val r = repo()
        assertNotNull(r.generatedMillis.value)
        assertEquals("GR 1", r.trail(0)?.ref)
        assertNull(r.trail(99))
        r.install(null)
        assertNull(r.generatedMillis.value)
        assertTrue(r.data.trails.isEmpty())
    }

    @Test fun settingsNeverHideEverything() {
        assertEquals(RouteSettings(enabled = true), RouteSettings(enabled = true, hiking = false, cycling = false).normalized())
        val store = InMemoryRouteSettingsStore()
        assertEquals(false, store.settings.value.enabled, "off by default")
        store.update { it.copy(hiking = false) }
        assertEquals(RouteSettings(enabled = false, hiking = false, cycling = true), store.settings.value)
        store.update { it.copy(cycling = false) }
        assertEquals(RouteSettings(), store.settings.value)
    }
}
