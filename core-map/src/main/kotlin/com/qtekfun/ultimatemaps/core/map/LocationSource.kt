package com.qtekfun.ultimatemaps.core.map

import com.qtekfun.ultimatemaps.core.geo.LatLon

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

/** A [LocationSource] that can say whether it can work at all on this device (a provider exists). */
interface AvailableLocationSource : LocationSource {
    val isAvailable: Boolean
}

/**
 * A hint about how the device is moving, from the system's activity recognition when the build has it (the optional `play`
 * flavor) and from nothing otherwise. Only ever a weak extra evidence: [inVehicle] is true when the device is probably in a
 * vehicle, false when it is probably on foot or still, null when unknown. Cheap to call (a cached value).
 */
interface MovementHint {
    fun inVehicle(): Boolean?

    /** The runtime permission the hint needs before [start] does anything, or null when it needs none. */
    val permission: String?

    fun start()
    fun stop()
}

/** No hint: the default and the only one in the `foss` flavor. */
object NoMovementHint : MovementHint {
    override fun inVehicle(): Boolean? = null
    override val permission: String? = null
    override fun start() {}
    override fun stop() {}
}

/** A circle the system watches by itself, cheaply, even with the screen off: [id] comes back when the device enters it. */
data class GeofenceTarget(val id: String, val point: com.qtekfun.ultimatemaps.core.geo.LatLon, val radiusMeters: Float)

/**
 * Watches a few circles for the system (the `play` flavor uses Google's geofencing, which needs no continuous GPS) and tells
 * when the device enters one. Weak help for the public-transport trip: "get off at the next stop" still arrives when position
 * updates are throttled. [set] replaces the whole list; [clear] stops watching. May call [onEnter] from any thread.
 */
interface Geofencer {
    fun set(targets: List<GeofenceTarget>, onEnter: (id: String) -> Unit)
    fun clear()
}

/** No geofencing: the default and the only one in the `foss` flavor. */
object NoGeofencer : Geofencer {
    override fun set(targets: List<GeofenceTarget>, onEnter: (id: String) -> Unit) {}
    override fun clear() {}
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
