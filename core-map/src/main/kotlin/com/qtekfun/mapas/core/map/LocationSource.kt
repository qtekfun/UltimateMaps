package com.qtekfun.mapas.core.map

import com.qtekfun.mapas.core.geo.LatLon

/** One position fix. Never logged by default (privacy). */
data class LocationFix(
    val point: LatLon,
    val accuracyMeters: Float? = null,
    val bearingDegrees: Float? = null,
    val speedMps: Float? = null,
    val timeMillis: Long = 0L,
)

/**
 * Source of positions. The real implementation uses the platform `LocationManager` without any
 * proprietary SDK; navigation tests use [SimulatedLocationSource].
 */
interface LocationSource {
    fun interface Listener {
        fun onFix(fix: LocationFix)
    }

    /** Latest known fix, or null when there is none. */
    fun lastKnown(): LocationFix?

    /** Starts delivering fixes to [listener]. Calling again replaces the listener. Needs the location permission. */
    fun start(listener: Listener)

    /** Stops updates; safe to call when not started. */
    fun stop()
}

/** Deterministic [LocationSource] for tests and route simulation: emit fixes with [emit]. */
class SimulatedLocationSource : LocationSource {
    private var listener: LocationSource.Listener? = null
    private var last: LocationFix? = null

    val isStarted: Boolean get() = listener != null

    override fun lastKnown(): LocationFix? = last

    override fun start(listener: LocationSource.Listener) {
        this.listener = listener
    }

    override fun stop() {
        listener = null
    }

    fun emit(fix: LocationFix) {
        last = fix
        listener?.onFix(fix)
    }

    fun emit(lat: Double, lon: Double) = emit(LocationFix(LatLon(lat, lon)))
}
