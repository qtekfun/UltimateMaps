package com.qtekfun.mapas.core.map

import com.qtekfun.mapas.core.geo.LatLon

/** Full camera state. Value type: persisted between launches so the first frame paints the last view. */
data class CameraState(
    val center: LatLon,
    val zoom: Double,
    val bearing: Double = 0.0,
    val tilt: Double = 0.0,
) {
    init {
        require(zoom.isFinite() && zoom in 0.0..24.0) { "zoom out of range: $zoom" }
        require(bearing.isFinite() && tilt.isFinite() && tilt in 0.0..85.0) { "bad bearing/tilt" }
    }

    companion object {
        /** First launch: peninsular Spain. */
        val DEFAULT = CameraState(LatLon(40.4, -3.7), 5.0)
    }
}

/** Visual theme of the base map. */
enum class MapTheme { LIGHT, DARK }

/** Persists the last camera state (RF-01 start-up: paint the last state). Reads must be cheap and synchronous. */
interface CameraStateStore {
    fun load(): CameraState?
    fun save(state: CameraState)
}

/** Minimal map-engine contract; the concrete engine (spike option A/B/C) lives behind it. */
interface MapEngine : AutoCloseable {
    /** Moves the camera. Must be cheap: it can be called on every gesture frame. */
    fun setCamera(center: LatLon, zoom: Double)

    /** Current camera centre and zoom. */
    fun camera(): Pair<LatLon, Double>

    /** Full camera state (centre, zoom, bearing, tilt). */
    fun cameraState(): CameraState = camera().let { (c, z) -> CameraState(c, z) }

    /** Switches the base map between day and night styles. */
    fun setTheme(theme: MapTheme) {}

    /** Animates the camera to [state] over [durationMillis] (0 = jump). */
    fun animateTo(state: CameraState, durationMillis: Int = 600) = setCamera(state.center, state.zoom)

    /** Resets bearing to north and tilt to 0. */
    fun resetNorth() {}

    /** Shows (or with null hides) the user position marker. Called at GNSS rate, not per frame. */
    fun showUserLocation(point: LatLon?, accuracyMeters: Float? = null) {}

    /** Shows (or with null hides) a pin, for example the destination of an opened map link. */
    fun showPin(point: LatLon?) {}

    /** Shows exactly these markers (saved places); an empty list hides them. */
    fun showMarkers(points: List<LatLon>) {}

    // --- Route preview ---

    /** Draws [points] as the route line and, with [fit], frames the whole route in the visible map area. */
    fun showRoute(points: List<LatLon>, fit: Boolean = true) {}

    /** Removes the route line. */
    fun clearRoute() {}

    /** Reports taps on the bare map (to pick a route origin); null removes the listener. Never called per frame. */
    fun setMapTapListener(listener: ((LatLon) -> Unit)?) {}
}
