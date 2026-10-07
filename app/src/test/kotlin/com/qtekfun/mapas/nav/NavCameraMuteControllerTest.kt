package com.qtekfun.mapas.nav

import com.qtekfun.mapas.core.cameras.InMemoryCameraSettingsStore
import org.junit.After
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The alerts' own mute in the screen model: separate from the navigation Mute, persisted through the camera store. */
class NavCameraMuteControllerTest {
    private val rigs = ArrayList<NavTestRig>()
    private val store = InMemoryCameraSettingsStore()

    private fun rig() = NavTestRig(cameraSettings = store).also { rigs += it }

    @After fun tearDown() = rigs.forEach { it.close() }

    @Test fun `the button is offered only while some category is on`() {
        val r = rig()
        assertFalse(r.screen.ui.value.cameraAlertsOn)
        store.update { it.copy(incidentsEnabled = true) }
        r.await("category on") { it.cameraAlertsOn }
        store.update { it.copy(incidentsEnabled = false) }
        r.await("category off") { !it.cameraAlertsOn }
    }

    @Test fun `muting writes only the camera flag and the screen follows it in both directions`() {
        val r = rig()
        r.screen.setCameraVoice(false)
        r.await("alerts muted") { !it.cameraVoiceOn }
        assertFalse(store.settings.value.voiceEnabled)
        assertTrue(r.settings.settings.value.voiceEnabled, "the navigation voice is untouched")
        assertTrue(r.screen.ui.value.voiceOn)
        store.update { it.copy(voiceEnabled = true) } // from Settings
        r.await("alerts unmuted from Settings") { it.cameraVoiceOn }
    }

    @Test fun `the navigation mute does not change the alerts flag`() {
        val r = rig()
        r.screen.setVoice(false)
        r.await("nav muted") { !it.voiceOn }
        assertTrue(r.screen.ui.value.cameraVoiceOn)
        assertTrue(store.settings.value.voiceEnabled)
    }
}
