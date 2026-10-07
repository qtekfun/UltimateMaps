package com.qtekfun.ultimatemaps.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.core.voice.InMemoryNavSettingsStore
import com.qtekfun.ultimatemaps.core.voice.NavSettings
import com.qtekfun.ultimatemaps.core.voice.UnitsPref
import com.qtekfun.ultimatemaps.core.voice.Utterance
import com.qtekfun.ultimatemaps.core.voice.VoiceFailure
import com.qtekfun.ultimatemaps.core.voice.VoiceGuide
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguage
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguagePref
import com.qtekfun.ultimatemaps.core.voice.VoiceStatus
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeGuide(initial: VoiceStatus = VoiceStatus.Idle) : VoiceGuide {
    override val status = MutableStateFlow(initial)
    val spoken = ArrayList<Utterance>()
    val prepared = ArrayList<VoiceLanguage>()
    val retried = ArrayList<VoiceLanguage>()
    var lastVolume = -1
    override fun prepare(language: VoiceLanguage) { prepared += language }
    override fun speak(utterance: Utterance) { spoken += utterance }
    override fun stop() = Unit
    override fun setVolume(percent: Int) { lastVolume = percent }
    override fun retry(language: VoiceLanguage) { retried += language }
    override fun shutdown() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NavigationSettingsTest {
    @get:Rule
    val rule = createComposeRule()

    private val store = InMemoryNavSettingsStore()
    private var guide = FakeGuide()
    private var locale = Locale.forLanguageTag("es-ES")

    private fun show() {
        val env = NavigationSettingsEnv(store, guide, { locale })
        rule.setContent { MapasTheme(darkTheme = false) { Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { NavigationSection(env) } } }
    }

    private fun click(tag: String) { rule.onNodeWithTag(tag).performScrollTo().performClick(); rule.waitForIdle() }

    @Test fun defaultsAreSensible() {
        val d = store.settings.value
        assertTrue(d.voiceEnabled)
        assertEquals(100, d.volumePercent)
        assertEquals(UnitsPref.AUTO, d.units)
        assertEquals(VoiceLanguagePref.AUTO, d.voiceLanguage)
        assertFalse(d.avoidMotorways || d.avoidTolls || d.avoidFerries || d.avoidUnpaved)
        assertEquals(com.qtekfun.ultimatemaps.core.routing.BikeCycleways.OFF, d.bikeCycleways)
    }

    @Test fun theSwitchesChangeTheSettings() {
        show()
        click("nav_voice_switch")
        assertFalse(store.settings.value.voiceEnabled)
        rule.onNodeWithTag("nav_volume_card").assertDoesNotExist()
        click("nav_voice_switch")
        click("nav_important_switch")
        assertTrue(store.settings.value.importantOnly)
        click("nav_avoid_motorways"); click("nav_avoid_tolls"); click("nav_avoid_ferries"); click("nav_avoid_unpaved")
        val s = store.settings.value
        assertTrue(s.avoidMotorways && s.avoidTolls && s.avoidFerries && s.avoidUnpaved)
        assertTrue(s.routeOptions().avoidUnpaved)
    }

    @Test fun volumeUnitsAndLanguageAreChosenWithRadios() {
        show()
        click("nav_volume_50")
        click("nav_units_imperial")
        click("nav_language_en")
        val s = store.settings.value
        assertEquals(50, s.volumePercent)
        assertEquals(UnitsPref.IMPERIAL, s.units)
        assertEquals(VoiceLanguagePref.EN, s.voiceLanguage)
        assertEquals(VoiceLanguage.EN, guide.prepared.last(), "choosing a language checks that it has a voice")
    }

    @Test fun testVoiceSpeaksARealPromptInTheChosenLanguageUnitsAndVolume() {
        store.update { it.copy(volumePercent = 75, units = UnitsPref.IMPERIAL, voiceLanguage = VoiceLanguagePref.EN) }
        show()
        click("nav_test_voice")
        val u = guide.spoken.single()
        assertEquals("Navigation voice is on. In 0.2 miles, turn left", u.text)
        assertEquals(VoiceLanguage.EN, u.language)
        assertEquals(75, guide.lastVolume)
    }

    @Test fun testVoiceWorksEvenWithTheVoiceOff() {
        store.update { it.copy(voiceEnabled = false) }
        show()
        click("nav_test_voice")
        assertEquals(1, guide.spoken.size)
    }

    @Test fun noGuideNoticeWhileTheVoiceWorks() {
        guide = FakeGuide(VoiceStatus.Ready(VoiceLanguage.ES))
        show()
        rule.onNodeWithTag("nav_voice_ready").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("voice_problem_notice").assertDoesNotExist()
    }

    @Test fun withoutAnEngineTheGuideExplainsAndPointsToFDroid() {
        guide = FakeGuide(VoiceStatus.NoEngine)
        show()
        rule.onNodeWithTag("voice_problem_notice").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("voice_install_com.github.olga_yakovleva.rhvoice.android").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("voice_install_com.reecedunn.espeak").assertExists()
        rule.onNodeWithTag("voice_open_tts_settings").assertExists()
        click("voice_retry")
        assertEquals(listOf(VoiceLanguage.ES), guide.retried)
    }

    @Test fun missingLanguageDataOffersToInstallIt() {
        guide = FakeGuide(VoiceStatus.LanguageMissing(VoiceLanguage.ES))
        show()
        rule.onNodeWithTag("voice_install_data").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("voice_install_com.reecedunn.espeak").assertDoesNotExist()
    }

    @Test fun aFailedEngineOffersARetry() {
        guide = FakeGuide(VoiceStatus.Failed(VoiceFailure.ENGINE_UNRESPONSIVE))
        show()
        rule.onNodeWithTag("voice_retry").performScrollTo().assertIsDisplayed()
    }

    @Test fun theSectionStartsTheEngineToDetectProblemsEarly() {
        show()
        assertEquals(listOf(VoiceLanguage.ES), guide.prepared)
    }

    // ---- The preferences store

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun preferencesSurviveARestart() {
        val prefs = context.getSharedPreferences("test_nav_1", Context.MODE_PRIVATE)
        PrefsNavSettingsStore(prefs).update { it.copy(voiceEnabled = false, importantOnly = true, volumePercent = 50, units = UnitsPref.METRIC, voiceLanguage = VoiceLanguagePref.ES, avoidTolls = true, avoidFerries = true) }
        val again = PrefsNavSettingsStore(prefs).settings.value
        assertEquals(NavSettings(false, true, 50, UnitsPref.METRIC, VoiceLanguagePref.ES, avoidTolls = true, avoidFerries = true), again)
    }

    @Test fun aDamagedFileFallsBackToDefaults() {
        val prefs = context.getSharedPreferences("test_nav_2", Context.MODE_PRIVATE)
        prefs.edit().putString(PrefsNavSettingsStore.KEY_UNITS, "FURLONGS").putString(PrefsNavSettingsStore.KEY_VOICE, "yes").putString(PrefsNavSettingsStore.KEY_VOLUME, "loud").putInt(PrefsNavSettingsStore.KEY_LANGUAGE, 7).commit()
        assertEquals(NavSettings(), PrefsNavSettingsStore(prefs).settings.value)
    }

    @Test fun anOutOfRangeVolumeIsClamped() {
        val prefs = context.getSharedPreferences("test_nav_3", Context.MODE_PRIVATE)
        prefs.edit().putInt(PrefsNavSettingsStore.KEY_VOLUME, 900).commit()
        assertEquals(100, PrefsNavSettingsStore(prefs).settings.value.volumePercent)
        PrefsNavSettingsStore(prefs).update { it.copy(volumePercent = 0) }
        assertEquals(NavSettings.MIN_VOLUME, PrefsNavSettingsStore(prefs).settings.value.volumePercent)
    }

    @Test fun anUpdateWithNoChangeWritesNothing() {
        val prefs = context.getSharedPreferences("test_nav_4", Context.MODE_PRIVATE)
        PrefsNavSettingsStore(prefs).update { it }
        assertNull(prefs.getString(PrefsNavSettingsStore.KEY_UNITS, null))
    }

    @Test fun theBikeCycleLevelIsChosenWithRadiosAndFeedsTheRouteOptions() {
        show()
        for (level in com.qtekfun.ultimatemaps.core.routing.BikeCycleways.entries) {
            click("nav_bike_cycleways_${level.name.lowercase()}")
            assertEquals(level, store.settings.value.bikeCycleways)
            assertEquals(level, store.settings.value.routeOptions().bikeCycleways)
        }
    }

    @Test fun theBikeCycleLevelSurvivesARestartAndAnUnknownValueFallsBackToOff() {
        val prefs = context.getSharedPreferences("test_nav_5", Context.MODE_PRIVATE)
        PrefsNavSettingsStore(prefs).update { it.copy(bikeCycleways = com.qtekfun.ultimatemaps.core.routing.BikeCycleways.STRONGLY_PREFER) }
        assertEquals(com.qtekfun.ultimatemaps.core.routing.BikeCycleways.STRONGLY_PREFER, PrefsNavSettingsStore(prefs).settings.value.bikeCycleways)
        prefs.edit().putString(PrefsNavSettingsStore.KEY_BIKE_CYCLEWAYS, "TELEPORT").commit()
        assertEquals(com.qtekfun.ultimatemaps.core.routing.BikeCycleways.OFF, PrefsNavSettingsStore(prefs).settings.value.bikeCycleways)
    }
}
