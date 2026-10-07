package com.qtekfun.ultimatemaps.core.map

import com.qtekfun.ultimatemaps.core.geo.LatLon

/**
 * Camera padding in pixels: the camera [center] appears in the middle of the screen minus these margins. The
 * navigation uses a big [top] so that the position marker sits in the lower part of the screen.
 */
data class CameraPadding(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    init {
        require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0) { "negative padding" }
    }

    companion object {
        val NONE = CameraPadding()
    }
}

/**
 * Full camera state. Value type: the centre, zoom, bearing and tilt are persisted between launches so the first
 * frame paints the last view; [padding] is transient (only the navigation camera uses it).
 */
data class CameraState(
    val center: LatLon,
    val zoom: Double,
    val bearing: Double = 0.0,
    val tilt: Double = 0.0,
    val padding: CameraPadding = CameraPadding.NONE,
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

/** A visible map rectangle in degrees (`west <= east`: no antimeridian crossing). */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double)

/**
 * One petrol station drawn on the map: a pump icon and [label] (the price, already formatted). [cheap] marks the
 * cheapest of the view; the engine must show it with a different SHAPE and size, not only a different colour.
 * [rank] orders label collisions (0 = wins first).
 */
data class FuelPin(val id: String, val point: LatLon, val label: String, val cheap: Boolean, val rank: Int)

/**
 * One EV charging station marker. [id] is opaque to the engine (it hands it back on a tap). [fast] stations (high power) are
 * drawn with a different SHAPE (a bolt in the plug), not only a different colour.
 */
data class ChargerPin(val id: String, val point: LatLon, val fast: Boolean)

/** One imported GPX track (or route) drawn as a line: [segments] are drawn separately, [color] is ARGB. */
class TrackLine(val id: Long, val segments: List<List<LatLon>>, val color: Int)

/** What a camera or traffic marker is. The engine must tell kinds apart by SHAPE as well as colour. */
enum class HazardKind { FIXED_CAMERA, SECTION, V16, ACCIDENT, CLOSURE, CONGESTION, OBSTACLE, WEATHER, ROADWORKS }

/** One speed-camera or traffic marker. [id] is opaque to the engine (it hands it back on a tap). */
data class HazardPin(val id: String, val point: LatLon, val kind: HazardKind)

/**
 * A stretch drawn as a line: a mobile-radar ZONE (published as a road and kilometre range, so [zone] is true and the
 * line is dashed and rough), an average-speed section or an incident stretch ([zone] false).
 */
data class HazardLine(val id: String, val points: List<LatLon>, val zone: Boolean)

/** One leg of a public-transport itinerary on the map: [color] is ARGB (the line colour), walking legs are [dashed]. */
class TransitMapLeg(val points: List<LatLon>, val color: Int, val dashed: Boolean)

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

    /**
     * Turns the user marker into a heading arrow pointing at [degrees] clockwise from north (flat on the map plane),
     * or, with null, back into a plain dot. Called at GNSS rate, not per frame.
     */
    fun setUserHeading(degrees: Float?) {}

    /** Shows (or with null hides) a pin, for example the destination of an opened map link. */
    fun showPin(point: LatLon?) {}

    /** Shows (or with null hides) the marker of the parked car, distinct from the saved places and the pin. */
    fun showParking(point: LatLon?) {}

    /** Shows exactly these markers (saved places); an empty list hides them. */
    fun showMarkers(points: List<LatLon>) {}

    // --- Route preview ---

    /** Draws [points] as the route line and, with [fit], frames the whole route in the visible map area. */
    fun showRoute(points: List<LatLon>, fit: Boolean = true) {}

    /**
     * Animates the camera (flat, north up) so that all [points] fit inside the screen minus [padding]; the route line
     * is not touched. For the "route overview" of the navigation.
     */
    fun frameRoute(points: List<LatLon>, padding: CameraPadding) {}

    /** Removes the route line. */
    fun clearRoute() {}

    /**
     * Draws [routes] as lighter lines under the route line (the alternatives not currently selected); an empty list
     * removes them. Never called per frame.
     */
    fun showAlternativeRoutes(routes: List<List<LatLon>>) {}

    /**
     * Shows exactly these category results as pins (separate from the saved markers); an empty list hides them. With
     * [fit] the camera frames them above the bottom sheet.
     */
    fun showCategoryPins(points: List<LatLon>, fit: Boolean = false) {}

    /**
     * Draws a public-transport itinerary: each leg as a line in its own colour, walking legs dashed; an empty list removes
     * it. With [fit] the camera frames the whole itinerary above the bottom sheet. Never called per frame.
     */
    fun showTransitItinerary(legs: List<TransitMapLeg>, fit: Boolean = true) {}

    /** Reports taps on the bare map (to pick a route origin); null removes the listener. Never called per frame. */
    fun setMapTapListener(listener: ((LatLon) -> Unit)?) {}

    // --- Imported tracks ---

    /** Draws exactly these tracks as lines, below the route line; an empty list removes them. Never called per frame. */
    fun showTracks(tracks: List<TrackLine>) {}

    // --- Petrol stations (RF-15) ---

    /** Draws exactly these stations (price labels); an empty list removes the layer content. Never called per frame. */
    fun showFuel(pins: List<FuelPin>) {}

    /** Reports taps on a station (its id), before [setMapTapListener]; null removes the listener. */
    fun setFuelTapListener(listener: ((String) -> Unit)?) {}

    // --- Speed cameras and traffic incidents (optional layers) ---

    /** Draws exactly these markers and lines (cameras, zones, incidents); empty lists remove them. Never called per frame. */
    fun showHazards(pins: List<HazardPin>, lines: List<HazardLine>) {}

    /** Reports taps on a hazard marker or line (its id), after stations and before [setMapTapListener]; null removes it. */
    fun setHazardTapListener(listener: ((String) -> Unit)?) {}

    // --- EV charging stations (optional layer) ---

    /** Draws exactly these charging stations; an empty list removes them. Never called per frame. */
    fun showChargers(pins: List<ChargerPin>) {}

    /** Reports taps on a charging station (its id), after petrol stations and before hazards; null removes it. */
    fun setChargerTapListener(listener: ((String) -> Unit)?) {}

    /** Reports the visible rectangle and zoom when a camera gesture or animation ends (never per frame); null removes it. */
    fun setViewportListener(listener: ((GeoBounds, Double) -> Unit)?) {}

    // --- Navigation ---

    /**
     * Reports when the USER starts moving the camera with a gesture (not animations the app asked for), so that
     * the navigation screen can stop following and offer "recenter". Called once per gesture start; null removes it.
     */
    fun setCameraGestureListener(listener: (() -> Unit)?) {}

    /**
     * Draws (true) or removes (false) the 3D buildings (extruded footprints) of the navigation's 3D view. They are
     * never part of the normal style; the engine adds them at runtime and removes them again.
     */
    fun setBuildings3d(enabled: Boolean) {}
}
