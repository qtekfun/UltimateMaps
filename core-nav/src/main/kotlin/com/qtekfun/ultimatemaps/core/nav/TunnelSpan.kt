package com.qtekfun.ultimatemaps.core.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo

/** Where a [TunnelSpan] came from. */
enum class TunnelSource {
    /** A per-segment tunnel flag from the routing engine (not exposed by the CoMaps bridge yet, see docs/phase2/tunnel-positioning.md). */
    ROUTE_FLAG,

    /** Map data (for example OSM `tunnel=*` ways) intersected with the route. */
    MAP_DATA,

    /** Learned on this device from an observed signal loss; the exit is a guess, so it is trusted less. */
    LEARNED,
}

/**
 * A stretch of the route without GNSS, in metres along the route. [confidence] is in `[0, 1]`; the exit of a
 * [TunnelSource.LEARNED] span is only a previous observation, so the estimator leaves slack beyond it.
 */
data class TunnelSpan(
    val startMeters: Double,
    val endMeters: Double,
    val source: TunnelSource = TunnelSource.MAP_DATA,
    val confidence: Float = 1f,
) {
    val lengthMeters: Double get() = endMeters - startMeters
}

/** Supplies the tunnel spans of a route. Pure and cheap to call; may return an empty list (no knowledge). */
fun interface TunnelSpanSource {
    fun spansFor(geometry: RouteGeometry): List<TunnelSpan>
}

/** Told by the tracker about a stretch that turned out to be without signal (entry point, exit point). */
fun interface TunnelObserver {
    fun onTunnelObserved(entry: LatLon, exit: LatLon)
}

/** Sorted, finite, clamped to the route, overlapping spans merged; invalid ones dropped. */
internal fun sanitizeSpans(spans: List<TunnelSpan>, totalMeters: Double): List<TunnelSpan> {
    val ok = spans.mapNotNull { s ->
        if (!s.startMeters.isFinite() || !s.endMeters.isFinite()) return@mapNotNull null
        val a = s.startMeters.coerceIn(0.0, totalMeters)
        val b = s.endMeters.coerceIn(0.0, totalMeters)
        if (b - a < 1.0) null else s.copy(startMeters = a, endMeters = b)
    }.sortedBy { it.startMeters }
    val out = ArrayList<TunnelSpan>()
    for (s in ok) {
        val last = out.lastOrNull()
        if (last != null && s.startMeters <= last.endMeters) {
            out[out.size - 1] = last.copy(
                endMeters = maxOf(last.endMeters, s.endMeters),
                // The merged span is as trustworthy as its weakest part.
                source = if (last.source == TunnelSource.LEARNED || s.source == TunnelSource.LEARNED) TunnelSource.LEARNED else last.source,
                confidence = minOf(last.confidence, s.confidence),
            )
        } else {
            out += s
        }
    }
    return out
}

/**
 * Tunnels learned from observed signal losses, kept in memory only (nothing is written to disk and nothing
 * leaves the device; it is gone when the process dies). It is the fallback while no data source tells where
 * the tunnels are: after one trip through a tunnel the next route that passes through the same place gets a span.
 * Thread-safe.
 */
class LearnedTunnelStore(
    private val maxEntries: Int = 64,
    private val matchMeters: Double = 40.0,
) : TunnelSpanSource, TunnelObserver {
    private class Entry(val entry: LatLon, val exit: LatLon)

    private val entries = ArrayList<Entry>()

    val size: Int get() = synchronized(entries) { entries.size }

    override fun onTunnelObserved(entry: LatLon, exit: LatLon) {
        synchronized(entries) {
            if (entries.any { it.entry.distanceTo(entry) < matchMeters && it.exit.distanceTo(exit) < matchMeters }) return
            if (entries.size >= maxEntries) entries.removeAt(0)
            entries += Entry(entry, exit)
        }
    }

    override fun spansFor(geometry: RouteGeometry): List<TunnelSpan> {
        val snapshot = synchronized(entries) { entries.toList() }
        if (snapshot.isEmpty()) return emptyList()
        val a = RouteMatch()
        val b = RouteMatch()
        val out = ArrayList<TunnelSpan>()
        for (e in snapshot) {
            geometry.search(e.entry.lat, e.entry.lon, 0.0, geometry.totalMeters, Double.NaN, 0.0, a)
            geometry.search(e.exit.lat, e.exit.lon, 0.0, geometry.totalMeters, Double.NaN, 0.0, b)
            if (a.distance <= matchMeters && b.distance <= matchMeters && b.along - a.along >= MIN_SPAN_METERS) {
                out += TunnelSpan(a.along, b.along, TunnelSource.LEARNED, LEARNED_CONFIDENCE)
            }
        }
        return out
    }

    private companion object {
        const val MIN_SPAN_METERS = 50.0
        const val LEARNED_CONFIDENCE = 0.5f
    }
}

/** What the optional motion sensor says about the vehicle. */
enum class MotionState { UNKNOWN, STOPPED, MOVING }

/**
 * Stop/go detection while there is no GNSS (an IMU on the device). Only an interface here: the Android
 * implementation is a later step that needs a device to tune. Must be cheap and must not allocate; the data
 * stays in memory and is never logged.
 */
fun interface StopGoSignal {
    fun motionState(nowMillis: Long): MotionState

    companion object {
        val NONE = StopGoSignal { MotionState.UNKNOWN }
    }
}
