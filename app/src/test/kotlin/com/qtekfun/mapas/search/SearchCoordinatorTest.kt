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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class FakeEngine(val onSearch: (String) -> List<SearchResult> = { listOf(hit(it)) }) : SearchEngine {
        val queries = CopyOnWriteArrayList<String>()
        override fun search(query: String, near: LatLon?, limit: Int): List<SearchResult> {
            queries += query
            return onSearch(query)
        }
        override fun close() = Unit
    }

    private class FakeLog : SearchLog {
        val events = CopyOnWriteArrayList<String>()
        override fun engineReady(millis: Long, regionCount: Int) { events += "ready ms=$millis regions=$regionCount" }
        override fun searched(queryLength: Int, results: Int, millis: Long, firstSinceReady: Boolean) {
            events += "search qlen=$queryLength results=$results ms=$millis first=$firstSinceReady"
        }
        override fun failed(kind: String) { events += "failed $kind" }
    }

    private class FakeRegions(var maps: CoreMaps?) : InstalledRegions {
        override fun coreMaps() = maps
    }

    private val installed = CoreMaps(File("/maps"), 2)
    private val opens = AtomicInteger()
    private val ticks = AtomicLong()
    private val log = FakeLog()

    private fun coordinator(regions: InstalledRegions, engine: SearchEngine) = SearchCoordinator(
        scope, Dispatchers.IO, regions,
        backend = { opens.incrementAndGet(); engine },
        near = { LatLon(40.0, -3.0) },
        clock = { ticks.addAndGet(7) },
        log = log,
        debounceMs = 40,
    )

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) {
            check(System.nanoTime() < end) { "timeout waiting for $what" }
            Thread.sleep(5)
        }
    }

    @After fun tearDown() = scope.cancel()

    @Test
    fun coreIsStartedLazilyOnTheFirstQueryAndOnlyOnce() {
        val engine = FakeEngine()
        val c = coordinator(FakeRegions(installed), engine)
        c.refreshRegions()
        await("scan") { c.state.regionsAvailable == true }
        assertEquals(0, opens.get())

        c.onQueryChange("calle")
        await("first result") { c.state.status == SearchStatus.DONE }
        c.onQueryChange("calle mayor")
        await("second result") { c.state.results.firstOrNull()?.name == "calle mayor" }
        assertEquals(1, opens.get())
        assertEquals(listOf("calle", "calle mayor"), engine.queries.toList())
    }

    @Test
    fun aNewKeystrokeCancelsThePreviousDebouncedQuery() {
        val engine = FakeEngine()
        val c = coordinator(FakeRegions(installed), engine)
        c.onQueryChange("c")
        c.onQueryChange("ca")
        c.onQueryChange("cal")
        await("result") { c.state.status == SearchStatus.DONE }
        Thread.sleep(150)
        assertEquals(listOf("cal"), engine.queries.toList())
    }

    @Test
    fun aSlowQueryOvertakenByANewOneNeverShowsItsResults() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = FakeEngine { q ->
            if (q == "slow") { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            listOf(hit(q))
        }
        val c = coordinator(FakeRegions(installed), engine)
        c.onQueryChange("slow")
        assertTrue(started.await(5, TimeUnit.SECONDS))
        c.onQueryChange("fast")
        release.countDown()
        await("fast result") { c.state.status == SearchStatus.DONE }
        assertEquals(listOf("fast"), c.state.results.map { it.name })
        Thread.sleep(100)
        assertEquals(listOf("fast"), c.state.results.map { it.name })
    }

    @Test
    fun noInstalledRegionsGivesTheEmptyStateAndNeverStartsTheCore() {
        val regions = FakeRegions(null)
        val c = coordinator(regions, FakeEngine())
        c.refreshRegions()
        await("scan") { c.state.regionsAvailable == false }
        c.onQueryChange("madrid")
        await("empty state") { c.state.status == SearchStatus.NO_REGIONS }
        assertEquals(0, opens.get())

        regions.maps = installed // the regions module installed something
        c.refreshRegions()
        await("results after install") { c.state.status == SearchStatus.DONE }
        assertEquals(true, c.state.regionsAvailable)
        assertEquals(1, opens.get())
    }

    @Test
    fun clearingTheQueryClearsTheResults() {
        val c = coordinator(FakeRegions(installed), FakeEngine())
        c.onQueryChange("sol")
        await("result") { c.state.status == SearchStatus.DONE }
        c.onQueryChange("  ")
        assertEquals(emptyList(), c.state.results)
        assertEquals(SearchStatus.IDLE, c.state.status)
    }

    @Test
    fun latencyIsLoggedWithLengthsAndMillisecondsOnly() {
        val c = coordinator(FakeRegions(installed), FakeEngine())
        c.onQueryChange("mi casa secreta")
        await("result") { c.state.status == SearchStatus.DONE }
        assertEquals(
            listOf("ready ms=7 regions=2", "search qlen=15 results=1 ms=7 first=true"),
            log.events.toList(),
        )
        assertTrue(log.events.none { "casa" in it || "40.0" in it })
    }

    @Test
    fun anEngineFailureShowsAnErrorAndLogsOnlyTheType() {
        val c = coordinator(FakeRegions(installed), FakeEngine { error("boom with /private/path") })
        c.onQueryChange("x")
        await("error") { c.state.status == SearchStatus.ERROR }
        assertEquals(listOf("ready ms=7 regions=2", "failed IllegalStateException"), log.events.toList())
    }

    companion object {
        fun hit(name: String) = SearchResult(name, LatLon(40.0, -3.0), "addr", "cafe")
    }
}
