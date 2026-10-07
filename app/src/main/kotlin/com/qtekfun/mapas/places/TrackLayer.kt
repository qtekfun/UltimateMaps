package com.qtekfun.mapas.places

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.mapas.core.data.TrackInfo
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.TrackLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Observable state of the tracks section. Written from the main thread only. */
class TrackLayerState {
    var tracks by mutableStateOf<List<TrackInfo>>(emptyList())

    /** Ids of the tracks drawn on the map. Not persisted: the map starts clean after a restart. */
    var visible by mutableStateOf<Set<Long>>(emptySet())
}

/**
 * Draws the imported GPX tracks on the map, one switch per track, and frames a track on request. Geometry comes
 * from the on-device database (no network); it is loaded off the main thread, once per track, and kept while the
 * track is shown. [render] gets exactly the visible tracks (an empty list clears the layer); [fit] frames points.
 */
class TrackLayerController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val service: Lazy<PlacesService>,
    private val render: (List<TrackLine>) -> Unit,
    private val fit: (List<LatLon>) -> Unit,
) {
    val state = TrackLayerState()
    private val loaded = HashMap<Long, TrackLine>()

    /** Reloads the track list (after an import, a restore or a delete) and drops what no longer exists. */
    fun refresh() {
        scope.launch {
            val infos = withContext(io) { service.value.tracks() }
            state.tracks = infos
            val ids = infos.mapTo(HashSet()) { it.id }
            loaded.keys.retainAll(ids)
            val kept = state.visible.filterTo(HashSet()) { it in ids }
            if (kept != state.visible) {
                state.visible = kept
                push()
            }
        }
    }

    /** Shows or hides track [id]. */
    fun toggle(id: Long) {
        if (id in state.visible) {
            state.visible -= id
            push()
        } else {
            show(id)
        }
    }

    /** Makes track [id] visible if needed and frames it. */
    fun fitTo(id: Long) {
        show(id) { line -> fit(line.segments.flatten()) }
    }

    private fun show(id: Long, then: (TrackLine) -> Unit = {}) {
        val info = state.tracks.firstOrNull { it.id == id } ?: return
        scope.launch {
            val line = loaded[id] ?: try {
                withContext(io) { service.value.trackSegments(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }?.let { TrackLine(id, it, colorOf(info)) }?.also { loaded[id] = it } ?: return@launch
            state.visible += id
            push()
            then(line)
        }
    }

    private fun push() {
        render(state.tracks.filter { it.id in state.visible }.mapNotNull { loaded[it.id] })
    }

    companion object {
        /** Distinct, readable on light and dark maps; used when the track has no colour of its own. */
        val PALETTE = intArrayOf(0xFFE5484D.toInt(), 0xFF8E4EC6.toInt(), 0xFF12A594.toInt(), 0xFFF76B15.toInt(), 0xFFD6409F.toInt(), 0xFF3E63DD.toInt())

        fun colorOf(info: TrackInfo): Int = info.color ?: PALETTE[(info.id % PALETTE.size).toInt()]
    }
}
