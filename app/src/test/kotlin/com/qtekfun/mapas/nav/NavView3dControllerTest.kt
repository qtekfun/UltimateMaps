package com.qtekfun.mapas.nav

import com.qtekfun.mapas.core.nav.NavTrip
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The 3D/2D choice and the route overview in the screen model: persisted through the settings store, no real map. */
class NavView3dControllerTest {
    private val rigs = ArrayList<NavTestRig>()

    private fun rig(overviewMillis: Long = 60_000L) = NavTestRig(overviewMillis = overviewMillis).also { rigs += it }

    @After fun tearDown() = rigs.forEach { it.close() }

    /** A simulated trip frozen at its first fix, so that the screen model stays active without moving. */
    private fun NavTestRig.beginParked() {
        gate = CompletableDeferred()
        gateWhen = { true }
        assertTrue(screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = true))
        await("active") { it.active }
    }

    @Test fun `3D and the buildings are on by default`() {
        val r = rig()
        assertTrue(r.screen.ui.value.view3d)
        assertTrue(r.screen.ui.value.buildings3d)
    }

    @Test fun `the toggle writes the navigation setting and the screen follows it`() {
        val r = rig()
        r.screen.setView3d(false)
        r.await("2D") { !it.view3d }
        assertFalse(r.settings.settings.value.view3d)
        r.screen.setView3d(true)
        r.await("3D") { it.view3d }
        assertTrue(r.settings.settings.value.view3d)
    }

    @Test fun `the mute button writes the voice setting and the screen follows it`() {
        val r = rig()
        assertTrue(r.screen.ui.value.voiceOn)
        r.screen.setVoice(false)
        r.await("muted") { !it.voiceOn }
        assertFalse(r.settings.settings.value.voiceEnabled)
        r.settings.update { it.copy(voiceEnabled = true) }
        r.await("unmuted from Settings") { it.voiceOn }
    }

    @Test fun `the choice made in Settings reaches a trip in progress`() {
        val r = rig()
        r.beginParked()
        r.settings.update { it.copy(view3d = false, buildings3d = false) }
        val ui = r.await("settings applied") { !it.view3d && !it.buildings3d }
        assertTrue(ui.active)
        r.settings.update { it.copy(view3d = true) }
        r.await("3D back") { it.view3d && !it.buildings3d }
    }

    @Test fun `a trip starts in the saved mode and keeps it after the trip ends`() {
        val r = rig()
        r.settings.update { it.copy(view3d = false) }
        r.await("2D") { !it.view3d }
        r.beginParked()
        assertFalse(r.screen.ui.value.view3d)
        r.screen.stop()
        assertFalse(r.screen.ui.value.view3d)
        assertFalse(r.screen.ui.value.active)
    }

    @Test fun `the overview stops following until recenter, which ends it`() {
        val r = rig()
        r.beginParked()
        r.screen.showOverview()
        var ui = r.screen.ui.value
        assertTrue(ui.overview)
        assertFalse(ui.following)
        r.screen.recenter()
        ui = r.screen.ui.value
        assertFalse(ui.overview)
        assertTrue(ui.following)
    }

    @Test fun `touching the map during the overview ends it and does not yank the camera back later`() {
        val r = rig(overviewMillis = 1L)
        r.beginParked()
        r.screen.showOverview()
        r.screen.onUserMovedMap()
        val ui = r.screen.ui.value
        assertFalse(ui.overview)
        assertFalse(ui.following)
        // The cancelled timer never recenters: give the thread a few turns and check it is still not following.
        repeat(5) { r.settle() }
        assertFalse(r.screen.ui.value.following)
    }

    @Test fun `after its time the overview returns to following by itself`() {
        val r = rig(overviewMillis = 1L)
        r.beginParked()
        r.screen.showOverview()
        val ui = r.await("back to following") { it.following && !it.overview }
        assertEquals(true, ui.active)
    }

    @Test fun `there is no overview without a trip, nor after arrival`() {
        val r = rig()
        r.screen.showOverview()
        assertFalse(r.screen.ui.value.overview)
    }

    @Test fun `stopping clears the overview`() {
        val r = rig()
        r.beginParked()
        r.screen.showOverview()
        r.screen.stop()
        assertFalse(r.screen.ui.value.overview)
        assertTrue(r.screen.ui.value.following)
    }
}
