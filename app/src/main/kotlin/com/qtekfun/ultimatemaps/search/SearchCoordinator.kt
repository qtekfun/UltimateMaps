package com.qtekfun.ultimatemaps.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.ultimatemaps.core.geo.CoordinateQuery
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.search.NoOnlineGeocoder
import com.qtekfun.ultimatemaps.core.search.OnlineGeocoder
import com.qtekfun.ultimatemaps.core.search.OnlineOutcome
import com.qtekfun.ultimatemaps.core.search.OnlineSearch
import com.qtekfun.ultimatemaps.core.search.SearchEngine
import com.qtekfun.ultimatemaps.core.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SearchStatus { IDLE, NO_REGIONS, PREPARING, SEARCHING, DONE, ERROR }

/** Observable search state. Written from the main thread only. */
class SearchState {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SearchResult>>(emptyList())
    var status by mutableStateOf(SearchStatus.IDLE)

    /** The category being browsed (its results are in [results], nearest first); null while searching by text. */
    var category by mutableStateOf<PlaceCategory?>(null)

    /** null until the first scan of the installed regions has finished. */
    var regionsAvailable by mutableStateOf<Boolean?>(null)

    /** The text is a position (coordinates or a Plus Code), answered without the search engine. */
    var coordinateQuery by mutableStateOf(false)

    /** The house number typed that no result has (the map data lacks that address); null otherwise. See `HouseNumberHint`. */
    var numberMissing by mutableStateOf<String?>(null)

    /**
     * The user asked for this search (search key of the keyboard, or the field's action) instead of the live
     * as-you-type one. While true and the status is PREPARING or SEARCHING the panel shows progress even if older results
     * are still listed; it is cleared by the next keystroke and by every outcome.
     */
    var submitted by mutableStateOf(false)

    /** The optional online fallback is looking the text up (play flavor, switch on); the panel says so instead of "No results". */
    var onlineSearching by mutableStateOf(false)

    /** The online fallback was tried and failed (network, server or offline mode changed); the offline answer stands. */
    var onlineFailed by mutableStateOf(false)
}

/** Labels of the result row for a typed position ("Coordinates", "Plus Code"); localized by the host. */
fun interface CoordinateLabels {
    fun label(kind: CoordinateQuery.Kind): String
}

/** Opens the engine on top of the installed maps. Blocking and heavy: always called off the main thread. */
fun interface SearchBackend {
    /** Called again after the installed regions change; implementations must reuse the native core. */
    fun open(maps: CoreMaps): SearchEngine
}

/** Latency record for the future on-device test. Must never receive queries or positions. */
interface SearchLog {
    /** Core start-up (or re-scan) on [regionCount] regions took [millis]. */
    fun engineReady(millis: Long, regionCount: Int)

    /** One search finished: only the query length is recorded, never its text. */
    fun searched(queryLength: Int, results: Int, millis: Long, firstSinceReady: Boolean)

    fun failed(kind: String)
}

/**
 * Lazy search: the native core is only started on the first query (or when regions change), on [io],
 * and queries are debounced; a new keystroke cancels the previous one. Native calls are serialised
 * (one core per process) so a cancelled query never overlaps the next.
 */
