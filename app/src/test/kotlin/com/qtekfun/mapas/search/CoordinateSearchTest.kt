package com.qtekfun.mapas.search

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.search.SearchEngine
import com.qtekfun.mapas.core.search.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A typed position is answered by the coordinator itself: at once, with no engine and no installed region. */
class CoordinateSearchTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opens = AtomicInteger()

    private val engine = object : SearchEngine {
        override fun search(query: String, near: LatLon?, limit: Int) = emptyList<SearchResult>()
        override fun close() = Unit
    }

    private val mapCentre = LatLon(51.3701125, -1.217765625)

    private fun coordinator() = SearchCoordinator(
        scope, Dispatchers.IO, regions = object : InstalledRegions { override fun coreMaps(): CoreMaps? = null },
        backend = { opens.incrementAndGet(); engine },
        near = { mapCentre },
        clock = { 0L },
        log = object : SearchLog {
            override fun engineReady(millis: Long, regionCount: Int) = Unit
            override fun searched(queryLength: Int, results: Int, millis: Long, firstSinceReady: Boolean) = Unit
            override fun failed(kind: String) = Unit
        },
        debounceMs = 10,
        coordinateLabels = { kind -> "label:${kind.name}" },
    )

    @After fun tearDown() = scope.cancel()

    @Test fun decimalCoordinatesGiveOneResultImmediatelyWithoutTheEngine() {
        val c = coordinator()
        c.onQueryChange("40.4168, -3.7038")
        val s = c.state
        assertEquals(SearchStatus.DONE, s.status)
        assertTrue(s.coordinateQuery)
        val r = s.results.single()
        assertEquals("40.41680, -3.70380", r.name)
        assertEquals(40.4168, r.point.lat, 1e-9)
        assertEquals(-3.7038, r.point.lon, 1e-9)
        assertEquals("label:DECIMAL", r.category)
        assertEquals(0, opens.get())
    }

    @Test fun degreesMinutesSecondsToo() {
        val c = coordinator()
        c.onQueryChange("40°26'46\"N 3°42'14\"W")
        val r = c.state.results.single()
        assertEquals(40 + 26 / 60.0 + 46 / 3600.0, r.point.lat, 1e-9)
        assertEquals(-(3 + 42 / 60.0 + 14 / 3600.0), r.point.lon, 1e-9)
        assertEquals("label:DMS", r.category)
    }

    @Test fun aFullPlusCodeIsShownAsTheCode() {
        val c = coordinator()
        c.onQueryChange("8fvc2222+22")
        val r = c.state.results.single()
        assertEquals("8FVC2222+22", r.name)
        assertEquals(47.0000625, r.point.lat, 1e-9)
        assertEquals("label:PLUS_CODE", r.category)
    }

    @Test fun aShortPlusCodeIsCompletedNearTheMapCentre() {
        val c = coordinator()
        c.onQueryChange("9QCJ+2VX")
        val r = c.state.results.single()
        assertEquals("9C3W9QCJ+2VX", r.name) // the published short-code vector for this reference point
        assertEquals("label:PLUS_CODE_SHORT", r.category)
    }

    @Test fun aCoordinateQueryIsFlaggedSoItIsNotStoredInTheHistory() {
        val c = coordinator()
        c.onQueryChange("40.4168, -3.7038")
        assertTrue(c.state.coordinateQuery)
        c.onQueryChange("Madrid")
        assertFalse(c.state.coordinateQuery)
        c.onQueryChange("")
        assertFalse(c.state.coordinateQuery)
    }

    @Test fun clearingTheFieldClearsTheCoordinateResult() {
        val c = coordinator()
        c.onQueryChange("40.4168, -3.7038")
        c.onQueryChange("")
        assertTrue(c.state.results.isEmpty())
    }

    @Test fun ordinaryTextIsNotTreatedAsAPosition() {
        val c = coordinator()
        c.onQueryChange("Calle Mayor 5")
        assertFalse(c.state.coordinateQuery)
        assertNull(c.state.results.firstOrNull())
        assertEquals(0, opens.get()) // the engine is only opened after the debounce, off the main thread
    }

    @Test fun theHistoryIsNotWrittenForPositions() {
        // PanelHost.pick records the query only when !coordinateQuery; check the source keeps that guard.
        val host = File("src/main/kotlin/com/qtekfun/mapas/search/PanelHost.kt").readText()
        assertTrue("if (!search.state.coordinateQuery) history.record(" in host)
    }
}
