package com.qtekfun.mapas.nav

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.CameraState
import com.qtekfun.mapas.core.map.MapEngine
import com.qtekfun.mapas.core.nav.NavTrip
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
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
 * Leaving and coming back: the navigation lives in the application (controller, model, service), so destroying the
 * activity loses nothing; the new activity just draws the same state again, with the screen kept on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NavRecreationTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val rig = NavTestRig()

    @After fun tearDown() = rig.close()

    /** Records what the navigation asks of the map. */
    private class FakeEngine : MapEngine {
        val cameras = mutableListOf<CameraState>()
        val routes = mutableListOf<Int>()
        var clears = 0
        var user: LatLon? = null
        var userCleared = 0
        var gesture: (() -> Unit)? = null
        override fun setCamera(center: LatLon, zoom: Double) = Unit
        override fun camera() = LatLon(0.0, 0.0) to 1.0
        override fun animateTo(state: CameraState, durationMillis: Int) { cameras += state }
        override fun showRoute(points: List<LatLon>, fit: Boolean) { routes += points.size }
        override fun clearRoute() { clears++ }
        override fun showUserLocation(point: LatLon?, accuracyMeters: Float?) { user = point; if (point == null) userCleared++ }
        override fun resetNorth() = Unit
        override fun setCameraGestureListener(listener: (() -> Unit)?) { gesture = listener }
        override fun close() = Unit
    }

    private val engine = FakeEngine()

    private fun mount(activity: ComponentActivity): NavHost {
        val host = NavHost(activity, engine, rig.screen, NavCamera(), now = { 10_000L })
        activity.setContent { host.Overlay(dark = false) }
        return host
    }

    private fun keepsScreenOn(scenario: ActivityScenario<ComponentActivity>): Boolean {
        var on = false
        scenario.onActivity { on = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
        return on
    }

    @Test fun `the screen comes back after the activity is destroyed, stays on, and is cleaned when the trip ends`() {
        // Freeze the simulated trip mid-route first, then compose: nothing changes while the screen is drawn.
        rig.gate = CompletableDeferred()
        rig.gateWhen = { (it.nav?.traveledMeters ?: 0.0) > 300.0 }
        rig.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = true)
        rig.await("past 300 m") { (it.nav?.traveledMeters ?: 0.0) > 300.0 }
        val frozen = rig.awaitParked()

        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { mount(it) }
        rule.waitForIdle()
        rule.onNodeWithTag("nav_banner").assertIsDisplayed()
        assertTrue(keepsScreenOn(scenario), "the window flag keeps the screen on while navigating")
        assertTrue(engine.cameras.isNotEmpty(), "the camera follows the user")
        assertEquals(frozen.nav!!.position, engine.user)
        assertTrue(engine.routes.isNotEmpty(), "the route line is drawn")
        val firstShown = frozen.nav!!.traveledMeters

        // Android destroys and recreates the activity (rotation, theme, memory): the navigation goes on without it.
        scenario.recreate()
        scenario.onActivity { mount(it) }
        rule.waitForIdle()
        rule.onNodeWithTag("nav_banner").assertIsDisplayed()
        assertTrue(keepsScreenOn(scenario), "the new window keeps the screen on too")
        assertEquals(firstShown, rig.screen.ui.value.nav!!.traveledMeters, 0.001) // same trip, same progress
        assertNotNull(rig.controller.state.value)
        assertEquals(0, rig.service.stops.get())

        // The user moves the map by hand: the screen stops following and offers to recenter.
        engine.gesture!!.invoke()
        rule.waitForIdle()
        rule.onNodeWithTag("nav_recenter").assertIsDisplayed()

        // The trip ends: route line and position are removed, the screen goes away and the flag is cleared.
        rig.gate!!.complete(Unit)
        rig.await("arrived") { it.phase == NavPhase.ARRIVED }
        rig.screen.stop()
        rule.waitForIdle()
        rule.onNodeWithTag("nav_banner").assertDoesNotExist()
        assertFalse(keepsScreenOn(scenario), "the flag is cleared when the navigation ends")
        assertTrue(engine.clears >= 1)
        assertNull(engine.user)
        assertNull(rig.store.load())
        scenario.close()
    }
}
