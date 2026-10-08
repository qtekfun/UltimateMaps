package com.qtekfun.ultimatemaps.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.qtekfun.ultimatemaps.settings.backup.SettingsSchema
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

private class FocusQuietGuide : VoiceGuide {
    override val status = MutableStateFlow<VoiceStatus>(VoiceStatus.Idle)
    override fun prepare(language: VoiceLanguage) = Unit
    override fun speak(utterance: Utterance) = Unit
    override fun stop() = Unit
    override fun setVolume(percent: Int) = Unit
    override fun retry(language: VoiceLanguage) = Unit
    override fun shutdown() = Unit
}

/** Settings > Navigation > Driving focus: the status-bar switch, the Do Not Disturb shortcut, persistence and backup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DrivingFocusSettingTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store = InMemoryNavSettingsStore()
    private var dndOpened = 0

    private fun show() {
        val env = NavigationSettingsEnv(store, FocusQuietGuide(), { Locale.ENGLISH }, openDoNotDisturb = { dndOpened++ })
        rule.setContent { MapasTheme(darkTheme = false) { Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { NavigationSection(env) } } }
    }

    @Test fun `hiding the status bar is off by default`() {
        assertFalse(NavSettings().hideStatusBar)
    }

    @Test fun `the switch changes the setting`() {
        show()
        rule.onNodeWithTag("nav_hide_status_bar_switch").performScrollTo().assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertTrue(store.settings.value.hideStatusBar)
    }

    @Test fun `the button opens the Do Not Disturb settings and the note is shown`() {
        show()
        rule.onNodeWithTag("nav_dnd_note").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("nav_open_dnd").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, dndOpened)
    }

    @Test fun `the choice survives a restart and a damaged value means off`() {
        val prefs = context.getSharedPreferences("test_driving_focus_1", Context.MODE_PRIVATE)
        PrefsNavSettingsStore(prefs).update { it.copy(hideStatusBar = true) }
        assertTrue(PrefsNavSettingsStore(prefs).settings.value.hideStatusBar)
        prefs.edit().putString(PrefsNavSettingsStore.KEY_HIDE_STATUS_BAR, "maybe").commit()
        assertFalse(PrefsNavSettingsStore(prefs).settings.value.hideStatusBar)
    }

    @Test fun `it is in the settings backup`() {
        val spec = SettingsSchema.specs.single { it.prefsName == PrefsNavSettingsStore.PREFS && it.key == PrefsNavSettingsStore.KEY_HIDE_STATUS_BAR }
        assertEquals(false, spec.default)
    }
}
