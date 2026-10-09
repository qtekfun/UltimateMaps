package com.qtekfun.ultimatemaps.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.cameras.HazardCardController
import com.qtekfun.ultimatemaps.cameras.HazardCardState
import com.qtekfun.ultimatemaps.cameras.HazardDescriber
import com.qtekfun.ultimatemaps.cameras.PrefsCameraSettingsStore
import com.qtekfun.ultimatemaps.core.cameras.AxisSense
import com.qtekfun.ultimatemaps.core.cameras.CameraAttribution
import com.qtekfun.ultimatemaps.core.cameras.CameraDataManager
import com.qtekfun.ultimatemaps.core.cameras.CameraDataset
import com.qtekfun.ultimatemaps.core.cameras.CameraKind
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import com.qtekfun.ultimatemaps.core.cameras.CameraSettings
import com.qtekfun.ultimatemaps.core.cameras.CameraSources
import com.qtekfun.ultimatemaps.core.cameras.IncidentCache
import com.qtekfun.ultimatemaps.core.cameras.IncidentData
import com.qtekfun.ultimatemaps.core.cameras.IncidentDataManager
import com.qtekfun.ultimatemaps.core.cameras.IncidentKind
import com.qtekfun.ultimatemaps.core.cameras.InMemoryCameraSettingsStore
import com.qtekfun.ultimatemaps.core.cameras.MobileZone
import com.qtekfun.ultimatemaps.core.cameras.SpeedCamera
import com.qtekfun.ultimatemaps.core.cameras.TrafficIncident
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CamerasSettingsTest {
    @get:Rule
    val rule = createComposeRule()

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val policy = DefaultNetworkPolicy().also { it.offlineMode = true } // any download attempt is refused and logged
    private val store = InMemoryCameraSettingsStore()
    private var assetCalls = 0
    private val cameras = CameraDataManager(
        store, policy, { assetCalls++; null }, Files.createTempDirectory("camui").toFile(),
        io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
    )
    private val incidents = IncidentDataManager(
        store, policy, policy::addEndpoint, policy::removeEndpoint, IncidentCache(Files.createTempDirectory("incui").toFile()),
        scope = CoroutineScope(Dispatchers.Unconfined), io = Dispatchers.Unconfined,
    )
    private var changed = 0

    private fun show() {
        val fuelPolicy = policy
        val env = SettingsEnv(
            com.qtekfun.ultimatemaps.core.fuel.InMemoryFuelSettingsStore(),
            com.qtekfun.ultimatemaps.core.fuel.FuelDataManager(
                com.qtekfun.ultimatemaps.core.fuel.InMemoryFuelSettingsStore(), fuelPolicy, fuelPolicy::addEndpoint, fuelPolicy::removeEndpoint,
                com.qtekfun.ultimatemaps.core.fuel.FuelCache(Files.createTempDirectory("fuelc").toFile()), com.qtekfun.ultimatemaps.core.fuel.FuelClient(fuelPolicy),
            ),
            fuelPolicy, offline = { true }, setOffline = {}, catalogUrl = { "" }, openMaps = {},
            cameras = CamerasSettingsEnv(store, cameras, incidents, offline = { true }, onChanged = { changed++ }, locale = { Locale.ENGLISH }),
        )
        cameras.start()
        incidents.start()
        rule.setContent { MapasTheme(darkTheme = false) { SettingsScreen(env, onBack = {}, initialCategory = "alerts") } }
    }

    private fun count(tag: String) = rule.onAllNodesWithTagCount(tag)

    @Test fun everythingIsOffByDefaultAndTheLegalNoteIsShown() {
        show()
        val s = store.settings.value
        assertFalse(s.fixedEnabled || s.mobileZonesEnabled || s.incidentsEnabled || s.v16Enabled)
        rule.onNodeWithTag("cam_legal_note").performScrollTo().assertIsDisplayed()
        assertEquals(0, count("cam_data_card"), "no data card while everything is off")
        assertEquals(0, assetCalls)
        assertTrue(policy.recentConnections().isEmpty(), "no connection attempt at all")
    }

    @Test fun cameraSwitchesAskForTheAcknowledgementFirst() {
        show()
        rule.onNodeWithTag("cam_fixed_switch").performScrollTo().performClick()
        rule.onNodeWithTag("cam_confirm_dialog").assertIsDisplayed()
        assertFalse(store.settings.value.fixedEnabled, "nothing changes before the user accepts")
        rule.onNodeWithTag("cam_confirm_no").performClick()
        rule.waitForIdle()
        assertFalse(store.settings.value.fixedEnabled)
        assertFalse(store.settings.value.acknowledged)
        assertEquals(0, count("cam_confirm_dialog"))
        rule.onNodeWithTag("cam_fixed_switch").performScrollTo().performClick()
        rule.onNodeWithTag("cam_confirm_yes").performClick()
        rule.waitForIdle()
        assertTrue(store.settings.value.fixedEnabled && store.settings.value.acknowledged)
        assertTrue(changed > 0)
        assertEquals(1, assetCalls, "turning it on asks the catalog for the file (here: none listed)")
        assertTrue(policy.recentConnections().isEmpty(), "and no catalog file means no connection")
        // The second camera category does not ask again.
        rule.onNodeWithTag("cam_mobile_switch").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(store.settings.value.mobileZonesEnabled)
        assertEquals(0, count("cam_confirm_dialog"))
        rule.onNodeWithTag("cam_data_card").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("cam_data_cameras").assertIsDisplayed()
    }

    @Test fun trafficNeedsExplicitConsentAndGoesThroughThePolicyWhichOfflineModeStops() {
        show()
        rule.onNodeWithTag("cam_incidents_switch").performScrollTo().performClick()
        rule.onNodeWithTag("cam_traffic_confirm_dialog").assertIsDisplayed()
        assertFalse(store.settings.value.incidentsEnabled)
        assertTrue(policy.recentConnections().isEmpty())
        rule.onNodeWithTag("cam_traffic_confirm_no").performClick()
        rule.waitForIdle()
        assertFalse(store.settings.value.incidentsEnabled)
        assertTrue(policy.recentConnections().isEmpty(), "declined: nothing was even attempted")
        rule.onNodeWithTag("cam_incidents_switch").performScrollTo().performClick()
        rule.onNodeWithTag("cam_traffic_confirm_yes").performClick()
        rule.waitForIdle()
        assertTrue(store.settings.value.incidentsEnabled)
        val attempts = policy.recentConnections()
        assertEquals(listOf("nap.dgt.es"), attempts.map { it.host }.distinct())
        assertTrue(attempts.none { it.allowed }, "offline mode refused it")
        rule.onNodeWithTag("cam_error").performScrollTo().assertIsDisplayed()
        // V16 does not ask again (the consent covers the one national file).
        rule.onNodeWithTag("cam_v16_switch").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(store.settings.value.v16Enabled)
        assertEquals(0, count("cam_traffic_confirm_dialog"))
        rule.onNodeWithTag("cam_attribution_incidents").performScrollTo().assertIsDisplayed()
    }

    @Test fun roadworksAndSpeedingOptionsAppearOnlyWithTheirParents() {
        show()
        assertEquals(0, count("cam_roadworks_switch"))
        assertEquals(0, count("cam_only_speeding_switch"))
        store.update { it.copy(acknowledged = true, fixedEnabled = true, incidentsEnabled = true) }
        rule.waitForIdle()
        rule.onNodeWithTag("cam_roadworks_switch").performScrollTo().performClick()
        rule.onNodeWithTag("cam_only_speeding_switch").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(store.settings.value.roadworksEnabled && store.settings.value.warnOnlyIfSpeeding)
    }

    @Test fun eachCategoryHasItsOwnThreeWayAlertModeShownWhileTheCategoryIsOn() {
        show()
        assertEquals(0, count("cam_alert_mode_card"), "nothing to announce while every category is off")
        assertEquals(0, count("incident_alert_mode_card"))
        store.update { it.copy(acknowledged = true, incidentsEnabled = true) }
        rule.waitForIdle()
        assertEquals(0, count("cam_alert_mode_card"), "cameras are still off")
        assertEquals(AlertSoundMode.SOUND, store.settings.value.incidentAlertMode, "a chime by default")
        rule.onNodeWithTag("incident_alert_mode_voice").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(AlertSoundMode.VOICE, store.settings.value.incidentAlertMode)
        assertEquals(AlertSoundMode.SOUND, store.settings.value.cameraAlertMode, "the camera mode is untouched")
        store.update { it.copy(fixedEnabled = true) }
        rule.waitForIdle()
        rule.onNodeWithTag("cam_alert_mode_silent").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(AlertSoundMode.SILENT, store.settings.value.cameraAlertMode)
        assertEquals(AlertSoundMode.VOICE, store.settings.value.incidentAlertMode, "the incident mode is untouched")
        assertTrue(store.settings.value.incidentsEnabled && store.settings.value.fixedEnabled, "only the mode changed")
        rule.onNodeWithTag("cam_alert_mode_sound").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(AlertSoundMode.SOUND, store.settings.value.cameraAlertMode)
    }

    // ---- preferences, strings, cards ----

    @Test fun preferencesKeepTheDefaultsOffAndNormaliseTamperedValues() {
        val prefs = ctx.getSharedPreferences("cam-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val a = PrefsCameraSettingsStore(prefs)
        assertEquals(CameraSettings(), a.settings.value)
        a.update { it.copy(acknowledged = true, fixedEnabled = true, v16Enabled = true, incidentRefreshMinutes = 30) }
        val b = PrefsCameraSettingsStore(prefs)
        assertTrue(b.settings.value.fixedEnabled && b.settings.value.v16Enabled)
        assertEquals(30, b.settings.value.incidentRefreshMinutes)
        prefs.edit().putBoolean(PrefsCameraSettingsStore.KEY_ACK, false).putInt(PrefsCameraSettingsStore.KEY_REFRESH, 1).commit()
        val c = PrefsCameraSettingsStore(prefs)
        assertFalse(c.settings.value.fixedEnabled, "a camera layer cannot be on without the acknowledgement, even from a hand-edited file")
        assertEquals(5, c.settings.value.incidentRefreshMinutes)
    }

    @Test fun cameraStringsExistInEnglishAndSpanishWithTheSameKeys() {
        fun keys(f: String) = Regex("<string name=\"([^\"]+)\"(?![^>]*translatable=\"false\")").findAll(File(f).readText()).map { it.groupValues[1] }.toSet()
        val en = keys("src/main/res/values/strings_cameras.xml")
        val es = keys("src/main/res/values-es/strings_cameras.xml")
        assertEquals(en, es)
        assertTrue(en.size > 60)
        assertTrue("cam_legal_note" in en && "cam_confirm_body" in en)
        assertEquals(CameraAttribution.DGT_EN.contains("CC BY"), true)
    }

    @Test fun cardsDescribeCamerasZonesAndIncidentsWithAttributionAndHonestWording() {
        val cams = com.qtekfun.ultimatemaps.core.cameras.CameraDataRepository().also {
            it.install(
                CameraDataset(
                    1_791_376_200L, CameraSources.DGT or CameraSources.OSM,
                    listOf(SpeedCamera("f0", CameraKind.FIXED, LatLon(40.0, -3.0), null, "A-2", 90, null, AxisSense.BOTH, CameraSources.DGT or CameraSources.OSM)),
                    emptyList(),
                    listOf(MobileZone("z0", "CM-412", "Albacete", 158_850, 168_520, listOf(LatLon(39.0, -2.0), LatLon(39.1, -2.0)))),
                ),
            )
        }
        val incs = com.qtekfun.ultimatemaps.core.cameras.IncidentDataRepository().also {
            it.install(IncidentData(1_791_376_200_000L, null, listOf(TrafficIncident("9", IncidentKind.V16, "N-540", LatLon(42.9, -7.5), null, 315, "Lugo", "Lugo", 3.76, 1_791_375_000_000L, null))))
        }
        val d = HazardDescriber(ctx, cams, incs) { Locale.ENGLISH }
        val fixed = assertNotNull(d.describe("cam:f0"))
        assertEquals("Possible fixed speed camera", fixed.title)
        assertTrue(fixed.lines.any { it.contains("90 km/h") })
        assertTrue(fixed.attribution.contains("OpenStreetMap") && fixed.attribution.contains("CC BY"))
        val zone = assertNotNull(d.describe("zone:z0"))
        assertTrue(zone.lines.any { it.contains("158.9") && it.contains("168.5") }, "kilometre range as published")
        assertTrue(zone.note!!.contains("not as a position"))
        val v16 = assertNotNull(d.describe("inc:9"))
        assertEquals("Stopped vehicle with a V16 beacon", v16.title)
        assertTrue(v16.lines.any { it.contains("Lugo (Lugo)") })
        assertTrue(v16.note!!.contains("may have changed"))
        assertNull(d.describe("cam:gone"))
        assertNull(d.describe("other:1"))
        val state = HazardCardState()
        var opened = 0
        val c = HazardCardController({ d.describe(it) }, state, onOpened = { opened++ })
        c.onTap("cam:f0")
        assertEquals("Possible fixed speed camera", state.info?.title)
        assertEquals(1, opened)
        c.onTap("cam:gone")
        assertEquals(1, opened, "a stale id opens nothing")
        state.close()
        assertNull(state.info)
        assertEquals(R.string.hazard_card_close, R.string.hazard_card_close)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
