package com.qtekfun.mapas.nav

import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.CameraPadding
import com.qtekfun.mapas.core.map.CameraState
import com.qtekfun.mapas.core.map.MapEngine
import com.qtekfun.mapas.core.nav.NavState
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the navigation asks of the map in 3D and 2D, with a fake engine (MapLibre cannot render in Robolectric, so
 * the pictures are not checked here: only the requests). The host is driven with hand-made [NavUi] snapshots and a
 * clock the test moves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NavHost3dTest {
    private class Move(val state: CameraState, val millis: Int)

    private class FakeEngine : MapEngine {
        val moves = mutableListOf<Move>()
        val headings = mutableListOf<Float?>()
        val buildings = mutableListOf<Boolean>()
        var user: LatLon? = null
        var clears = 0
        var resets = 0
        var current = CameraState(LatLon(40.0, -3.0), 17.0, 33.0, 40.0, CameraPadding(0, 500, 0, 0))
        var gesture: (() -> Unit)? = null
        override fun setCamera(center: LatLon, zoom: Double) = Unit
        override fun camera() = current.center to current.zoom
        override fun cameraState() = current
        override fun animateTo(state: CameraState, durationMillis: Int) { moves += Move(state, durationMillis); current = state }
        override fun showUserLocation(point: LatLon?, accuracyMeters: Float?) { user = point }
        override fun setUserHeading(degrees: Float?) { headings += degrees }
        override fun setBuildings3d(enabled: Boolean) { buildings += enabled }
        override fun clearRoute() { clears++ }
        override fun resetNorth() { resets++ }
        override fun setCameraGestureListener(listener: (() -> Unit)?) { gesture = listener }
        override fun close() = Unit
    }

    private val rig = NavTestRig()
    private val engine = FakeEngine()
    private var clock = 10_000L

    @After fun tearDown() = rig.close()

    private fun withHost(block: (NavHost) -> Unit) {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            block(NavHost(activity, engine, rig.screen, NavCamera(), now = { clock }, screenHeightPx = { 2400 }))
        }
        scenario.close()
    }

    private fun ui(nav: NavState = navState(speedMps = 10.0), view3d: Boolean = true, buildings3d: Boolean = true, following: Boolean = true, overview: Boolean = false) =
        NavUi(phase = NavPhase.ON_ROUTE, nav = nav, view3d = view3d, buildings3d = buildings3d, following = following, overview = overview)

    @Test fun `starting in 3D eases into the tilt with the marker low, a heading arrow and the buildings`() {
        withHost { host ->
            host.render(ui())
            val move = engine.moves.single()
            assertEquals(NavCamera.TRANSITION_MILLIS, move.millis)
            assertTrue(move.state.tilt in 55.0..60.0)
            assertEquals(90.0, move.state.bearing, 1e-9)
            assertTrue(NavCameraPlanner.markerY(move.state.padding, 2400) >= 2400 * 2.0 / 3.0)
            assertEquals(listOf(true), engine.buildings)
            assertEquals(90f, engine.headings.last())
            assertNotNull(engine.user)
        }
    }

    @Test fun `following updates are throttled and use the short animation`() {
        withHost { host ->
            host.render(ui())
            clock += 100
            host.render(ui(navState(speedMps = 10.0).copy(position = pt(100.0, 0.0)))) // too soon
            assertEquals(1, engine.moves.size)
            clock += 1_000
            host.render(ui(navState(speedMps = 10.0).copy(position = pt(100.0, 0.0))))
            assertEquals(2, engine.moves.size)
            assertEquals(NavCamera().animationMillis, engine.moves.last().millis)
        }
    }

    @Test fun `2D asks for a flat course up camera with less padding and no buildings`() {
        withHost { host ->
            host.render(ui(view3d = false))
            val move = engine.moves.single()
            assertEquals(0.0, move.state.tilt)
            assertEquals(90.0, move.state.bearing, 1e-9)
            assertTrue(move.state.padding.top < NavCameraPlanner.padding(true, 2400).top)
            assertEquals(emptyList(), engine.buildings) // never shown: nothing to remove
            assertEquals(90f, engine.headings.last()) // the arrow is also there in 2D
        }
    }

    @Test fun `switching to 2D and back eases at once, even inside the interval, and the buildings follow`() {
        withHost { host ->
            host.render(ui())
            clock += 50
            host.render(ui(view3d = false))
            assertEquals(2, engine.moves.size)
            assertEquals(0.0, engine.moves.last().state.tilt)
            assertEquals(NavCamera.TRANSITION_MILLIS, engine.moves.last().millis)
            clock += 50
            host.render(ui(view3d = true))
            assertEquals(3, engine.moves.size)
            assertTrue(engine.moves.last().state.tilt >= 55.0)
            assertEquals(listOf(true, false, true), engine.buildings)
        }
    }

    @Test fun `the buildings setting alone adds and removes the extrusions without moving the camera`() {
        withHost { host ->
            host.render(ui(buildings3d = false))
            assertEquals(emptyList(), engine.buildings)
            host.render(ui(buildings3d = true))
            host.render(ui(buildings3d = true)) // no repeated calls
            host.render(ui(buildings3d = false))
            assertEquals(listOf(true, false), engine.buildings)
            assertEquals(1, engine.moves.size)
        }
    }

    @Test fun `a standing car keeps the last bearing on the map and on the arrow`() {
        withHost { host ->
            host.render(ui(navState(speedMps = 10.0).copy(bearingDegrees = 90f)))
            clock += 1_000
            host.render(ui(navState(speedMps = 0.0).copy(bearingDegrees = 270f)))
            assertEquals(90f, engine.headings.last())
            // The zoom changes (slow now), but the bearing sent to the map is still the last one.
            assertEquals(90.0, engine.moves.last().state.bearing, 1e-9)
        }
    }

    @Test fun `after the user pans, recentering eases back into the view at once`() {
        withHost { host ->
            host.render(ui())
            host.render(ui(following = false))
            assertEquals(1, engine.moves.size) // not following: the camera is left alone
            clock += 10
            host.render(ui(following = true)) // recenter
            assertEquals(2, engine.moves.size)
            assertEquals(NavCamera.TRANSITION_MILLIS, engine.moves.last().millis)
            assertTrue(engine.moves.last().state.tilt >= 55.0)
        }
    }

    @Test fun `stopping eases back to the flat north up view and cleans the map`() {
        withHost { host ->
            host.render(ui())
            engine.moves.clear()
            host.render(NavUi())
            val back = engine.moves.single()
            assertEquals(NavCamera.LEAVE_MILLIS, back.millis)
            assertEquals(0.0, back.state.tilt)
            assertEquals(0.0, back.state.bearing)
            assertEquals(CameraPadding.NONE, back.state.padding)
            assertEquals(engine.current.center, back.state.center)
            assertEquals(null, engine.headings.last())
            assertEquals(false, engine.buildings.last())
            assertNull(engine.user)
            assertEquals(1, engine.clears)
            // A second inactive snapshot does nothing more.
            host.render(NavUi())
            assertEquals(1, engine.moves.size)
        }
    }

    @Test fun `a new trip eases into the tilt again`() {
        withHost { host ->
            host.render(ui())
            host.render(NavUi())
            engine.moves.clear()
            clock += 5_000
            host.render(ui())
            assertEquals(NavCamera.TRANSITION_MILLIS, engine.moves.single().millis)
        }
    }

    @Test fun `the overview leaves the camera to the engine and following resumes with an ease`() {
        withHost { host ->
            host.render(ui())
            val before = engine.moves.size
            clock += 2_000
            host.render(ui(following = false, overview = true))
            assertEquals(before, engine.moves.size) // frameRoute is the engine's own animation, no follow update
            clock += 8_000
            host.render(ui(following = true, overview = false))
            assertEquals(before + 1, engine.moves.size)
            assertEquals(NavCamera.TRANSITION_MILLIS, engine.moves.last().millis)
            assertFalse(engine.moves.last().state.tilt == 0.0)
        }
    }

    @Test fun `the remaining route starts at the user and drops what is behind`() {
        val line = (0..10).map { pt(it * 100.0, 0.0) }
        val position = pt(420.0, 5.0)
        val remaining = remainingRoute(line, position)
        assertEquals(position, remaining.first())
        assertEquals(line[4], remaining[1])
        assertEquals(line.last(), remaining.last())
        assertEquals(1 + 7, remaining.size)
        assertEquals(emptyList(), remainingRoute(emptyList(), position))
    }
}
