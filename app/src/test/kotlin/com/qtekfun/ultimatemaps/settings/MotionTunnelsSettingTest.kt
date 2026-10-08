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
import kotlinx.coroutines.flow.MutableStateFlow
import com.qtekfun.ultimatemaps.nav.AndroidStopGoSignal
import com.qtekfun.ultimatemaps.nav.MotionSampleSource
import com.qtekfun.ultimatemaps.settings.backup.SettingsSchema
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class MotionQuietGuide : VoiceGuide {
    override val status = MutableStateFlow<VoiceStatus>(VoiceStatus.Idle)
    override fun prepare(language: VoiceLanguage) = Unit
    override fun speak(utterance: Utterance) = Unit
    override fun stop() = Unit
    override fun setVolume(percent: Int) = Unit
    override fun retry(language: VoiceLanguage) = Unit
    override fun shutdown() = Unit
}

/** The "Use motion sensors in tunnels" switch: default, UI, persistence, backup and the wiring to the signal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MotionTunnelsSettingTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store = InMemoryNavSettingsStore()

    @Test fun `it is on by default`() {
        assertTrue(NavSettings().motionSensorsInTunnels)
    }

    @Test fun `the switch shows and changes the setting`() {
        val env = NavigationSettingsEnv(store, MotionQuietGuide(), { Locale.ENGLISH })
        rule.setContent { MapasTheme(darkTheme = false) { Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { NavigationSection(env) } } }
        rule.onNodeWithTag("nav_motion_tunnels_switch").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("nav_motion_tunnels_switch").performScrollTo().performClick()
        rule.waitForIdle()
        assertFalse(store.settings.value.motionSensorsInTunnels)
    }

    @Test fun `the choice survives a restart and a damaged value means on`() {
        val prefs = context.getSharedPreferences("test_motion_tunnels_1", Context.MODE_PRIVATE)
        PrefsNavSettingsStore(prefs).update { it.copy(motionSensorsInTunnels = false) }
        assertFalse(PrefsNavSettingsStore(prefs).settings.value.motionSensorsInTunnels)
        prefs.edit().putString(PrefsNavSettingsStore.KEY_MOTION_TUNNELS, "maybe").commit()
        assertTrue(PrefsNavSettingsStore(prefs).settings.value.motionSensorsInTunnels)
    }

    @Test fun `it is in the settings backup`() {
        val spec = SettingsSchema.specs.single { it.prefsName == PrefsNavSettingsStore.PREFS && it.key == PrefsNavSettingsStore.KEY_MOTION_TUNNELS }
        assertEquals(true, spec.default)
    }

    @Test fun `the signal reads the live setting so the switch takes effect without a restart`() {
        var starts = 0
        val source = object : MotionSampleSource {
            override fun start(sink: MotionSampleSource.Sink): Boolean {
                starts++
                return true
            }
            override fun stop() = Unit
        }
        val signal = AndroidStopGoSignal(source, { store.settings.value.motionSensorsInTunnels })
        store.update { it.copy(motionSensorsInTunnels = false) }
        signal.motionState(0L)
        assertEquals(0, starts)
        store.update { it.copy(motionSensorsInTunnels = true) }
        signal.motionState(0L)
        assertEquals(1, starts)
    }
}
