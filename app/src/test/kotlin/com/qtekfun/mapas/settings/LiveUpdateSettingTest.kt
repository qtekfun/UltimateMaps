package com.qtekfun.mapas.settings

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
import com.qtekfun.mapas.core.voice.InMemoryNavSettingsStore
import com.qtekfun.mapas.core.voice.NavSettings
import com.qtekfun.mapas.core.voice.Utterance
import com.qtekfun.mapas.core.voice.VoiceGuide
import com.qtekfun.mapas.core.voice.VoiceLanguage
import com.qtekfun.mapas.core.voice.VoiceStatus
import com.qtekfun.mapas.settings.backup.SettingsSchema
import com.qtekfun.mapas.ui.theme.MapasTheme
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

private class QuietGuide : VoiceGuide {
    override val status = MutableStateFlow<VoiceStatus>(VoiceStatus.Idle)
    override fun prepare(language: VoiceLanguage) = Unit
    override fun speak(utterance: Utterance) = Unit
    override fun stop() = Unit
    override fun setVolume(percent: Int) = Unit
    override fun retry(language: VoiceLanguage) = Unit
    override fun shutdown() = Unit
}

/** The "Show distance in the status bar" switch: default, visibility by API level, persistence and backup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LiveUpdateSettingTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store = InMemoryNavSettingsStore()

    private fun show(available: Boolean) {
        val env = NavigationSettingsEnv(store, QuietGuide(), { Locale.ENGLISH }, liveUpdateAvailable = available)
        rule.setContent { MapasTheme(darkTheme = false) { Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { NavigationSection(env) } } }
    }

    @Test fun `it is on by default`() {
        assertTrue(NavSettings().liveUpdateChip)
    }

    @Test fun `the switch is hidden when the device is older than Android 16`() {
        show(available = false)
        rule.onNodeWithTag("nav_live_update_switch").assertDoesNotExist()
    }

    @Test fun `the switch shows on Android 16 and changes the setting`() {
        show(available = true)
        rule.onNodeWithTag("nav_live_update_switch").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("nav_live_update_switch").performScrollTo().performClick()
        rule.waitForIdle()
        assertFalse(store.settings.value.liveUpdateChip)
    }

    @Test fun `the default of the environment follows the API level`() {
        assertFalse(NavigationSettingsEnv(store, QuietGuide()).liveUpdateAvailable) // Robolectric SDK 34
    }

    @Test fun `the choice survives a restart and a damaged value means on`() {
        val prefs = context.getSharedPreferences("test_live_update_1", Context.MODE_PRIVATE)
        PrefsNavSettingsStore(prefs).update { it.copy(liveUpdateChip = false) }
        assertFalse(PrefsNavSettingsStore(prefs).settings.value.liveUpdateChip)
        prefs.edit().putString(PrefsNavSettingsStore.KEY_LIVE_UPDATE_CHIP, "maybe").commit()
        assertTrue(PrefsNavSettingsStore(prefs).settings.value.liveUpdateChip)
    }

    @Test fun `it is in the settings backup`() {
        val spec = SettingsSchema.specs.single { it.prefsName == PrefsNavSettingsStore.PREFS && it.key == PrefsNavSettingsStore.KEY_LIVE_UPDATE_CHIP }
        assertEquals(true, spec.default)
    }
}
