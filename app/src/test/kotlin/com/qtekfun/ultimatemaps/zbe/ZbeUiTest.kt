package com.qtekfun.ultimatemaps.zbe

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.qtekfun.ultimatemaps.core.zbe.InMemoryZbeSettingsStore
import com.qtekfun.ultimatemaps.core.zbe.ZbeCrossing
import com.qtekfun.ultimatemaps.core.zbe.ZbeDataManager
import com.qtekfun.ultimatemaps.core.zbe.ZbePolygon
import com.qtekfun.ultimatemaps.core.zbe.ZbeRing
import com.qtekfun.ultimatemaps.core.zbe.ZbeSettings
import com.qtekfun.ultimatemaps.core.zbe.ZbeZone
import com.qtekfun.ultimatemaps.route.LowEmissionWarning
import com.qtekfun.ultimatemaps.settings.SettingsEnv
import com.qtekfun.ultimatemaps.settings.SettingsScreen
import com.qtekfun.ultimatemaps.settings.ZbeSettingsEnv
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ZbeUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val square = listOf(ZbePolygon(listOf(ZbeRing(doubleArrayOf(40.0, -3.0, 40.0, -2.9, 40.1, -2.9, 40.1, -3.0)))))
    private fun crossing(name: String, restriction: String = "") =
        ZbeCrossing(ZbeZone(name, name, "Madrid", restriction, square), LatLon(40.0, -3.0), 100.0, 900.0, startsInside = false, endsInside = false)

    private val forbidden = Regex("allowed|banned|forbidden|prohibited|permitted|sticker|label", RegexOption.IGNORE_CASE)

    private fun resource(id: Int) = ctx.getString(id)

    // ------------------------------------------------------------------------------------------------ route warning

    @Test fun theRouteWarningNamesTheZoneAndAsksToCheckTheRulesWithoutClaimingAnything() {
        rule.setContent { MapasTheme(darkTheme = false) { LowEmissionWarning(listOf(crossing("Centro", "Zona de especial protección"), crossing("Centro"))) } }
        rule.onNodeWithTag("route_zbe_text").assertIsDisplayed()
        val texts = listOf("route_zbe_text", "route_zbe_names", "route_zbe_tagged", "route_zbe_notice").map { tag ->
            rule.onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
        }
        assertEquals("This route enters a low-emission zone: check the access rules of the city", texts[0])
        assertEquals("Zone: Centro (Madrid)", texts[1], "the same zone twice is listed once")
        assertEquals("Tagged in OpenStreetMap: Zona de especial protección", texts[2])
        assertTrue(texts[3].contains("Based on OpenStreetMap") && texts[3].contains("incomplete or out of date") && texts[3].contains("official rules"), texts[3])
        assertFalse(forbidden.containsMatchIn(texts.joinToString(" ")))
    }

    @Test fun noCrossingsDrawsNothing() {
        rule.setContent { MapasTheme(darkTheme = false) { LowEmissionWarning(emptyList()) } }
        assertEquals(0, rule.onAllNodesWithTag("route_zbe_warning").fetchSemanticsNodes().size)
    }

    // ------------------------------------------------------------------------------------------------ navigation banner

    @Test fun theAheadChipShowsTheTitleTheDistanceAndTheHint() {
        rule.setContent { MapasTheme(darkTheme = false) { ZbeAheadChip(ZbeBannerState("Centro (Madrid)", 350)) } }
        rule.onNodeWithTag("zbe_ahead").assertIsDisplayed()
        rule.onNodeWithTag("zbe_ahead_title", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("zbe_ahead_distance", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("zbe_ahead_hint", useUnmergedTree = true).assertIsDisplayed()
        assertFalse(forbidden.containsMatchIn(resource(R.string.zbe_ahead_title) + resource(R.string.zbe_ahead_hint)))
    }

    @Test fun noPromptDrawsNothing() {
        rule.setContent { MapasTheme(darkTheme = false) { ZbeAheadChip(null) } }
        assertEquals(0, rule.onAllNodesWithTag("zbe_ahead").fetchSemanticsNodes().size)
    }

    // ------------------------------------------------------------------------------------------------ settings

    private val policy = DefaultNetworkPolicy().also { it.offlineMode = true } // any download attempt is refused
    private val store = InMemoryZbeSettingsStore()
    private var assetCalls = 0
    private val data = ZbeDataManager(
        store, policy, { assetCalls++; null }, Files.createTempDirectory("zbeui").toFile(),
        io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun showSettings() {
        val env = SettingsEnv(
            com.qtekfun.ultimatemaps.core.fuel.InMemoryFuelSettingsStore(),
            com.qtekfun.ultimatemaps.core.fuel.FuelDataManager(
                com.qtekfun.ultimatemaps.core.fuel.InMemoryFuelSettingsStore(), policy, policy::addEndpoint, policy::removeEndpoint,
                com.qtekfun.ultimatemaps.core.fuel.FuelCache(Files.createTempDirectory("fuelc").toFile()), com.qtekfun.ultimatemaps.core.fuel.FuelClient(policy),
            ),
            policy, offline = { true }, setOffline = {}, catalogUrl = { "" }, openMaps = {},
            cameras = com.qtekfun.ultimatemaps.settings.CamerasSettingsEnv(
                com.qtekfun.ultimatemaps.core.cameras.InMemoryCameraSettingsStore(),
                com.qtekfun.ultimatemaps.core.cameras.CameraDataManager(
                    com.qtekfun.ultimatemaps.core.cameras.InMemoryCameraSettingsStore(), policy, { null }, Files.createTempDirectory("camz").toFile(),
                    io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
                ),
                com.qtekfun.ultimatemaps.core.cameras.IncidentDataManager(
                    com.qtekfun.ultimatemaps.core.cameras.InMemoryCameraSettingsStore(), policy, policy::addEndpoint, policy::removeEndpoint,
                    com.qtekfun.ultimatemaps.core.cameras.IncidentCache(Files.createTempDirectory("incz").toFile()),
                    scope = CoroutineScope(Dispatchers.Unconfined), io = Dispatchers.Unconfined,
                ),
                offline = { true }, locale = { Locale.ENGLISH },
            ),
            zbe = ZbeSettingsEnv(store, data, locale = { Locale.ENGLISH }),
        )
        data.start()
        rule.setContent { MapasTheme(darkTheme = false) { SettingsScreen(env, onBack = {}, initialCategory = "alerts") } }
    }

    @Test fun everythingIsOffByDefaultAndNothingConnects() {
        showSettings()
        assertEquals(ZbeSettings(), store.settings.value)
        assertFalse(store.settings.value.enabled)
        rule.onNodeWithTag("zbe_switch").performScrollTo().assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("zbe_data_card").fetchSemanticsNodes().size, "no options while the switch is off")
        assertEquals(0, assetCalls)
        assertTrue(policy.recentConnections().isEmpty())
    }

    @Test fun turningTheSwitchOnShowsTheOptionsTheNoticeAndTheAttribution() {
        showSettings()
        rule.onNodeWithTag("zbe_switch").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(store.settings.value.enabled)
        rule.onNodeWithTag("zbe_map_switch").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("zbe_prompt_sound").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("zbe_notice").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("zbe_attribution").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("zbe_prompt_voice").performScrollTo().performClick()
        assertEquals(AlertSoundMode.VOICE, store.settings.value.promptMode)
        rule.onNodeWithTag("zbe_map_switch").performScrollTo().performClick()
        assertFalse(store.settings.value.showOnMap)
        assertFalse(store.settings.value.layerVisible)
        assertTrue(assetCalls > 0, "the switch asked for the file (refused: offline mode)")
    }

    @Test fun thePreferencesRoundTripAndDefaultsAreOff() {
        val prefs = ctx.getSharedPreferences("zbe-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val a = PrefsZbeSettingsStore(prefs)
        assertEquals(ZbeSettings(), a.settings.value)
        a.update { it.copy(enabled = true, showOnMap = false, promptMode = AlertSoundMode.SILENT) }
        val b = PrefsZbeSettingsStore(prefs)
        assertEquals(ZbeSettings(enabled = true, showOnMap = false, promptMode = AlertSoundMode.SILENT), b.settings.value)
        prefs.edit().putString(PrefsZbeSettingsStore.KEY_PROMPT_MODE, "garbage").commit()
        a.reload()
        assertEquals(AlertSoundMode.SOUND, a.settings.value.promptMode, "an unknown value falls back to the default")
    }
}
