package com.qtekfun.mapas.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import com.qtekfun.mapas.core.cameras.CameraDataManager
import com.qtekfun.mapas.core.cameras.IncidentCache
import com.qtekfun.mapas.core.cameras.IncidentDataManager
import com.qtekfun.mapas.core.cameras.InMemoryCameraSettingsStore
import com.qtekfun.mapas.core.fuel.FuelCache
import com.qtekfun.mapas.core.fuel.FuelClient
import com.qtekfun.mapas.core.fuel.FuelDataManager
import com.qtekfun.mapas.core.fuel.FuelTypes
import com.qtekfun.mapas.core.fuel.InMemoryFuelSettingsStore
import com.qtekfun.mapas.core.net.DefaultNetworkPolicy
import com.qtekfun.mapas.core.voice.InMemoryNavSettingsStore
import com.qtekfun.mapas.core.voice.Utterance
import com.qtekfun.mapas.core.voice.VoiceGuide
import com.qtekfun.mapas.core.voice.VoiceLanguage
import com.qtekfun.mapas.core.voice.VoiceLanguagePref
import com.qtekfun.mapas.core.voice.VoiceStatus
import com.qtekfun.mapas.search.HistorySettings
import com.qtekfun.mapas.ui.theme.MapasTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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

private class HubQuietGuide : VoiceGuide {
    override val status = MutableStateFlow<VoiceStatus>(VoiceStatus.Idle)
    override fun prepare(language: VoiceLanguage) = Unit
    override fun speak(utterance: Utterance) = Unit
    override fun stop() = Unit
    override fun setVolume(percent: Int) = Unit
    override fun retry(language: VoiceLanguage) = Unit
    override fun shutdown() = Unit
}

/** Builds a [SettingsEnv] whose stores are all in memory, shared by the hub tests. */
internal class HubFixture {
    val policy = DefaultNetworkPolicy()
    val fuelStore = InMemoryFuelSettingsStore()
    val navStore = InMemoryNavSettingsStore()
    val camStore = InMemoryCameraSettingsStore()
    var offline by mutableStateOf(false)
    var historyOn = true
    var exits = 0

    private val fuel = FuelDataManager(
        fuelStore, policy, policy::addEndpoint, policy::removeEndpoint,
        FuelCache(Files.createTempDirectory("hubfuel").toFile()), FuelClient(policy),
    )
    private val cameras = CameraDataManager(
        camStore, policy, { null }, Files.createTempDirectory("hubcam").toFile(),
        io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined),
    )
    private val incidents = IncidentDataManager(
        camStore, policy, policy::addEndpoint, policy::removeEndpoint, IncidentCache(Files.createTempDirectory("hubinc").toFile()),
        scope = CoroutineScope(Dispatchers.Unconfined), io = Dispatchers.Unconfined,
    )

    val env = SettingsEnv(
        fuelStore, fuel, policy, offline = { offline }, setOffline = { offline = it; policy.offlineMode = it },
        catalogUrl = { "https://example.org/catalog.json" }, openMaps = {},
        navigation = NavigationSettingsEnv(navStore, HubQuietGuide(), { Locale.ENGLISH }),
        history = HistorySettingsEnv(object : HistorySettings {
            override var enabled: Boolean
                get() = historyOn
                set(v) { historyOn = v }
        }, clear = {}),
        cameras = CamerasSettingsEnv(camStore, cameras, incidents, offline = { offline }, locale = { Locale.ENGLISH }),
        about = AboutSettingsEnv(version = "1.2.3", transitAttributions = { listOf("Test transit data") }),
    )
}

