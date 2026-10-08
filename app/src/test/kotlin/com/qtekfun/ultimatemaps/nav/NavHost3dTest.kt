package com.qtekfun.ultimatemaps.nav

import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.CameraPadding
import com.qtekfun.ultimatemaps.core.map.CameraState
import com.qtekfun.ultimatemaps.core.map.MapEngine
import com.qtekfun.ultimatemaps.core.nav.NavState
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

    /** Runs display frames (16 ms apart) on the host's clock for [millis]. */
    private fun settle(host: NavHost, millis: Long = 3_000) {
        val end = clock + millis
        while (clock < end) {
            clock += 16
            host.frame(clock)
        }
    }

    @Test fun `starting in 3D eases from the camera as it is into the tilt with the marker low, an arrow and the buildings`() {
        withHost { host ->
            host.render(ui())
            assertEquals(33.0, engine.moves.first().state.bearing, 1.0) // the first frame is where the camera was: no jump
            assertEquals(40.0, engine.moves.first().state.tilt, 1.0)
            settle(host)
            val move = engine.moves.last()
            assertTrue(move.state.tilt in 54.9..60.0)
            assertEquals(90.0, move.state.bearing, 0.5)
            assertTrue(NavCameraPlanner.markerY(move.state.padding, 2400) >= 2400 * 2.0 / 3.0 - 2)
            assertEquals(listOf(true), engine.buildings)
            assertEquals(90f, engine.headings.last()!!, 0.5f)
            assertNotNull(engine.user)
        }
    }

    @Test fun `following never restarts a map animation, every follow push is a plain move`() {
        withHost { host ->
            host.render(ui())
            clock += 100
            host.render(ui(navState(speedMps = 10.0).copy(position = pt(100.0, 0.0))))
            clock += 1_000
            host.render(ui(navState(speedMps = 10.0).copy(position = pt(100.0, 0.0))))
            settle(host)
            assertTrue(engine.moves.size > 20)
            assertTrue(engine.moves.all { it.millis == 0 })
        }
    }

    @Test fun `2D asks for a flat course up camera with less padding and no buildings`() {
        withHost { host ->
            host.render(ui(view3d = false))
            settle(host)
            val move = engine.moves.last()
            assertEquals(0.0, move.state.tilt, 0.1)
            assertEquals(90.0, move.state.bearing, 0.5)
            assertTrue(move.state.padding.top < NavCameraPlanner.padding(true, 2400).top)
            assertEquals(emptyList(), engine.buildings) // never shown: nothing to remove
            assertEquals(90f, engine.headings.last()!!, 0.5f) // the arrow is also there in 2D
        }
    }

    @Test fun `switching to 2D and back eases the tilt, and the buildings follow`() {
        withHost { host ->
            host.render(ui())
            settle(host)
            host.render(ui(view3d = false))
            settle(host)
            assertEquals(0.0, engine.moves.last().state.tilt, 0.1)
            host.render(ui(view3d = true))
            settle(host)
            assertTrue(engine.moves.last().state.tilt >= 54.9)
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
            assertTrue(engine.moves.all { it.millis == 0 }) // no animation was asked for
        }
    }

    @Test fun `a standing car keeps the last bearing on the map and on the arrow`() {
        withHost { host ->
            host.render(ui(navState(speedMps = 10.0).copy(bearingDegrees = 90f)))
            settle(host)
            host.render(ui(navState(speedMps = 0.0).copy(bearingDegrees = 270f)))
            settle(host)
            assertEquals(90f, engine.headings.last()!!, 0.5f)
            // The zoom changes (slow now), but the bearing sent to the map is still the last one.
            assertEquals(90.0, engine.moves.last().state.bearing, 0.5)
        }
    }

    @Test fun `after the user pans the camera is left alone, and recentering eases back into the view`() {
        withHost { host ->
            host.render(ui())
            settle(host)
            val before = engine.moves.size
            host.render(ui(following = false))
            settle(host)
            assertEquals(before, engine.moves.size) // not following: the camera is left alone
            engine.current = engine.current.copy(tilt = 0.0, zoom = 15.0) // the user flattened and zoomed out
            host.render(ui(following = true)) // recenter
            assertEquals(15.0, engine.moves.last().state.zoom, 0.05) // starts where the camera is
            settle(host)
            assertTrue(engine.moves.last().state.tilt >= 54.9)
            assertTrue(engine.moves.last().state.zoom > 16.5)
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
            // A second inactive snapshot does nothing more, and the frames stay off.
            host.render(NavUi())
            settle(host)
            assertEquals(1, engine.moves.size)
            assertNull(engine.user)
        }
    }

    @Test fun `a new trip eases into the tilt again`() {
        withHost { host ->
            host.render(ui())
            host.render(NavUi())
            engine.moves.clear()
            clock += 5_000
            host.render(ui())
            settle(host)
            assertTrue(engine.moves.isNotEmpty())
            assertTrue(engine.moves.last().state.tilt >= 54.9)
        }
    }

    @Test fun `the overview leaves the camera to the engine and following resumes with an ease`() {
        withHost { host ->
            host.render(ui())
            settle(host)
            val before = engine.moves.size
            clock += 2_000
            host.render(ui(following = false, overview = true))
            settle(host)
            assertEquals(before, engine.moves.size) // frameRoute is the engine's own animation, no follow update
            engine.current = engine.current.copy(tilt = 0.0)
            clock += 8_000
            host.render(ui(following = true, overview = false))
            assertEquals(0.0, engine.moves.last().state.tilt, 0.1) // starts from the overview camera
            settle(host)
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
