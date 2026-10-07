package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.cameras.CameraDataRepository
import com.qtekfun.ultimatemaps.core.cameras.CameraSettings
import com.qtekfun.ultimatemaps.core.cameras.IncidentKind
import com.qtekfun.ultimatemaps.core.cameras.IncidentRepository
import com.qtekfun.ultimatemaps.core.cameras.LatLonBounds
import com.qtekfun.ultimatemaps.core.map.GeoBounds
import com.qtekfun.ultimatemaps.core.map.HazardKind
import com.qtekfun.ultimatemaps.core.map.HazardLine
import com.qtekfun.ultimatemaps.core.map.HazardPin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The ids the map layer gives its markers; the card controller reads them back. */
object HazardIds {
    const val CAMERA = "cam:"
    const val ZONE = "zone:"
    const val INCIDENT = "inc:"
}

/**
 * Decides which speed cameras, mobile-radar zones and traffic incidents the map shows and hands [render] the pins and
 * lines. Same discipline as [FuelMapLayer]: no MapLibre here (runs on the JVM in tests); the engine reports the
 * viewport only when a camera gesture ends; the query runs on [io] after [debounceMs]; [render] (main thread) is called
 * only when the result differs from what is drawn. Switched off (nothing enabled) it draws nothing and, once nothing is
 * drawn, does not even query. Below [minZoom] nothing is drawn.
 */
class HazardMapLayer(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val cameras: CameraDataRepository,
    private val incidents: IncidentRepository,
    private val settings: StateFlow<CameraSettings>,
    private val render: (List<HazardPin>, List<HazardLine>) -> Unit,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val minZoom: Double = MIN_ZOOM,
    /** Emits when the cameras or incidents data changes (defaults to both repositories' update times). */
    private val dataChanged: Flow<Any?> = combine(cameras.generatedMillis, incidents.lastUpdateMillis) { a, b -> a to b },
) {
    private class Viewport(val bounds: GeoBounds, val zoom: Double)
    private data class Drawn(val pins: List<HazardPin>, val lines: List<HazardLine>)

    private var viewport: Viewport? = null
    private var shown = Drawn(emptyList(), emptyList())
    private var job: Job? = null
    private var watching: Job? = null

    /** Starts following the settings and the data. Call once, from the main thread. */
    fun start() {
        if (watching != null) return
        watching = scope.launch {
            combine(settings, dataChanged) { s, d -> s to d }.distinctUntilChanged().collect { schedule(0) }
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
        if (!settings.value.anything && shown.pins.isEmpty() && shown.lines.isEmpty()) return
        schedule(debounceMs)
    }

    private fun schedule(delayMs: Long) {
        job?.cancel()
        val vp = viewport ?: return
        val config = settings.value
        job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val drawn = if (!config.anything || vp.zoom < minZoom) Drawn(emptyList(), emptyList()) else withContext(io) { compute(vp, config) }
            if (drawn != shown) {
                shown = drawn
                render(drawn.pins, drawn.lines)
            }
        }
    }

    /** Runs on [io]. */
    private fun compute(vp: Viewport, s: CameraSettings): Drawn {
        val b = LatLonBounds(vp.bounds.south, vp.bounds.west, vp.bounds.north, vp.bounds.east)
        val pins = ArrayList<HazardPin>()
        val lines = ArrayList<HazardLine>()
        if (s.fixedEnabled) {
            cameras.fixedIn(b, MAX_PINS).forEach { pins += HazardPin(HazardIds.CAMERA + it.id, it.location, HazardKind.FIXED_CAMERA) }
            cameras.sectionsIn(b).forEach { sec ->
                pins += HazardPin(HazardIds.CAMERA + sec.id, sec.location, HazardKind.SECTION)
                sec.endLocation?.let { lines += HazardLine(HazardIds.CAMERA + sec.id, listOf(sec.location, it), zone = false) }
            }
        }
        if (s.mobileZonesEnabled) {
            cameras.zonesIn(b).forEach { lines += HazardLine(HazardIds.ZONE + it.id, it.line, zone = true) }
        }
        val kinds = s.incidentKinds()
        if (kinds.isNotEmpty()) {
            incidents.incidentsIn(b, kinds, MAX_PINS).forEach { i ->
                pins += HazardPin(HazardIds.INCIDENT + i.id, i.location, hazardKindOf(i.kind))
                i.end?.let { lines += HazardLine(HazardIds.INCIDENT + i.id, listOf(i.location, it), zone = false) }
            }
        }
        return Drawn(pins, lines)
    }

    companion object {
        /** Below this zoom nothing is drawn: the markers would be a blur. */
        const val MIN_ZOOM = 8.0
        const val DEBOUNCE_MS = 250L
        const val MAX_PINS = 600

        fun hazardKindOf(k: IncidentKind): HazardKind = when (k) {
            IncidentKind.V16 -> HazardKind.V16
            IncidentKind.ACCIDENT -> HazardKind.ACCIDENT
            IncidentKind.CLOSURE -> HazardKind.CLOSURE
            IncidentKind.CONGESTION -> HazardKind.CONGESTION
            IncidentKind.OBSTACLE -> HazardKind.OBSTACLE
            IncidentKind.WEATHER -> HazardKind.WEATHER
            IncidentKind.ROADWORKS -> HazardKind.ROADWORKS
        }
    }
}
