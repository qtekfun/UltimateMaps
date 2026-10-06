package com.qtekfun.mapas.core.map

import com.qtekfun.mapas.core.geo.LatLon

/** Minimal map-engine contract; the concrete engine (spike option A/B/C) lives behind it. */
interface MapEngine : AutoCloseable {
    /** Moves the camera. Must be cheap: it can be called on every gesture frame. */
    fun setCamera(center: LatLon, zoom: Double)

    /** Current camera centre and zoom. */
    fun camera(): Pair<LatLon, Double>
}