class SearchCoordinator(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val regions: InstalledRegions,
    private val backend: SearchBackend,
    private val near: () -> LatLon?,
    private val clock: () -> Long,
    private val log: SearchLog,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val limit: Int = 20,
    /** Serialises native calls; share it with the routing so the one core is never entered twice. */
    private val mutex: Mutex = Mutex(),
    /** Where category results are measured from (the user if known, else the map center); defaults to [near]. */
    private val categoryOrigin: () -> LatLon? = near,
    /** Receives the positions of the category results (empty when they are cleared) to draw them on the map. */
    private val onCategoryResults: (List<LatLon>) -> Unit = {},
    private val coordinateLabels: CoordinateLabels = CoordinateLabels { "" },
    /**
     * The optional online fallback (play flavor, off by default). Asked only after the offline search found nothing, or only
     * streets for a typed house number; never while a category is browsed and never for a typed position.
     */
    private val online: OnlineGeocoder = NoOnlineGeocoder,
    /** Extra wait before an as-you-type search goes online (a submitted one goes at once), so a text is not sent half typed. */
    private val onlineDebounceMs: Long = ONLINE_DEBOUNCE_MS,
    private val onlineLimit: Int = ONLINE_LIMIT,
    /** Receives the positions of the online results (empty when they are cleared) to pin them on the map. */
    private val onOnlineResults: (List<LatLon>) -> Unit = {},
) {
    val state = SearchState()

    private var job: Job? = null
    private var engine: SearchEngine? = null
    private var dirty = true
    private var firstSinceReady = true

    /** Re-reads the installed regions (call on start and after the regions screen changes something). */
    fun refreshRegions() {
        scope.launch {
            val available = withContext(io) { mutex.withLock { regions.coreMaps() != null } }
            state.regionsAvailable = available
            dirty = true
            if (!available) {
                state.results = emptyList()
                if (state.query.isNotBlank()) state.status = SearchStatus.NO_REGIONS
            } else if (state.status == SearchStatus.NO_REGIONS) {
                state.status = SearchStatus.IDLE
                if (state.query.isNotBlank()) onQueryChange(state.query)
            }
        }
    }

    /**
     * Lists the places of [category] nearest first. [categoryName] is the category name in the UI language that the core
     * searches as a pure category (see [PlaceCategory.query]). Replaces any text query and shows the results on the map.
     */
    fun browseCategory(category: PlaceCategory, categoryName: String) {
        job?.cancel()
        clearOnline()
        state.submitted = false
        state.query = ""
        state.category = category
        state.results = emptyList()
        onCategoryResults(emptyList())
        val origin = categoryOrigin()
        job = scope.launch {
            val cold = engine == null || dirty
            state.status = if (cold) SearchStatus.PREPARING else SearchStatus.SEARCHING
            val outcome = withContext(io) {
                mutex.withLock { run(categoryName) { it.searchCategory(categoryName, origin, limit) } }
            }
            when (outcome) {
                is Outcome.Found -> {
                    val sorted = sortedByDistance(outcome.list, origin)
                    state.results = sorted
                    state.status = SearchStatus.DONE
                    onCategoryResults(sorted.map { it.point })
                }
                Outcome.NoRegions -> {
                    state.regionsAvailable = false
                    state.results = emptyList()
                    state.status = SearchStatus.NO_REGIONS
                }
                Outcome.Failed -> {
                    state.results = emptyList()
                    state.status = SearchStatus.ERROR
                }
            }
        }
    }

    /** Leaves category browsing: empties the list and removes the pins. */
    fun clearCategory() {
        if (state.category == null) return
        job?.cancel()
        state.submitted = false
        state.category = null
        state.results = emptyList()
        state.status = if (state.regionsAvailable == false) SearchStatus.NO_REGIONS else SearchStatus.IDLE
        onCategoryResults(emptyList())
    }

    fun onQueryChange(query: String) {
        clearOnline()
        if (state.category != null) {
            state.category = null
            onCategoryResults(emptyList())
        }
        state.query = query
        state.numberMissing = null
        state.submitted = false
        job?.cancel()
        // A typed position (coordinates, Plus Code) is answered here, offline and at once, without the engine.
        // The map centre only completes a short Plus Code; it is not stored or logged.
        val typed = if (query.isBlank()) null else CoordinateQuery.parse(query, near())
        state.coordinateQuery = typed != null
        if (typed != null) {
            val name = typed.plusCode ?: CoordinateQuery.formatDecimal(typed.point)
            state.results = listOf(SearchResult(name, typed.point, category = coordinateLabels.label(typed.kind).ifEmpty { null }))
            state.status = SearchStatus.DONE
            return
        }
        if (query.isBlank()) {
            state.results = emptyList()
            state.status = if (state.regionsAvailable == false) SearchStatus.NO_REGIONS else SearchStatus.IDLE
            return
        }
        launchQuery(query, debounceMs)
    }

    /**
     * The user pressed the search key (or the field's search button): runs the typed text now, without the debounce,
     * and shows progress at once. Returns false when there is nothing to run (blank text or a typed position, which is
     * answered already), so the host can still just dismiss the keyboard. Safe to call repeatedly: it re-runs the query,
     * which is also how an ERROR is retried.
     */
    fun submit(): Boolean {
        val query = state.query
        if (state.category != null || query.isBlank() || state.coordinateQuery) return false
        job?.cancel()
        clearOnline()
        state.numberMissing = null
        state.submitted = true
        state.status = if (engine == null || dirty) SearchStatus.PREPARING else SearchStatus.SEARCHING
        launchQuery(query, 0)
        return true
    }

    private fun launchQuery(query: String, delayMs: Long) {
        job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val point = near()
            val cold = engine == null || dirty
            val asked = state.submitted
            state.status = if (cold) SearchStatus.PREPARING else SearchStatus.SEARCHING
            val outcome = withContext(io) { mutex.withLock { run(query.trim()) { it.search(query.trim(), point, limit) } } }
            state.submitted = false
            when (outcome) {
                is Outcome.Found -> {
                    state.results = outcome.list
                    state.numberMissing = com.qtekfun.ultimatemaps.core.search.HouseNumberHint.missingNumber(query, outcome.list)
                    state.status = SearchStatus.DONE
                    if (outcome.list.isEmpty() || state.numberMissing != null) fallBackOnline(query.trim(), point, outcome.list, asked)
                }
                Outcome.NoRegions -> {
                    state.regionsAvailable = false
                    state.results = emptyList()
                    state.status = SearchStatus.NO_REGIONS
                }
                Outcome.Failed -> {
                    state.results = emptyList()
                    state.status = SearchStatus.ERROR
                }
            }
        }
    }

    /**
     * The offline search is settled with nothing (or only streets for a typed number): ask the optional online geocoder and
     * list its results after the offline ones (before them when the offline ones are only streets of a missing number, since
     * the online answer may carry the number). Runs inside the query's job, so a keystroke cancels it. A submitted search goes
     * at once; an as-you-type one waits [onlineDebounceMs] more and needs [OnlineSearch.MIN_QUERY_CHARS] characters.
     */
    private suspend fun fallBackOnline(text: String, near: LatLon?, offline: List<SearchResult>, submitted: Boolean) {
        if (state.category != null || !online.enabled || text.length < OnlineSearch.MIN_QUERY_CHARS) return
        state.onlineSearching = true
        try {
            if (!submitted && onlineDebounceMs > 0) delay(onlineDebounceMs)
            when (val r = online.search(text, near, onlineLimit)) {
                is OnlineOutcome.Found -> {
                    val extra = r.results.filter { o -> offline.none { it.name.equals(o.name, ignoreCase = true) && closeTo(it.point, o.point) } }
                    if (extra.isNotEmpty()) {
                        state.results = if (offline.isNotEmpty() && state.numberMissing != null) extra + offline else offline + extra
                        onOnlineResults(extra.map { it.point })
                        onlinePinsShown = true
                    }
                }
                OnlineOutcome.Failed -> state.onlineFailed = true
                OnlineOutcome.Offline, OnlineOutcome.Disabled -> Unit
            }
        } finally {
            state.onlineSearching = false
        }
    }

    private var onlinePinsShown = false

    /** Drops the online pins and notes of the previous text. */
    private fun clearOnline() {
        state.onlineSearching = false
        state.onlineFailed = false
        if (onlinePinsShown) {
            onlinePinsShown = false
            onOnlineResults(emptyList())
        }
    }

    private fun closeTo(a: LatLon, b: LatLon): Boolean =
        kotlin.math.abs(a.lat - b.lat) < 0.001 && kotlin.math.abs(a.lon - b.lon) < 0.001

    private sealed interface Outcome {
        data class Found(val list: List<SearchResult>) : Outcome
        data object NoRegions : Outcome
        data object Failed : Outcome
    }

    private fun run(query: String, search: (SearchEngine) -> List<SearchResult>): Outcome {
        try {
            var current = engine
            if (current == null || dirty) {
                val maps = regions.coreMaps() ?: return Outcome.NoRegions
                val t0 = clock()
                current = backend.open(maps)
                engine = current
                dirty = false
                firstSinceReady = true
                log.engineReady(clock() - t0, maps.regionCount)
            }
            val t0 = clock()
            val found = search(current)
            log.searched(query.length, found.size, clock() - t0, firstSinceReady)
            firstSinceReady = false
            return Outcome.Found(found)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.failed(e.javaClass.simpleName) // the message could echo paths or text: only the type is kept
            return Outcome.Failed
        }
    }

    fun close() {
        job?.cancel()
    }

    companion object {
        const val DEBOUNCE_MS = 250L
        const val ONLINE_DEBOUNCE_MS = 900L
        const val ONLINE_LIMIT = 8
    }
}
