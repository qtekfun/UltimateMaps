package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.cameras.LatLonBounds
import com.qtekfun.ultimatemaps.core.chargers.Charger
import com.qtekfun.ultimatemaps.core.chargers.ChargerRepository
import com.qtekfun.ultimatemaps.core.chargers.ChargerSettings
import com.qtekfun.ultimatemaps.core.map.ChargerPin
import com.qtekfun.ultimatemaps.core.map.GeoBounds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.pow

/**
 * Decides which charging stations the map shows and hands [render] the pins. Same discipline as [FuelMapLayer]: no
 * MapLibre here (runs on the JVM in tests); the engine reports the viewport only when a camera gesture ends; the query
 * runs on [io] after [debounceMs]; [render] (main thread) is called only when the result differs from what is drawn.
 * Switched off it draws nothing and, once nothing is drawn, does not even query. Below [minZoom] nothing is drawn; between
 * [minZoom] and [THIN_BELOW] at most one station per grid cell is kept (the most powerful), so a city is never a blur.
 */
class ChargerMapLayer(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val repository: ChargerRepository,
    private val settings: StateFlow<ChargerSettings>,
    private val render: (List<ChargerPin>) -> Unit,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val minZoom: Double = MIN_ZOOM,
) {
    private class Viewport(val bounds: GeoBounds, val zoom: Double)

    private var viewport: Viewport? = null
    private var shown: List<ChargerPin> = emptyList()
    private var job: Job? = null
    private var watching: Job? = null

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
            val pins = if (!config.enabled || vp.zoom < minZoom) emptyList() else withContext(io) { compute(vp, config) }
            if (pins != shown) {
                shown = pins
                render(pins)
            }
        }
    }

    /** Runs on [io]. */
    private fun compute(vp: Viewport, s: ChargerSettings): List<ChargerPin> {
        val b = vp.bounds
        val found = repository.chargersIn(LatLonBounds(b.south, b.west, b.north, b.east), s, limitFor(vp.zoom))
        return thin(found, vp.zoom).map { ChargerPin(it.id, it.location, it.isFast) }
    }

    companion object {
        /** Below this zoom nothing is drawn: stations would be a blur. */
        const val MIN_ZOOM = 9.0
        const val DEBOUNCE_MS = 250L

        /** Under this zoom at most one station per [CELL_DP] cell is kept (the most powerful one). */
        const val THIN_BELOW = 13.0
        private const val CELL_DP = 40.0

        fun limitFor(zoom: Double): Int = when {
            zoom < 11 -> 150
            zoom < 13 -> 300
            else -> 500
        }

        /** Keeps the most powerful station of each grid cell when zoomed out; the result keeps the input order. */
        internal fun thin(found: List<Charger>, zoom: Double): List<Charger> {
            if (zoom >= THIN_BELOW) return found
            val cell = CELL_DP * 360.0 / (512.0 * 2.0.pow(zoom)) // degrees; MapLibre tiles are 512 dp at zoom 0
            val best = HashMap<Long, Charger>()
            for (c in found) {
                val key = floor(c.location.lon / cell).toLong() * 1_000_003L + floor(c.location.lat / cell).toLong()
                val old = best[key]
                if (old == null || (c.maxPowerKw ?: 0.0) > (old.maxPowerKw ?: 0.0)) best[key] = c
            }
            val kept = best.values.toHashSet()
            return found.filter { it in kept }
        }
    }
}
