package com.qtekfun.ultimatemaps.transit.follow

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.CameraPadding
import com.qtekfun.ultimatemaps.core.map.MapEngine
import com.qtekfun.ultimatemaps.core.transit.Itinerary
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the transit trip screen frames the map: first display, re-plan, new leg, recenter and layout changes before any
 * gesture, never after one (a zoom is a gesture). A recording engine stands in for the map; no threads, no clocks.
 */
class TripCameraFramingTest {
    private class RecordingEngine : MapEngine {
        val frames = mutableListOf<List<LatLon>>()
        val gestureListeners = mutableListOf<() -> Unit>()
        override fun setCamera(center: LatLon, zoom: Double) = Unit
        override fun camera() = LatLon(0.0, 0.0) to 0.0
        override fun frameRoute(points: List<LatLon>, padding: CameraPadding) { frames += points }
        override fun addCameraGestureListener(listener: () -> Unit) { gestureListeners += listener }
        override fun close() = Unit

        /** A pinch, a pan or a rotation: the engine reports the start of a user gesture. */
        fun userGesture() = gestureListeners.forEach { it() }
    }

    private val engine = RecordingEngine()
    private val framing = TripCameraFraming { engine.frameRoute(it, CameraPadding(0, 0, 0, 0)) }.also { f ->
        engine.addCameraGestureListener { f.onUserMovedMap() }
    }

    private val all = listOf(LatLon(40.0, -3.0), LatLon(40.1, -3.1), LatLon(40.2, -3.2))
    private val leg0 = all.take(2)
    private val leg1 = all.drop(1)

    private fun show(itinerary: Itinerary = TripTestSupport.itinerary(), leg: Int = 0) =
        framing.onTrip(itinerary, leg, all, if (leg == 0) leg0 else leg1)

    @Test fun firstDisplayFramesTheWholeItineraryOnce() {
        val it = TripTestSupport.itinerary()
        show(it)
        show(it) // a state emission or a fix: the same itinerary and leg again
        show(it)
        assertEquals(listOf(all), engine.frames)
        assertFalse(framing.userMoved)
    }

    @Test fun aZoomOrAnyGestureNeverFramesAgainAndOffersRecenter() {
        val it = TripTestSupport.itinerary()
        show(it)
        engine.userGesture() // pinch
        assertTrue(framing.userMoved)
        show(it) // the fixes keep arriving
        show(it)
        assertEquals(1, engine.frames.size)
    }

    @Test fun aNewLegFramesTheLegAndResumesTheAutomaticFraming() {
        val it = TripTestSupport.itinerary()
        show(it, leg = 0)
        engine.userGesture()
        show(it, leg = 1)
        assertEquals(listOf(all, leg1), engine.frames)
        assertFalse(framing.userMoved)
    }

    @Test fun aReplanFramesTheNewItinerary() {
        show(TripTestSupport.itinerary())
        engine.userGesture()
        show(TripTestSupport.itinerary()) // another instance: the re-plan produced a new itinerary
        assertEquals(2, engine.frames.size)
        assertFalse(framing.userMoved)
    }

    @Test fun recenterFramesAgainAndResumes() {
        val it = TripTestSupport.itinerary()
        show(it)
        engine.userGesture()
        framing.recenter()
        assertEquals(2, engine.frames.size)
        assertFalse(framing.userMoved)
        framing.onLayoutChanged()
        assertEquals(3, engine.frames.size)
    }

    @Test fun aLayoutChangeFramesBeforeAnyGestureButNotAfter() {
        show()
        framing.onLayoutChanged() // fold or rotation
        assertEquals(2, engine.frames.size)
        engine.userGesture()
        framing.onLayoutChanged()
        assertEquals(2, engine.frames.size)
    }

    @Test fun gesturesWithoutATripAreIgnoredAndEndForgetsEverything() {
        engine.userGesture()
        assertFalse(framing.userMoved)
        show()
        engine.userGesture()
        framing.end()
        assertFalse(framing.userMoved)
        framing.onLayoutChanged()
        framing.recenter()
        assertEquals(1, engine.frames.size)
    }
}
