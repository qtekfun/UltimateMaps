package com.qtekfun.ultimatemaps.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.core.voice.InMemoryNavSettingsStore
import com.qtekfun.ultimatemaps.core.voice.NavSettings
import com.qtekfun.ultimatemaps.core.voice.Utterance
import com.qtekfun.ultimatemaps.core.voice.VoiceGuide
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguage
import com.qtekfun.ultimatemaps.core.voice.VoiceStatus
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class StillGuide : VoiceGuide {
    override val status = MutableStateFlow<VoiceStatus>(VoiceStatus.Idle)
    override fun prepare(language: VoiceLanguage) = Unit
    override fun speak(utterance: Utterance) = Unit
    override fun stop() = Unit
    override fun setVolume(percent: Int) = Unit
    override fun retry(language: VoiceLanguage) = Unit
    override fun shutdown() = Unit
}

/** The "3D view" and "3D buildings" settings: defaults, switches, persistence and damaged values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class Nav3dSettingsTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store = InMemoryNavSettingsStore()

    private fun show() {
        val env = NavigationSettingsEnv(store, StillGuide(), { Locale.ENGLISH })
        rule.setContent { MapasTheme(darkTheme = false) { Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { NavigationSection(env) } } }
    }

    private fun click(tag: String) { rule.onNodeWithTag(tag).performScrollTo().performClick(); rule.waitForIdle() }

    @Test fun `both are on by default`() {
        assertTrue(NavSettings().view3d)
        assertTrue(NavSettings().buildings3d)
    }

    @Test fun `the Navigation section has the 3D view switch and, while it is on, the buildings switch`() {
        show()
        rule.onNodeWithTag("nav_view3d_switch").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("nav_buildings3d_switch").performScrollTo().assertIsDisplayed()
        click("nav_buildings3d_switch")
        assertFalse(store.settings.value.buildings3d)
        assertTrue(store.settings.value.view3d)
        click("nav_view3d_switch")
        assertFalse(store.settings.value.view3d)
        rule.onNodeWithTag("nav_buildings3d_switch").assertDoesNotExist() // no 3D view, no buildings to choose
        click("nav_view3d_switch")
        assertTrue(store.settings.value.view3d)
        rule.onNodeWithTag("nav_buildings3d_switch").assertExists()
    }

    @Test fun `the choices survive a restart`() {
        val prefs = context.getSharedPreferences("test_nav_3d_1", Context.MODE_PRIVATE)
        PrefsNavSettingsStore(prefs).update { it.copy(view3d = false, buildings3d = false) }
        val again = PrefsNavSettingsStore(prefs).settings.value
        assertFalse(again.view3d)
        assertFalse(again.buildings3d)
        PrefsNavSettingsStore(prefs).update { it.copy(view3d = true) }
        assertEquals(NavSettings(view3d = true, buildings3d = false), PrefsNavSettingsStore(prefs).settings.value)
    }

    @Test fun `an empty or damaged file means both on`() {
        val prefs = context.getSharedPreferences("test_nav_3d_2", Context.MODE_PRIVATE)
        assertEquals(NavSettings(), PrefsNavSettingsStore(prefs).settings.value)
        prefs.edit().putString(PrefsNavSettingsStore.KEY_VIEW_3D, "maybe").putInt(PrefsNavSettingsStore.KEY_BUILDINGS_3D, 3).commit()
        val s = PrefsNavSettingsStore(prefs).settings.value
        assertTrue(s.view3d)
        assertTrue(s.buildings3d)
    }

    @Test fun `the toggle of the navigation screen and the Settings switch share the same store value`() {
        val prefs = context.getSharedPreferences("test_nav_3d_3", Context.MODE_PRIVATE)
        val a = PrefsNavSettingsStore(prefs)
        a.update { it.copy(view3d = false) } // what NavScreenController.setView3d does
        assertFalse(PrefsNavSettingsStore(prefs).settings.value.view3d) // what the Settings screen reads
    }
}
