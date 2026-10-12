package com.qtekfun.ultimatemaps.search

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.search.OnlineGeocoder
import com.qtekfun.ultimatemaps.core.search.OnlineOutcome
import com.qtekfun.ultimatemaps.core.search.SearchEngine
import com.qtekfun.ultimatemaps.core.search.SearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The optional online fallback of the search (play flavor): when it is asked, in what order the results appear, what it
 * sends, and when it must stay silent. Virtual time only: no threads, no sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineFallbackTest {
    private class FakeEngine(var answer: (String) -> List<SearchResult> = { emptyList() }) : SearchEngine {
        val queries = mutableListOf<String>()
        override fun search(query: String, near: LatLon?, limit: Int): List<SearchResult> {
            queries += query
            return answer(query)
        }
        override fun close() = Unit
    }

    private class FakeOnline(
        override var enabled: Boolean = true,
        var latencyMs: Long = 100,
        var outcome: (String) -> OnlineOutcome = { OnlineOutcome.Found(listOf(onlineHit("Cafe $it"))) },
    ) : OnlineGeocoder {
        val calls = mutableListOf<Triple<String, LatLon?, Int>>()
        override suspend fun search(query: String, near: LatLon?, limit: Int): OnlineOutcome {
            calls += Triple(query, near, limit)
            delay(latencyMs)
            return outcome(query)
        }
    }

    private object NoLog : SearchLog {
        override fun engineReady(millis: Long, regionCount: Int) = Unit
        override fun searched(queryLength: Int, results: Int, millis: Long, firstSinceReady: Boolean) = Unit
        override fun failed(kind: String) = Unit
    }

    private val maps = CoreMaps(File("/maps"), 1)
    private val pins = mutableListOf<List<LatLon>>()

    private fun TestScope.coordinator(engine: SearchEngine, online: OnlineGeocoder): SearchCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SearchCoordinator(
            scope = this, io = dispatcher, regions = object : InstalledRegions { override fun coreMaps() = maps },
            backend = { engine }, near = { LatLon(40.4168, -3.7038) }, clock = { 0L }, log = NoLog,
            debounceMs = 250, online = online, onlineDebounceMs = 900, onlineLimit = 8,
            onOnlineResults = { pins += it },
        )
    }

    @Test
    fun offlineHitsNeverGoOnline() = runTest {
        val online = FakeOnline()
        val c = coordinator(FakeEngine { listOf(hit(it)) }, online)
        c.onQueryChange("plaza mayor")
        advanceUntilIdle()
        assertTrue(online.calls.isEmpty())
        assertEquals(listOf("plaza mayor"), c.state.results.map { it.name })
        assertFalse(c.state.results.single().online)
    }

    @Test
    fun anEmptyOfflineAnswerAsksOnlineAfterTheExtraDebounceAndListsTheResultsMarked() = runTest {
        val online = FakeOnline()
        val c = coordinator(FakeEngine(), online)
        c.onQueryChange("cafe luna")
        advanceTimeBy(250); runCurrent() // the offline debounce passed and the offline search settled with nothing
        assertEquals(SearchStatus.DONE, c.state.status)
        assertTrue(c.state.results.isEmpty())
        assertTrue(c.state.onlineSearching, "the panel says it is searching online, not 'No results'")
        assertTrue(online.calls.isEmpty(), "nothing is sent before the extra debounce")
        advanceTimeBy(900); runCurrent()
        assertEquals(1, online.calls.size)
        assertTrue(c.state.onlineSearching)
        advanceUntilIdle()
        assertFalse(c.state.onlineSearching)
        assertEquals(listOf("Cafe cafe luna"), c.state.results.map { it.name })
        assertTrue(c.state.results.single().online)
        assertEquals(listOf(listOf(c.state.results.single().point)), pins)
        // The only inputs are the text and the map centre (the geocoder rounds it); the limit is the configured one.
        assertEquals(Triple("cafe luna", LatLon(40.4168, -3.7038), 8), online.calls.single())
    }

    @Test
    fun aSubmittedSearchGoesOnlineAtOnceAfterTheOfflineOne() = runTest {
        val online = FakeOnline()
        val c = coordinator(FakeEngine(), online)
        c.onQueryChange("cafe luna")
        c.submit()
        runCurrent()
        assertEquals(1, online.calls.size, "no extra debounce when the user asked")
        assertEquals(0L, currentTime)
        advanceUntilIdle()
        assertEquals(1, c.state.results.size)
    }

    @Test
    fun theOfflineResultsAreShownBeforeTheOnlineAnswerArrives() = runTest {
        val online = FakeOnline(latencyMs = 5_000)
        val engine = FakeEngine { q -> if (q.endsWith(" 2")) listOf(SearchResult("Calle Mayor", LatLon(40.0, -3.0))) else emptyList() }
        val c = coordinator(engine, online)
        c.onQueryChange("calle mayor 2")
        c.submit()
        runCurrent()
        assertEquals(listOf("Calle Mayor"), c.state.results.map { it.name }, "the street is there while the lookup runs")
        assertEquals("2", c.state.numberMissing)
        assertTrue(c.state.onlineSearching)
        advanceUntilIdle()
        // A missing number: the online answer (which may have the number) is listed first, the streets after.
        assertEquals(listOf("Cafe calle mayor 2", "Calle Mayor"), c.state.results.map { it.name })
        assertEquals(listOf(true, false), c.state.results.map { it.online })
    }

    @Test
    fun anOnlineResultThatRepeatsAnOfflineOneIsDropped() = runTest {
        val here = SearchResult("Cafe Luna", LatLon(40.4168, -3.7038))
        val online = FakeOnline(outcome = { OnlineOutcome.Found(listOf(onlineHit("Cafe Luna", 40.4169, -3.7039), onlineHit("Cafe Sol"))) })
        // A number the offline hit does not mention, so the fallback runs and the repeat must be dropped.
        val c = coordinator(FakeEngine { listOf(here) }, online)
        c.onQueryChange("cafe luna 7")
        c.submit()
        advanceUntilIdle()
        // The repeat of the offline hit is dropped; the new online place comes first (the offline one lacks the number).
        assertEquals(listOf("Cafe Sol", "Cafe Luna"), c.state.results.map { it.name })
        assertTrue(c.state.results.first().online)
        assertFalse(c.state.results.last().online)
    }

    @Test
    fun aNewKeystrokeCancelsTheLookupAndClearsItsPins() = runTest {
        val online = FakeOnline(latencyMs = 2_000)
        val c = coordinator(FakeEngine(), online)
        c.onQueryChange("cafe luna")
        advanceTimeBy(250 + 900 + 2_100); runCurrent()
        assertEquals(1, c.state.results.size)
        assertEquals(1, pins.size)
        c.onQueryChange("cafe lun")
        assertEquals(listOf(emptyList<LatLon>()), pins.drop(1), "the online pins of the previous text are cleared")
        advanceTimeBy(250); runCurrent()
        c.onQueryChange("cafe lu") // typed again before the extra debounce ended: nothing was sent for "cafe lun"
        advanceUntilIdle()
        assertEquals(listOf("cafe luna", "cafe lu"), online.calls.map { it.first })
    }

    @Test
    fun theSwitchOffOrOfflineModeSendsNothing() = runTest {
        val online = FakeOnline(enabled = false)
        val c = coordinator(FakeEngine(), online)
        c.onQueryChange("cafe luna")
        c.submit()
        advanceUntilIdle()
        assertTrue(online.calls.isEmpty())
        assertEquals(SearchStatus.DONE, c.state.status)
        assertTrue(c.state.results.isEmpty())
        assertFalse(c.state.onlineSearching)
        assertFalse(c.state.onlineFailed)
    }

    @Test
    fun aFailureLeavesTheOfflineAnswerAndSaysSo() = runTest {
        val online = FakeOnline(outcome = { OnlineOutcome.Failed })
        val c = coordinator(FakeEngine(), online)
        c.onQueryChange("cafe luna")
        c.submit()
        advanceUntilIdle()
        assertTrue(c.state.results.isEmpty())
        assertTrue(c.state.onlineFailed)
        assertFalse(c.state.onlineSearching)
        assertEquals(SearchStatus.DONE, c.state.status)
        // The next text starts clean.
        c.onQueryChange("cafe luna 2")
        assertFalse(c.state.onlineFailed)
    }

    @Test
    fun shortTextCategoriesAndTypedPositionsStayOffline() = runTest {
        val online = FakeOnline()
        val c = coordinator(FakeEngine(), online)
        c.onQueryChange("ab")
        c.submit()
        advanceUntilIdle()
        c.onQueryChange("40.4168, -3.7038")
        advanceUntilIdle()
        c.browseCategory(PlaceCategory.PHARMACY, "pharmacy")
        advanceUntilIdle()
        assertTrue(online.calls.isEmpty())
    }

    @Test
    fun nothingFoundOnlineEitherIsJustNoResults() = runTest {
        val online = FakeOnline(outcome = { OnlineOutcome.Found(emptyList()) })
        val c = coordinator(FakeEngine(), online)
        c.onQueryChange("zzzz")
        c.submit()
        advanceUntilIdle()
        assertTrue(c.state.results.isEmpty())
        assertFalse(c.state.onlineFailed)
        assertFalse(c.state.onlineSearching)
        assertTrue(pins.isEmpty())
    }

    private companion object {
        fun hit(q: String) = SearchResult(q, LatLon(40.0, -3.0))
        fun onlineHit(name: String, lat: Double = 41.0, lon: Double = -4.0) = SearchResult(name, LatLon(lat, lon), online = true)
    }
}
