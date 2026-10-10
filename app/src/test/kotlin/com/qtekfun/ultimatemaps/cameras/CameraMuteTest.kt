package com.qtekfun.ultimatemaps.cameras

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import com.qtekfun.ultimatemaps.nav.NavActions
import com.qtekfun.ultimatemaps.nav.NavPhase
import com.qtekfun.ultimatemaps.nav.NavScreen
import com.qtekfun.ultimatemaps.nav.NavUi
import com.qtekfun.ultimatemaps.nav.navState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The alerts' own mute on the navigation screen, and its persisted flag. The screen model side is in NavCameraMuteControllerTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CameraMuteTest {
    @get:Rule
    val rule = createComposeRule()

    private var ui by mutableStateOf(NavUi())
    private val calls = mutableListOf<String>()
    private val actions = NavActions(onVoice = { calls += "voice:$it" }, onCameraVoice = { calls += "camera:$it" })

    private fun driving(cameraAlertsOn: Boolean = true, cameraVoiceOn: Boolean = true, voiceOn: Boolean = true, glove: Boolean = false) =
        NavUi(
            phase = NavPhase.ON_ROUTE, nav = navState(), voiceOn = voiceOn, glove = glove, etaMillis = 1_700_000_000_000L,
            cameraAlertsOn = cameraAlertsOn, cameraVoiceOn = cameraVoiceOn,
        )

    private fun show(initial: NavUi) {
        ui = initial
        rule.setContent { NavScreen(ui, actions, dark = false) }
    }

    private fun text(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    @Test fun theButtonIsHiddenWhileNoCameraOrIncidentCategoryIsOn() {
        show(driving(cameraAlertsOn = false))
        rule.onNodeWithTag("nav_mute").assertIsDisplayed()
        rule.onNodeWithTag("nav_camera_mute").assertDoesNotExist()
    }

    @Test fun theButtonShowsTheStateDescribesItAndAsksForTheOpposite() {
        show(driving())
        assertEquals("", text("nav_camera_mute"), "an icon button: no text, the state is in its description")
        rule.onNodeWithTag("nav_camera_mute").assertContentDescriptionEquals("Camera and incident alerts on. Tap to mute their sound and voice")
        rule.onNodeWithTag("nav_camera_mute").performClick()
        ui = driving(cameraVoiceOn = false)
        rule.waitForIdle()
        assertEquals("", text("nav_camera_mute"))
        rule.onNodeWithTag("nav_camera_mute").assertContentDescriptionEquals("Camera and incident alerts muted. Tap to turn their sound and voice back on")
        rule.onNodeWithTag("nav_camera_mute").performClick()
        assertEquals(listOf("camera:false", "camera:true"), calls, "it never touches the navigation voice")
    }

    @Test fun theNavigationMuteIsSeparate() {
        show(driving())
        rule.onNodeWithTag("nav_mute").performClick()
        assertEquals(listOf("voice:false"), calls)
    }

    @Test fun theButtonIsGloveSized() {
        show(driving(glove = true))
        rule.onNodeWithTag("nav_camera_mute").assertHeightIsAtLeast(56.dp)
    }

    private fun prefs(name: String) = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun theModesAndTheMutedFlagArePersistedWithTheOtherCameraPrefs() {
        val prefs = prefs("camera_mute_test")
        val a = PrefsCameraSettingsStore(prefs)
        assertEquals(AlertSoundMode.SOUND, a.settings.value.cameraAlertMode, "a chime by default")
        assertEquals(AlertSoundMode.SOUND, a.settings.value.incidentAlertMode)
        assertFalse(a.settings.value.alertsMuted)
        a.update { it.copy(cameraAlertMode = AlertSoundMode.VOICE, incidentAlertMode = AlertSoundMode.SILENT, alertsMuted = true) }
        assertEquals("VOICE", prefs.getString("cam_alert_mode", null))
        assertEquals("SILENT", prefs.getString("incident_alert_mode", null))
        assertTrue(prefs.getBoolean("alerts_muted", false))
        val b = PrefsCameraSettingsStore(prefs).settings.value
        assertEquals(AlertSoundMode.VOICE, b.cameraAlertMode)
        assertEquals(AlertSoundMode.SILENT, b.incidentAlertMode)
        assertTrue(b.alertsMuted, "survives a restart")
        a.update { it.copy(incidentsEnabled = true) }
        assertEquals(b.cameraAlertMode, PrefsCameraSettingsStore(prefs).settings.value.cameraAlertMode, "other switches do not reset it")
    }

    @Test fun theOldSharedVoiceFlagIsMigratedToBothCategoriesAndNeverWritten() {
        val on = prefs("camera_migrate_on").also { it.edit().putBoolean("voice_alerts", true).commit() }
        val a = PrefsCameraSettingsStore(on).settings.value
        assertEquals(AlertSoundMode.SOUND, a.cameraAlertMode)
        assertEquals(AlertSoundMode.SOUND, a.incidentAlertMode)
        val off = prefs("camera_migrate_off").also { it.edit().putBoolean("voice_alerts", false).commit() }
        val store = PrefsCameraSettingsStore(off)
        assertEquals(AlertSoundMode.SILENT, store.settings.value.cameraAlertMode)
        assertEquals(AlertSoundMode.SILENT, store.settings.value.incidentAlertMode)
        store.update { it.copy(cameraAlertMode = AlertSoundMode.SOUND) }
        assertFalse(off.getBoolean("voice_alerts", true), "the old key is left as it was")
        val again = PrefsCameraSettingsStore(off).settings.value
        assertEquals(AlertSoundMode.SOUND, again.cameraAlertMode, "an explicit mode wins over the old flag")
        assertEquals(AlertSoundMode.SILENT, again.incidentAlertMode, "the other category was migrated and stored")
    }

    @Test fun anUnknownStoredModeFallsBackToTheDefault() {
        val p = prefs("camera_bad_mode").also { it.edit().putString("cam_alert_mode", "LOUD").commit() }
        assertEquals(AlertSoundMode.SOUND, PrefsCameraSettingsStore(p).settings.value.cameraAlertMode)
    }
}
