package com.qtekfun.ultimatemaps.search

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.search.SearchEngine
import com.qtekfun.ultimatemaps.core.search.SearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The explicit search (keyboard search key): runs at once, shows progress, ends in results, empty or error. Virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchSubmitTest {
    private class FakeEngine(var answer: (String) -> List<SearchResult> = { listOf(hit(it)) }) : SearchEngine {
        val queries = mutableListOf<String>()
        override fun search(query: String, near: LatLon?, limit: Int): List<SearchResult> {
            queries += query
            return answer(query)
        }
        override fun close() = Unit
    }

    private object NoLog : SearchLog {
        override fun engineReady(millis: Long, regionCount: Int) = Unit
        override fun searched(queryLength: Int, results: Int, millis: Long, firstSinceReady: Boolean) = Unit
        override fun failed(kind: String) = Unit
    }

    private val maps = CoreMaps(File("/maps"), 1)

    private fun TestScope.coordinator(engine: SearchEngine, regions: CoreMaps? = maps): SearchCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SearchCoordinator(
            scope = this, io = dispatcher, regions = object : InstalledRegions { override fun coreMaps() = regions },
            backend = { engine }, near = { LatLon(40.0, -3.0) }, clock = { 0L }, log = NoLog,
            debounceMs = 250,
        )
    }

    @Test
    fun submitRunsAtOnceWithoutWaitingForTheDebounce() = runTest {
        val engine = FakeEngine()
        val c = coordinator(engine)
        c.onQueryChange("calle mayor")
        assertTrue(c.submit())
        assertTrue(c.state.submitted)
        assertEquals(SearchStatus.PREPARING, c.state.status) // cold core: visible feedback before any work
        runCurrent() // no virtual time passes
        assertEquals(listOf("calle mayor"), engine.queries)
        assertEquals(SearchStatus.DONE, c.state.status)
        assertFalse(c.state.submitted)
        // The cancelled debounced job does not run the same query a second time.
        advanceUntilIdle()
        assertEquals(1, engine.queries.size)
    }

    @Test
    fun submitShowsSearchingWhenTheCoreIsWarm() = runTest {
        val c = coordinator(FakeEngine())
        c.onQueryChange("a")
        advanceUntilIdle()
        assertEquals(SearchStatus.DONE, c.state.status)
        c.onQueryChange("ab")
        c.submit()
        assertEquals(SearchStatus.SEARCHING, c.state.status)
        assertTrue(c.state.submitted)
        runCurrent()
        assertEquals("ab", c.state.results.single().name)
    }

    @Test
    fun noMatchEndsInDoneWithEmptyResults() = runTest {
        val c = coordinator(FakeEngine { emptyList() })
        c.onQueryChange("zzzz")
        c.submit()
        runCurrent()
        assertEquals(SearchStatus.DONE, c.state.status)
        assertTrue(c.state.results.isEmpty())
    }

    @Test
    fun failureEndsInErrorAndSubmitRetries() = runTest {
        val engine = FakeEngine { throw IllegalStateException("boom") }
        val c = coordinator(engine)
        c.onQueryChange("calle")
        c.submit()
        runCurrent()
        assertEquals(SearchStatus.ERROR, c.state.status)
        engine.answer = { listOf(hit(it)) }
        c.submit()
        runCurrent()
        assertEquals(SearchStatus.DONE, c.state.status)
        assertEquals(2, engine.queries.size)
    }

    @Test
    fun missingMapsEndInNoRegionsNotInSilence() = runTest {
        val c = coordinator(FakeEngine(), regions = null)
        c.onQueryChange("calle")
        c.submit()
        runCurrent()
        assertEquals(SearchStatus.NO_REGIONS, c.state.status)
        assertFalse(c.state.submitted)
    }

    @Test
    fun blankTextAndTypedPositionsHaveNothingToSubmit() = runTest {
        val engine = FakeEngine()
        val c = coordinator(engine)
        assertFalse(c.submit())
        c.onQueryChange("   ")
        assertFalse(c.submit())
        c.onQueryChange("40.4168, -3.7038")
        assertTrue(c.state.coordinateQuery)
        assertFalse(c.submit())
        advanceUntilIdle()
        assertTrue(engine.queries.isEmpty())
        assertEquals(SearchStatus.DONE, c.state.status)
    }

    @Test
    fun aKeystrokeAfterSubmitDropsTheSubmittedFlagAndKeepsLiveTypingDebounced() = runTest {
        val engine = FakeEngine()
        val c = coordinator(engine)
        c.onQueryChange("cal")
        c.submit()
        c.onQueryChange("call") // before the submitted query ran
        assertFalse(c.state.submitted)
        advanceTimeBy(249)
        assertTrue(engine.queries.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("call"), engine.queries)
    }

    private companion object {
        fun hit(name: String) = SearchResult(name, LatLon(40.0, -3.0), "addr", "cafe")
    }
}
