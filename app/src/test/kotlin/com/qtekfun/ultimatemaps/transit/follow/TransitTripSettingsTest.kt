package com.qtekfun.ultimatemaps.transit.follow

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import com.qtekfun.ultimatemaps.core.voice.InMemoryNavSettingsStore
import com.qtekfun.ultimatemaps.settings.NavigationSection
import com.qtekfun.ultimatemaps.settings.NavigationSettingsEnv
import com.qtekfun.ultimatemaps.settings.backup.SettingsSchema
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TransitTripSettingsTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun prefs(name: String = "test_transit_trip") = context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun theDefaultIsVoiceAndTheChoiceIsStored() {
        val p = prefs()
        val s = PrefsTransitTripSettings(p)
        assertEquals(AlertSoundMode.VOICE, s.promptMode.value)
        s.setPromptMode(AlertSoundMode.SOUND)
        assertEquals("SOUND", p.getString(PrefsTransitTripSettings.KEY_PROMPTS, null))
        assertEquals(AlertSoundMode.SOUND, PrefsTransitTripSettings(p).promptMode.value)
    }

    @Test fun aDamagedValueFallsBackToTheDefault() {
        val p = prefs()
        p.edit().putString(PrefsTransitTripSettings.KEY_PROMPTS, "LOUD").commit()
        assertEquals(AlertSoundMode.VOICE, PrefsTransitTripSettings(p).promptMode.value)
    }

    @Test fun reloadReadsWhatARestoreWrote() {
        val p = prefs()
        val s = PrefsTransitTripSettings(p)
        p.edit().putString(PrefsTransitTripSettings.KEY_PROMPTS, "SILENT").commit()
        s.reload()
        assertEquals(AlertSoundMode.SILENT, s.promptMode.value)
    }

    @Test fun theChoiceIsInTheBackupWhitelistAndAnUnknownValueIsRejected() {
        val spec = assertNotNull(SettingsSchema.find(SettingsSchema.GROUP_NAVIGATION, PrefsTransitTripSettings.KEY_PROMPTS))
        assertEquals(PrefsTransitTripSettings.PREFS, spec.prefsName)
        assertEquals("VOICE", spec.default)
        assertEquals("SOUND", spec.sanitize("SOUND"))
        assertNull(spec.sanitize("LOUD"))
    }

    @Test fun cercaniasRealTimeIsOffByDefaultAndTheChoiceIsStored() {
        val p = prefs()
        val s = PrefsTransitTripSettings(p)
        assertEquals(false, s.realTimeEnabled.value)
        s.setRealTimeEnabled(true)
        assertEquals(true, p.getBoolean(PrefsTransitTripSettings.KEY_REAL_TIME, false))
        assertEquals(true, PrefsTransitTripSettings(p).realTimeEnabled.value)
        s.setRealTimeEnabled(false)
        assertEquals(false, PrefsTransitTripSettings(p).realTimeEnabled.value)
    }

    @Test fun aDamagedRealTimeValueIsOffAndReloadReadsARestore() {
        val p = prefs()
        p.edit().putString(PrefsTransitTripSettings.KEY_REAL_TIME, "yes").commit()
        val s = PrefsTransitTripSettings(p)
        assertEquals(false, s.realTimeEnabled.value)
        p.edit().clear().putBoolean(PrefsTransitTripSettings.KEY_REAL_TIME, true).commit()
        s.reload()
        assertEquals(true, s.realTimeEnabled.value)
    }

    @Test fun cercaniasRealTimeIsInTheBackupWhitelistAndNeedsConsentToTurnOn() {
        val spec = assertNotNull(SettingsSchema.find(SettingsSchema.GROUP_NAVIGATION, PrefsTransitTripSettings.KEY_REAL_TIME))
        assertEquals(PrefsTransitTripSettings.PREFS, spec.prefsName)
        assertEquals(false, spec.default)
        assertEquals(com.qtekfun.ultimatemaps.settings.backup.RestorePolicy.NEEDS_CONSENT, spec.policy)
        assertEquals(true, spec.sanitize(true))
    }

    @Test fun theSwitchIsInSettingsWithTheNoteAndTurnsItOnAndOff() {
        val store = InMemoryTransitTripSettings()
        rule.setContent {
            MapasTheme(darkTheme = false) {
                Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                    NavigationSection(NavigationSettingsEnv(InMemoryNavSettingsStore(), FakeGuide(), transitTrip = store))
                }
            }
        }
        rule.onNodeWithTag("nav_transit_rt_card").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Asks Renfe's server for train delays while you use public transport. Your position is never sent; Renfe sees your IP address.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("nav_transit_rt_switch").performScrollTo().performClick()
        assertEquals(true, store.realTimeEnabled.value)
        rule.onNodeWithTag("nav_transit_rt_switch").performScrollTo().performClick()
        assertEquals(false, store.realTimeEnabled.value)
    }

    @Test fun settingsNavigationOffersTheThreeModesAndAChoiceIsSaved() {
        val store = InMemoryTransitTripSettings()
        rule.setContent {
            MapasTheme(darkTheme = false) {
                Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                    NavigationSection(NavigationSettingsEnv(InMemoryNavSettingsStore(), FakeGuide(), transitTrip = store))
                }
            }
        }
        rule.onNodeWithTag("nav_transit_prompts_card").performScrollTo().assertIsDisplayed()
        for (m in listOf("voice", "sound", "silent")) rule.onNodeWithTag("nav_transit_prompts_$m").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("nav_transit_prompts_sound").performScrollTo().performClick()
        assertEquals(AlertSoundMode.SOUND, store.promptMode.value)
    }

    @Test fun withoutATransitStoreTheCardIsHidden() {
        rule.setContent {
            MapasTheme(darkTheme = false) {
                Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { NavigationSection(NavigationSettingsEnv(InMemoryNavSettingsStore(), FakeGuide())) }
            }
        }
        rule.onNodeWithTag("nav_transit_prompts_card").assertDoesNotExist()
    }
}
