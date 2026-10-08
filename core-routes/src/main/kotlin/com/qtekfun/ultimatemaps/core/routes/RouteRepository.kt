package com.qtekfun.ultimatemaps.core.routes

import com.qtekfun.ultimatemaps.core.cameras.LatLonBounds
import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The part of one trail that lies in (or next to) the viewport, ready to draw. */
class TrailPiece(val trail: Trail, val points: List<LatLon>)

/**
 * The trails in memory, for the map layer and the card. Thread-safe for one writer ([install]) and many readers: a query
 * reads one immutable snapshot.
 */
class RouteRepository {
    @Volatile private var dataset = TrailDataset.EMPTY
    private val updated = MutableStateFlow<Long?>(null)

    /** Generation time of the data in the file (epoch millis), or null without data. Shown to the user. */
    val generatedMillis: StateFlow<Long?> get() = updated

    val data: TrailDataset get() = dataset

    fun install(d: TrailDataset?) {
        dataset = d ?: TrailDataset.EMPTY
        updated.value = d?.generatedAtEpochSeconds?.takeIf { it > 0 }?.times(1000)
    }

    fun trail(id: Int): Trail? = dataset.trails.getOrNull(id)

    /**
     * What to draw for [bounds] at [zoom]: only trails of a kind the user chose, only the levels worth drawing at this zoom,
     * only the pieces of each line that touch the view (with a margin, so a small pan does not show gaps before the next
     * query), thinned when zoomed out, and at most [MAX_POINTS] points in all. The data is sorted with the highest levels
     * first, so when the budget runs out the local routes are the ones left out.
     */
    fun piecesIn(bounds: LatLonBounds, zoom: Double, settings: RouteSettings, maxPoints: Int = MAX_POINTS): List<TrailPiece> {
        val minLevel = minLevelFor(zoom)
        val stride = strideFor(zoom)
        val padLat = (bounds.north - bounds.south) * MARGIN
        val padLon = (bounds.east - bounds.west) * MARGIN
        val s = ((bounds.south - padLat) * 1e6).toInt()
        val n = ((bounds.north + padLat) * 1e6).toInt()
        val w = ((bounds.west - padLon) * 1e6).toInt()
        val e = ((bounds.east + padLon) * 1e6).toInt()
        val out = ArrayList<TrailPiece>()
        var budget = maxPoints
        for (t in dataset.trails) {
            if (budget <= 0) break
            if (t.level.code < minLevel || !settings.accepts(t.kind)) continue
            if (t.maxLat < s || t.minLat > n || t.maxLon < w || t.minLon > e) continue
            for (seg in t.segments) {
                forEachRun(seg, s, n, w, e) { from, to ->
                    val pts = thin(seg, from, to, stride)
                    if (pts.size >= 2 && budget > 0) {
                        budget -= pts.size
                        out += TrailPiece(t, pts)
                    }
                }
            }
        }
        return out
    }

    companion object {
        /** Below this zoom nothing is drawn. */
        const val MIN_ZOOM = 7.0
        const val MAX_POINTS = 30_000
        private const val MARGIN = 0.25

        /** Lowest level code drawn: only long-distance routes when zoomed far out. */
        fun minLevelFor(zoom: Double): Int = when {
            zoom < 9 -> TrailLevel.NATIONAL.code
            zoom < 11 -> TrailLevel.REGIONAL.code
            else -> TrailLevel.LOCAL.code
        }

        /** Every n-th point is kept when zoomed out (the ends of a run always are). */
        fun strideFor(zoom: Double): Int = when {
            zoom < 9 -> 4
            zoom < 11 -> 2
            else -> 1
        }

        /** Calls [block] with the point index range `[from, to]` of each maximal run of edges that touch the box. */
        internal inline fun forEachRun(seg: IntArray, s: Int, n: Int, w: Int, e: Int, block: (from: Int, to: Int) -> Unit) {
            val count = seg.size / 2
            var runStart = -1
            for (i in 0 until count - 1) {
                val lat0 = seg[i * 2]
                val lon0 = seg[i * 2 + 1]
                val lat1 = seg[i * 2 + 2]
                val lon1 = seg[i * 2 + 3]
                val hit = maxOf(lat0, lat1) >= s && minOf(lat0, lat1) <= n && maxOf(lon0, lon1) >= w && minOf(lon0, lon1) <= e
                if (hit) {
                    if (runStart < 0) runStart = i
                } else if (runStart >= 0) {
                    block(runStart, i)
                    runStart = -1
                }
            }
            if (runStart >= 0) block(runStart, count - 1)
        }

        internal fun thin(seg: IntArray, from: Int, to: Int, stride: Int): List<LatLon> {
            val out = ArrayList<LatLon>((to - from) / stride + 2)
            var i = from
            while (i < to) {
                out += LatLon(seg[i * 2] / 1e6, seg[i * 2 + 1] / 1e6)
                i += stride
            }
            out += LatLon(seg[to * 2] / 1e6, seg[to * 2 + 1] / 1e6)
            return out
        }
    }
}
