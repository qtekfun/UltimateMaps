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
import com.qtekfun.ultimatemaps.core.transit.TransitMode
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

    // ---- planner options: allowed modes and walking limits

    @Test fun plannerOptionsHaveTheOwnerApprovedDefaultsAndAreStored() {
        val p = prefs()
        val s = PrefsTransitTripSettings(p)
        assertEquals(TransitPlanningDefaults.ALL_MODES, s.allowedModes.value)
        assertEquals(listOf(20, 5, 15), listOf(s.walkAlternativeMin.value, s.minSavingMin.value, s.maxWalkMin.value))
        val options = s.planOptions()
        assertEquals(listOf(1200, 300, 900), listOf(options.walkAlternativeMaxSec, options.minTransitSavingSec, options.maxTotalWalkSec))
        assertEquals(com.qtekfun.ultimatemaps.core.transit.TransitMode.ALL, options.modes)

        s.setAllowedModes(setOf(TransitMode.BUS, TransitMode.TRAM, TransitMode.OTHER)) // OTHER is not switchable: dropped
        s.setWalkAlternativeMin(30)
        s.setMinSavingMin(10)
        s.setMaxWalkMin(0)
        val again = PrefsTransitTripSettings(p)
        assertEquals(setOf(TransitMode.BUS, TransitMode.TRAM), again.allowedModes.value)
        assertEquals(listOf(30, 10, 0), listOf(again.walkAlternativeMin.value, again.minSavingMin.value, again.maxWalkMin.value))
        assertEquals(setOf(TransitMode.BUS, TransitMode.TRAM, TransitMode.OTHER), again.planOptions().modes)
        assertEquals(0, again.planOptions().maxTotalWalkSec)
    }

    @Test fun anEmptyModeChoiceIsKeptAndDamagedPlannerValuesFallBack() {
        val p = prefs()
        val s = PrefsTransitTripSettings(p)
        s.setAllowedModes(emptySet())
        assertEquals(emptySet(), PrefsTransitTripSettings(p).allowedModes.value)
        p.edit().putInt(PrefsTransitTripSettings.KEY_MODES, 3).putString(PrefsTransitTripSettings.KEY_WALK_ALT, "x").putInt(PrefsTransitTripSettings.KEY_MIN_SAVING, -4).putInt(PrefsTransitTripSettings.KEY_MAX_WALK, 9999).commit()
        val d = PrefsTransitTripSettings(p)
        assertEquals(TransitPlanningDefaults.ALL_MODES, d.allowedModes.value)
        assertEquals(listOf(20, 5, 15), listOf(d.walkAlternativeMin.value, d.minSavingMin.value, d.maxWalkMin.value))
    }

    @Test fun reloadReadsARestoredPlannerChoice() {
        val p = prefs()
        val s = PrefsTransitTripSettings(p)
        p.edit().putStringSet(PrefsTransitTripSettings.KEY_MODES, setOf("METRO", "WARP")).putInt(PrefsTransitTripSettings.KEY_MAX_WALK, 10).commit()
        s.reload()
        assertEquals(setOf(TransitMode.METRO), s.allowedModes.value)
        assertEquals(10, s.maxWalkMin.value)
    }

    @Test fun plannerOptionsAreInTheBackupWhitelist() {
        val modes = assertNotNull(SettingsSchema.find(SettingsSchema.GROUP_NAVIGATION, PrefsTransitTripSettings.KEY_MODES))
        assertEquals(PrefsTransitTripSettings.PREFS, modes.prefsName)
        assertEquals(setOf("BUS", "METRO", "TRAM", "TRAIN", "FERRY"), modes.default)
        assertEquals(setOf("BUS"), modes.sanitize(setOf("BUS", "OTHER", "x")))
        for ((key, default) in listOf(
            PrefsTransitTripSettings.KEY_WALK_ALT to 20, PrefsTransitTripSettings.KEY_MIN_SAVING to 5, PrefsTransitTripSettings.KEY_MAX_WALK to 15,
        )) {
            val spec = assertNotNull(SettingsSchema.find(SettingsSchema.GROUP_NAVIGATION, key))
            assertEquals(PrefsTransitTripSettings.PREFS, spec.prefsName)
            assertEquals(default, spec.default)
            assertEquals(30, spec.sanitize(30))
            assertEquals(0, spec.sanitize(0))
            assertNull(spec.sanitize(-1))
            assertNull(spec.sanitize(100000))
        }
    }

    @Test fun settingsShowThePlannerCardsAndAChoiceIsSaved() {
        val store = InMemoryTransitTripSettings()
        rule.setContent {
            MapasTheme(darkTheme = false) {
                Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                    NavigationSection(NavigationSettingsEnv(InMemoryNavSettingsStore(), FakeGuide(), transitTrip = store))
                }
            }
        }
        for (card in listOf("transit_plan_walk_card", "transit_plan_saving_card", "transit_plan_maxwalk_card")) rule.onNodeWithTag(card).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Show walking when it takes under").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Prefer walking if transit saves less than").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Max walking per trip").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("transit_plan_walk_30").performScrollTo().performClick()
        rule.onNodeWithTag("transit_plan_saving_10").performScrollTo().performClick()
        rule.onNodeWithTag("transit_plan_maxwalk_0").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(30, 10, 0), listOf(store.walkAlternativeMin.value, store.minSavingMin.value, store.maxWalkMin.value))
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
