package com.qtekfun.ultimatemaps.transit.follow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.transit.Itinerary

/**
 * Decides when the map camera is framed for the transit trip screen. The app frames the trip only when it has a reason:
 * the first display of an itinerary, a re-plan (another itinerary), a new leg, "recenter", or a real layout change
 * (fold, rotation, resize) while the user has not touched the map. Any gesture (pan, pinch, rotate) takes the camera
 * from the app until one of those reasons comes up again; a state emission or a position fix is never a reason, and
 * neither is a zoom. Pure logic: [frame] is the only side effect (tested on the JVM).
 */
class TripCameraFraming(
    /** Animates the camera so that these points fit in the part of the map the trip screen leaves free. */
    private val frame: (List<LatLon>) -> Unit,
) {
    private var itinerary: Itinerary? = null
    private var leg = -1
    private var points: List<LatLon> = emptyList()

    /** The user moved the map since the last framing: the screen offers "recenter" and the app stops adjusting the view. */
    var userMoved: Boolean by mutableStateOf(false)
        private set

    /**
     * The trip screen shows [itinerary] with the follower at [legIndex]; [all] is every point of the itinerary and
     * [legPoints] those of that leg. Frames the whole itinerary the first time an itinerary is seen (start, re-plan) and
     * the leg when only the leg changed; the same input again does nothing.
     */
    fun onTrip(itinerary: Itinerary, legIndex: Int, all: List<LatLon>, legPoints: List<LatLon>) {
        if (itinerary !== this.itinerary) {
            this.itinerary = itinerary
            leg = legIndex
            show(all)
        } else if (legIndex != leg) {
            leg = legIndex
            show(legPoints.ifEmpty { all })
        }
    }

    /** The window was rearranged: the free part of the map changed, so frame again unless the user took the map. */
    fun onLayoutChanged() {
        if (points.isEmpty() || userMoved) return
        frame(points)
    }

    /** The user dragged, pinched or rotated the map (not an animation of the app). Ignored when no trip is shown. */
    fun onUserMovedMap() {
        if (points.isNotEmpty()) userMoved = true
    }

    /** "Recenter": frames what was last framed again and resumes the automatic framing. */
    fun recenter() {
        userMoved = false
        if (points.isNotEmpty()) frame(points)
    }

    /** The trip ended: forget everything. */
    fun end() {
        itinerary = null
        leg = -1
        points = emptyList()
        userMoved = false
    }

    private fun show(target: List<LatLon>) {
        points = target
        userMoved = false
        if (target.isNotEmpty()) frame(target)
    }
}
