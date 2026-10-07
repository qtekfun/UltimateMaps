package com.qtekfun.mapas.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.mapas.core.geo.CoordinateQuery
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.search.SearchEngine
import com.qtekfun.mapas.core.search.SearchResult
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

    /** null until the first scan of the installed regions has finished. */
    var regionsAvailable by mutableStateOf<Boolean?>(null)

    /** The text is a position (coordinates or a Plus Code), answered without the search engine. */
    var coordinateQuery by mutableStateOf(false)
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
    private val coordinateLabels: CoordinateLabels = CoordinateLabels { "" },
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

    fun onQueryChange(query: String) {
        state.query = query
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
        job = scope.launch {
            delay(debounceMs)
            val point = near()
            val cold = engine == null || dirty
            state.status = if (cold) SearchStatus.PREPARING else SearchStatus.SEARCHING
            when (val outcome = withContext(io) { mutex.withLock { run(query.trim(), point) } }) {
                is Outcome.Found -> {
                    state.results = outcome.list
                    state.status = SearchStatus.DONE
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

    private sealed interface Outcome {
        data class Found(val list: List<SearchResult>) : Outcome
        data object NoRegions : Outcome
        data object Failed : Outcome
    }

    private fun run(query: String, point: LatLon?): Outcome {
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
            val found = current.search(query, point, limit)
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
    }
}
