package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.cameras.LatLonBounds
import com.qtekfun.ultimatemaps.core.map.GeoBounds
import com.qtekfun.ultimatemaps.core.map.TrailLine
import com.qtekfun.ultimatemaps.core.routes.RouteRepository
import com.qtekfun.ultimatemaps.core.routes.RouteSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Decides which hiking and cycling route pieces the map shows and hands [render] the lines. Same discipline as
 * [ChargerMapLayer]: no MapLibre here (runs on the JVM in tests); the engine reports the viewport only when a camera
 * gesture ends; the query runs on [io] after [debounceMs]; [render] (main thread) is called only when the result differs
 * from what is drawn. Switched off it draws nothing and, once nothing is drawn, does not even query. Below
 * [RouteRepository.MIN_ZOOM] nothing is drawn.
 */
class TrailMapLayer(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val repository: RouteRepository,
    private val settings: StateFlow<RouteSettings>,
    private val render: (List<TrailLine>) -> Unit,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val minZoom: Double = RouteRepository.MIN_ZOOM,
) {
    private class Viewport(val bounds: GeoBounds, val zoom: Double)

    private var viewport: Viewport? = null
    private var shown: List<Key> = emptyList()
    private var job: Job? = null
    private var watching: Job? = null

    /** What identifies a drawn piece: two queries with the same keys draw the same picture. */
    private data class Key(val id: Int, val points: Int, val first: Long, val last: Long)

    /** Starts following the settings and the data. Call once, from the main thread. */
    fun start() {
        if (watching != null) return
        watching = scope.launch {
            combine(settings, repository.generatedMillis) { s, updated -> s to updated }.distinctUntilChanged().collect { schedule(0) }
        }
    }

    fun stop() {
        watching?.cancel()
        watching = null
        job?.cancel()
        job = null
    }

    /** The camera stopped at [bounds] / [zoom]. Cheap when there is nothing to show or draw. */
    fun onViewport(bounds: GeoBounds, zoom: Double) {
        viewport = Viewport(bounds, zoom)
        if (!settings.value.enabled && shown.isEmpty()) return // switched off: no coroutine, no query
        schedule(debounceMs)
    }

    private fun schedule(delayMs: Long) {
        job?.cancel()
        val vp = viewport ?: return
        val config = settings.value
        job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val lines = if (!config.enabled || vp.zoom < minZoom) emptyList() else withContext(io) { compute(vp, config) }
            val keys = lines.map { Key(it.id, it.points.size, it.points.first().lat.toBits() xor (it.points.first().lon.toBits() shl 1), it.points.last().lat.toBits()) }
            if (keys != shown) {
                shown = keys
                render(lines)
            }
        }
    }

    /** Runs on [io]. */
    private fun compute(vp: Viewport, s: RouteSettings): List<TrailLine> {
        val b = vp.bounds
        return repository.piecesIn(LatLonBounds(b.south, b.west, b.north, b.east), vp.zoom, s).map {
            TrailLine(it.trail.id, it.trail.level.code, it.trail.kind.isBike, it.points)
        }
    }

    companion object {
        const val DEBOUNCE_MS = 250L
    }
}