private fun SemanticsNodeInteractionsProvider.exists(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

/** Opens a category from the hub (the row is tagged `settings_row_<id>`). */
internal fun SemanticsNodeInteractionsProvider.openCategory(id: String) {
    onNodeWithTag("settings_row_$id").performClick()
}

/** Goes back with the arrow in the title bar. */
internal fun SemanticsNodeInteractionsProvider.tapBack() {
    onNodeWithTag("settings_back").performClick()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsHubTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val f = HubFixture()

    private fun show(fixture: HubFixture = f) {
        rule.setContent { MapasTheme(darkTheme = false) { SettingsScreen(fixture.env, onBack = { fixture.exits++ }) } }
    }

    @Test fun theHubListsEveryCategoryWithATitleAndAOneLineSummary() {
        f.navStore.update { it.copy(voiceEnabled = true, voiceLanguage = VoiceLanguagePref.ES, volumePercent = 100) }
        show()
        listOf("navigation", "alerts", "fuel", "network", "data", "about").forEach {
            rule.onNodeWithTag("settings_row_$it").assertIsDisplayed()
        }
        rule.onNodeWithTag("settings_summary_navigation", useUnmergedTree = true).assertTextEquals("Voice: Spanish, 100 %")
        rule.onNodeWithTag("settings_summary_alerts", useUnmergedTree = true).assertTextEquals("Cameras: off · Traffic: off")
        rule.onNodeWithTag("settings_summary_fuel", useUnmergedTree = true).assertTextEquals("Off")
        rule.onNodeWithTag("settings_summary_network", useUnmergedTree = true).assertTextEquals("Offline mode: off")
        rule.onNodeWithTag("settings_summary_data", useUnmergedTree = true).assertTextEquals("Searches: on")
        rule.onNodeWithTag("settings_summary_about", useUnmergedTree = true).assertTextEquals("Version 1.2.3")
    }

    @Test fun summariesFollowTheSettings() {
        show()
        f.fuelStore.update { it.copy(enabled = true, downloadedFuels = setOf("glp", "gnc"), mapFuel = "glp") }
        f.navStore.update { it.copy(voiceEnabled = false) }
        f.camStore.update { it.copy(fixedEnabled = true, acknowledged = true) }
        f.offline = true
        rule.waitForIdle()
        rule.onNodeWithTag("settings_summary_fuel", useUnmergedTree = true).assertTextEquals("2 fuels, ${FuelTypes.byId("glp")!!.displayName} on the map")
        rule.onNodeWithTag("settings_summary_navigation", useUnmergedTree = true).assertTextEquals("Voice: off")
        rule.onNodeWithTag("settings_summary_alerts", useUnmergedTree = true).assertTextEquals("Cameras: on · Traffic: off")
        rule.onNodeWithTag("settings_summary_network", useUnmergedTree = true).assertTextEquals("Offline mode: on")
    }

    @Test fun aRowOpensItsCategoryAndTheArrowGoesBackToTheHub() {
        show()
        rule.openCategory("navigation")
        rule.onNodeWithTag("nav_voice_switch").assertIsDisplayed()
        rule.onNodeWithTag("settings_heading").assertTextEquals("Navigation and voice")
        assertFalse(rule.exists("settings_hub"))
        rule.tapBack()
        rule.onNodeWithTag("settings_hub").assertIsDisplayed()
        assertFalse(rule.exists("nav_voice_switch"))
        assertEquals(0, f.exits, "the arrow inside a category only returns to the hub")
        rule.tapBack()
        assertEquals(1, f.exits, "the arrow on the hub leaves Settings")
    }

    @Test fun theSystemBackReturnsToTheHubFirst() {
        show()
        rule.openCategory("network")
        rule.onNodeWithTag("offline_switch").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("settings_hub").assertIsDisplayed()
    }

    @Test fun searchFiltersTheRowsByTitleSummaryAndKeywords() {
        show()
        rule.onNodeWithTag("settings_search").performTextInput("toll")
        rule.waitForIdle()
        rule.onNodeWithTag("settings_row_navigation").assertIsDisplayed() // keyword of Navigation and voice
        assertFalse(rule.exists("settings_row_fuel"))
        rule.onNodeWithTag("settings_search").performTextClearance()
        rule.onNodeWithTag("settings_search").performTextInput("zzzz")
        rule.waitForIdle()
        rule.onNodeWithTag("settings_no_match").assertIsDisplayed()
        rule.onNodeWithTag("settings_search").performTextClearance()
        rule.onNodeWithTag("settings_search").performTextInput("backup")
        rule.waitForIdle()
        rule.openCategory("data")
        rule.onNodeWithTag("history_switch").assertIsDisplayed()
    }

    @Test fun theMatcherNeedsEveryWordAndIgnoresCase() {
        assertTrue(matches("", "anything"))
        assertTrue(matches("VOICE km", "Navigation and voice", "kilometers km"))
        assertFalse(matches("voice fuel", "Navigation and voice"))
    }

    @Test fun theDataCategorySeparatesTheDestructiveAction() {
        show()
        rule.openCategory("data")
        rule.onNodeWithTag("history_clear").assertIsDisplayed()
        rule.onNodeWithTag("history_clear").performClick()
        rule.onNodeWithTag("history_cleared").assertIsDisplayed()
    }

    @Test fun theAboutCategoryShowsVersionLicenceAndAttributions() {
        show()
        rule.openCategory("about")
        rule.onNodeWithTag("about_version").assertTextEquals("1.2.3")
        rule.onNodeWithTag("about_osm").assertIsDisplayed()
        rule.onNodeWithTag("about_fuel").assertIsDisplayed()
        rule.onNodeWithTag("about_source").assertIsDisplayed()
        rule.onNodeWithTag("about_transit").assertIsDisplayed()
    }

    @Test fun theHubAndTheRowsAreAccessible() {
        show()
        val heading = rule.onNodeWithTag("settings_heading").fetchSemanticsNode()
        assertTrue(heading.config.contains(SemanticsProperties.Heading), "the title is a heading")
        val back = rule.onNodeWithTag("settings_back").fetchSemanticsNode()
        assertEquals("Back", back.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull())
        val density = rule.density
        listOf("navigation", "alerts", "fuel", "network", "data", "about").forEach { id ->
            val node = rule.onNodeWithTag("settings_row_$id").fetchSemanticsNode()
            assertTrue(with(density) { node.size.height.toDp().value } >= 48f, "$id row is at least 48 dp high")
            assertTrue(node.config.contains(SemanticsProperties.Role))
        }
    }
}

/** Rotation: the activity is destroyed and recreated; the open category and the Advanced group survive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsHubRotationTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val f = HubFixture()

    private fun mount(activity: ComponentActivity) =
        activity.setContent { MapasTheme(darkTheme = false) { SettingsScreen(f.env, onBack = {}) } }

    @Test fun theOpenCategoryAndTheAdvancedGroupAreKeptOnRotation() {
        f.fuelStore.update { it.copy(enabled = true) }
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { mount(it) }
        rule.waitForIdle()
        rule.openCategory("fuel")
        rule.onNodeWithTag("fuel_advanced_header").performScrollTo().performClick()
        rule.onNodeWithTag("fuel_url_field").assertExists()

        scenario.recreate()
        scenario.onActivity { mount(it) }
        rule.waitForIdle()
        rule.onNodeWithTag("fuel_switch").assertExists()
        rule.onNodeWithTag("fuel_url_field").assertExists()
        assertFalse(rule.exists("settings_hub"))
        rule.tapBack()
        rule.onNodeWithTag("settings_hub").assertIsDisplayed()

        scenario.recreate()
        scenario.onActivity { mount(it) }
        rule.waitForIdle()
        rule.onNodeWithTag("settings_hub").assertIsDisplayed()
        scenario.close()
    }
}
