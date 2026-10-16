package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.map.GeoBounds
import com.qtekfun.ultimatemaps.core.traffic.FlowGeoJson
import com.qtekfun.ultimatemaps.core.traffic.FlowLine
import com.qtekfun.ultimatemaps.core.traffic.FlowLines
import com.qtekfun.ultimatemaps.core.traffic.TrafficProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Decides which live-traffic lines the map shows and hands [render] the lines. Same discipline as [TrailMapLayer]: no MapLibre
 * here (runs on the JVM in tests); the engine reports the viewport when a camera gesture ends; the tiles are read on [io] after
 * [debounceMs] and cached by the provider; [render] (main thread) is called only when the picture changes. Off, it draws nothing
 * and asks nothing. Below [FlowLines.MIN_MAP_ZOOM] or over [FlowLines.MAX_TILES] tiles nothing is drawn. While on and showing,
 * it looks again every [refreshMs] (the feed changes about every minute; the provider's cache keeps this to one read per 3 minutes).
 */
class TrafficMapLayer(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val provider: TrafficProvider,
    /** The switch is on, a key is stored and the owner wants the layer. Read on every decision. */
    private val wanted: () -> Boolean,
    /** Gets the GeoJSON of the lines (built on [io]); [FlowGeoJson.EMPTY] clears the layer. Called on the main thread, only on a change. */
    private val render: (String) -> Unit,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val refreshMs: Long = REFRESH_MS,
) {
    private class Viewport(val bounds: GeoBounds, val zoom: Double)

    private var viewport: Viewport? = null
    private var shown: List<Long> = emptyList()
    private var job: Job? = null
    private var ticker: Job? = null

    /** Starts the periodic look. Call once, from the main thread. */
    fun start() {
        if (ticker != null) return
        ticker = scope.launch {
            while (true) {
                delay(refreshMs)
                if (wanted() || shown.isNotEmpty()) schedule(0)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        job?.cancel()
        job = null
    }

    /** The owner changed something that decides what is shown (the map button): decide again now, without the debounce. */
    fun refresh() = schedule(0)

    /** The camera stopped at [bounds] / [zoom]. Cheap when the layer is off and nothing is drawn. */
    fun onViewport(bounds: GeoBounds, zoom: Double) {
        viewport = Viewport(bounds, zoom)
        if (!wanted() && shown.isEmpty()) return
        schedule(debounceMs)
    }

    private fun schedule(delayMs: Long) {
        job?.cancel()
        val vp = viewport ?: return
        job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            // Reading, comparing and building the GeoJSON all happen off the main thread; only [render] runs on it.
            val frame = if (!wanted()) Frame(emptyList(), FlowGeoJson.EMPTY) else withContext(io) {
                read(vp)?.let { Frame(it, FlowGeoJson.of(it)) }
            }
            // A failed read keeps the picture the owner already has; only "off" or "too far out" clear it.
            val result = frame ?: if (wanted() && vp.zoom >= FlowLines.MIN_MAP_ZOOM) return@launch else Frame(emptyList(), FlowGeoJson.EMPTY)
            val keys = result.lines.map { it.level.toLong() shl 40 xor (it.points.size.toLong() shl 32) xor it.points.first().lat.toBits() }
            if (keys != shown) {
                shown = keys
                render(result.geoJson)
            }
        }
    }

    private class Frame(val lines: List<FlowLine>, val geoJson: String)

    /** Runs on [io]; null when the provider could not say. */
    private suspend fun read(vp: Viewport): List<FlowLine>? {
        val tiles = FlowLines.tilesFor(vp.bounds.south, vp.bounds.west, vp.bounds.north, vp.bounds.east, vp.zoom)
        if (tiles.isEmpty()) return emptyList()
        return provider.flowLines(tiles)
    }

    companion object {
        const val DEBOUNCE_MS = 800L
        const val REFRESH_MS = 60_000L
    }
}
