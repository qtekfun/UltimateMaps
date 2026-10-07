package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.fuel.FuelRepository
import com.qtekfun.ultimatemaps.core.fuel.FuelSettings
import com.qtekfun.ultimatemaps.core.fuel.FuelStation
import com.qtekfun.ultimatemaps.core.fuel.FuelType
import com.qtekfun.ultimatemaps.core.fuel.LatLonBounds
import com.qtekfun.ultimatemaps.core.map.FuelPin
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
import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow

/**
 * Decides which stations the map shows (RF-15) and hands [render] the list to draw. No MapLibre here, so it runs on
 * the JVM in tests.
 *
 * Never works per frame: the engine calls [onViewport] only when a camera gesture ends; the query runs on [io] after
 * [debounceMs] without further changes, and [render] (main thread) is called only when the result differs from what
 * is already drawn. A change of the chosen fuel, of the on/off switch or of the repository data
 * ([FuelRepository.lastUpdateMillis]) recomputes. Switched off, below [minZoom] or without a chosen fuel it draws
 * nothing and, once nothing is drawn, does not even query.
 */
class FuelMapLayer(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val repository: FuelRepository,
    private val settings: StateFlow<FuelSettings>,
    private val render: (List<FuelPin>) -> Unit,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val debounceMs: Long = DEBOUNCE_MS,
    private val minZoom: Double = MIN_ZOOM,
) {
    private class Viewport(val bounds: GeoBounds, val zoom: Double)

    private var viewport: Viewport? = null
    private var shown: List<FuelPin> = emptyList()
    private var job: Job? = null
    private var watching: Job? = null

    /** Starts following the settings and the repository. Call once, from the main thread. */
    fun start() {
        if (watching != null) return
        watching = scope.launch {
            combine(settings, repository.lastUpdateMillis) { s, updated -> Triple(s.enabled, s.mapFuel, updated) }
                .distinctUntilChanged()
                .collect { schedule(0) }
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
            val fuelId = config.mapFuel
            val pins = if (!config.enabled || fuelId == null || vp.zoom < minZoom) {
                emptyList()
            } else {
                withContext(io) { compute(vp, fuelId) }
            }
            if (pins != shown) {
                shown = pins
                render(pins)
            }
        }
    }

    /** Runs on [io]. */
    private fun compute(vp: Viewport, fuelId: String): List<FuelPin> {
        val b = vp.bounds
        val fuel = FuelType(fuelId, fuelId)
        val found = repository.stationsIn(LatLonBounds(b.south, b.west, b.north, b.east), fuel, limitFor(vp.zoom))
        val priced = found.mapNotNull { s -> s.prices[fuelId]?.let { s to it } }.sortedBy { it.second }
        val kept = thin(priced, vp.zoom)
        if (kept.isEmpty()) return emptyList()
        val cheapCount = (kept.size / 10).coerceIn(1, MAX_CHEAP)
        val threshold = kept[cheapCount - 1].second
        val max = kept.last().second
        val loc = locale()
        return kept.mapIndexed { i, (s, price) ->
            FuelPin(s.id, s.location, price(price, loc), cheap = price <= threshold && price < max, rank = i)
        }
    }

    companion object {
        /** Below this zoom nothing is drawn: stations would be a blur. */
        const val MIN_ZOOM = 11.0
        const val DEBOUNCE_MS = 250L
        private const val MAX_CHEAP = 5

        /** Under this zoom at most one station per [CELL_DP] cell is kept (the cheapest). */
        private const val THIN_BELOW = 14.0
        private const val CELL_DP = 48.0

        fun limitFor(zoom: Double): Int = when {
            zoom < 12 -> 80
            zoom < 13 -> 150
            zoom < 14 -> 250
            else -> 400
        }

        /** "1,154 €" (decimal separator of [locale]). */
        fun price(value: Double, locale: Locale): String = String.format(locale, "%.3f €", value)

        /** Keeps the cheapest station of each grid cell when zoomed out; [priced] must be sorted cheapest first. */
        internal fun thin(priced: List<Pair<FuelStation, Double>>, zoom: Double): List<Pair<FuelStation, Double>> {
            if (zoom >= THIN_BELOW) return priced
            val cell = CELL_DP * 360.0 / (512.0 * 2.0.pow(zoom)) // degrees; MapLibre tiles are 512 dp at zoom 0
            val seen = HashSet<Long>()
            return priced.filter { (s, _) ->
                val x = floor(s.location.lon / cell).toLong()
                val y = floor(s.location.lat / cell).toLong()
                seen.add(x * 1_000_003L + y)
            }
        }
    }
}

/** Settings that never change (feature off by default) until the real store is wired in; also handy in tests. */
class StaticFuelSettings(initial: FuelSettings = FuelSettings()) : com.qtekfun.ultimatemaps.core.fuel.FuelSettingsStore {
    override val settings = kotlinx.coroutines.flow.MutableStateFlow(initial)
    override fun update(transform: (FuelSettings) -> FuelSettings) {
        settings.value = transform(settings.value)
    }
}

/** Repository with no data (until the real one is wired in). */
object NoFuelData : FuelRepository {
    override fun stationsIn(bounds: LatLonBounds, fuel: FuelType, limit: Int): List<FuelStation> = emptyList()
    override fun station(id: String): FuelStation? = null
    override val lastUpdateMillis = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
}
