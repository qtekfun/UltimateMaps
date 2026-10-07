package com.qtekfun.ultimatemaps.transit.follow

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
