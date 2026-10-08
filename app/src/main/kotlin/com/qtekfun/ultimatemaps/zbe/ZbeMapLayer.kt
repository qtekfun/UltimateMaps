package com.qtekfun.ultimatemaps.zbe

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.ZoneShape
import com.qtekfun.ultimatemaps.core.zbe.ZbeRepository
import com.qtekfun.ultimatemaps.core.zbe.ZbeSettings
import com.qtekfun.ultimatemaps.core.zbe.ZbeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Decides which low-emission zones the map draws and hands [render] the shapes. The whole file is small (tens of zones), so
 * there is no viewport query: the shapes are handed over when the switch, the layer toggle or the data change, an empty list
 * when the layer is not visible. No MapLibre here (runs on the JVM in tests).
 */
class ZbeMapLayer(
    private val scope: CoroutineScope,
    private val repository: ZbeRepository,
    private val settings: StateFlow<ZbeSettings>,
    private val render: (List<ZoneShape>) -> Unit,
) {
    private var job: Job? = null

    /** Starts following the settings and the data. Call once, from the main thread. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            combine(settings, repository.changes) { s, version -> s.layerVisible to version }.distinctUntilChanged().collect { (visible, _) ->
                render(if (visible) shapesOf(repository.data.zones) else emptyList())
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** One shape per polygon of each zone; every ring is closed (first point repeated) as GeoJSON wants. */
        fun shapesOf(zones: List<ZbeZone>): List<ZoneShape> = zones.flatMap { z ->
            z.polygons.map { poly ->
                ZoneShape(
                    poly.rings.map { r ->
                        val pts = List(r.size) { LatLon(r.lat(it), r.lon(it)) }
                        pts + pts.first()
                    },
                )
            }
        }
    }
}
