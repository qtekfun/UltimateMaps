package com.qtekfun.ultimatemaps.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.core.fuel.FuelAttribution
import com.qtekfun.ultimatemaps.core.fuel.FuelCache
import com.qtekfun.ultimatemaps.core.fuel.FuelClient
import com.qtekfun.ultimatemaps.core.fuel.FuelDataManager
import com.qtekfun.ultimatemaps.core.fuel.FuelSettings
import com.qtekfun.ultimatemaps.core.fuel.InMemoryFuelSettingsStore
import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.qtekfun.ultimatemaps.fuel.PrefsFuelSettingsStore
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES)))
    private val store = InMemoryFuelSettingsStore()
    private var offline by mutableStateOf(false)
    private val manager = FuelDataManager(
        store, policy, policy::addEndpoint, policy::removeEndpoint,
        FuelCache(Files.createTempDirectory("fuelui").toFile()), FuelClient(policy),
    )

    private fun show(category: String = "fuel", store: com.qtekfun.ultimatemaps.core.fuel.FuelSettingsStore = this.store) {
        val env = SettingsEnv(store, manager, policy, offline = { offline }, setOffline = { offline = it; policy.offlineMode = it }, catalogUrl = { "https://example.org/catalog.json" }, openMaps = {})
        rule.setContent { MapasTheme(darkTheme = false) { SettingsScreen(env, onBack = {}, initialCategory = category) } }
    }

    @Test fun enablingAsksFirstAndExplainsWhatTheServerSees() {
        show()
        rule.onNodeWithTag("fuel_switch").performScrollTo().performClick()
        rule.onNodeWithTag("fuel_confirm_dialog").assertIsDisplayed()
        assertFalse(store.settings.value.enabled, "nothing changes before the user confirms")
        // cancel: still off, nothing listed
        rule.onNodeWithTag("fuel_confirm_no").performClick()
        rule.waitForIdle()
        assertFalse(store.settings.value.enabled)
        assertTrue(rule.onAllNodesWithTagCount("fuel_confirm_dialog") == 0)
        // confirm
        rule.onNodeWithTag("fuel_switch").performScrollTo().performClick()
        rule.onNodeWithTag("fuel_confirm_yes").performClick()
        rule.waitForIdle()
        assertTrue(store.settings.value.enabled)
        rule.onNodeWithTag("fuel_fuels_card").assertExists()
    }

    @Test fun advancedStaysClosedUntilOpened() {
        store.update { it.copy(enabled = true) }
        show()
        assertEquals(0, rule.onAllNodesWithTagCount("fuel_refresh_card"))
        assertEquals(0, rule.onAllNodesWithTagCount("fuel_url_field"))
        rule.onNodeWithTag("fuel_advanced_header").performScrollTo().performClick()
        rule.onNodeWithTag("fuel_url_field").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("fuel_refresh_card").assertExists()
    }

    @Test fun fuelChoicesMapFuelAndUrlValidation() {
        store.update { it.copy(enabled = true) }
        show()
        rule.onNodeWithTag("fuel_check_glp").performScrollTo().performClick()
        rule.onNodeWithTag("fuel_check_gnc").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(setOf("glp", "gnc"), store.settings.value.downloadedFuels)
        assertEquals("glp", store.settings.value.mapFuel)
        rule.onNodeWithTag("fuel_map_gnc").performScrollTo().performClick()
        assertEquals("gnc", store.settings.value.mapFuel)
        rule.onNodeWithTag("fuel_advanced_header").performScrollTo().performClick() // Advanced is closed by default
        rule.onNodeWithTag("fuel_refresh_360").performScrollTo().performClick()
        assertEquals(360, store.settings.value.refreshMinutes)
        // unchecking the map fuel moves the map fuel to another downloaded one
        rule.onNodeWithTag("fuel_check_gnc").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("glp", store.settings.value.mapFuel)
        // URL: http is rejected, https saved, reset restores the default
        rule.onNodeWithTag("fuel_url_field").performScrollTo().performTextReplacement("http://example.org/x/")
        rule.onNodeWithTag("fuel_url_save").performScrollTo().performClick()
        rule.onNodeWithTag("fuel_url_message").assertIsDisplayed()
        assertEquals(FuelSettings.DEFAULT_SOURCE_URL, store.settings.value.sourceUrl)
        rule.onNodeWithTag("fuel_url_field").performTextReplacement("https://example.org/x/")
        rule.onNodeWithTag("fuel_url_save").performClick()
        assertEquals("https://example.org/x/", store.settings.value.sourceUrl)
        rule.onNodeWithTag("fuel_url_reset").performClick()
        assertEquals(FuelSettings.DEFAULT_SOURCE_URL, store.settings.value.sourceUrl)
    }

    @Test fun privacyShowsConnectionsOfflineSwitchAndAttribution() {
        policy.addEndpoint(AllowedEndpoint("sedeaplicaciones.minetur.gob.es", ConnectionPurpose.OTHER, enabled = true))
        store.update { it.copy(enabled = true) }
        show("network")
        rule.onNodeWithTag("privacy_connections_card").performScrollTo().assertIsDisplayed()
        assertEquals(2, rule.onAllNodesWithTagCount("conn_host"))
        rule.onNodeWithTag("offline_switch").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(offline && policy.offlineMode)
        rule.onNodeWithTag("privacy_note").assertIsDisplayed()
    }

    @Test fun fuelCategoryShowsTheAttributionAndTheLastUpdate() {
        store.update { it.copy(enabled = true) }
        show("fuel")
        rule.onNodeWithTag("fuel_attribution").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("fuel_last_update").assertIsDisplayed()
    }

    @Test fun attributionStringMatchesTheCoreConstant() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val id = ctx.resources.getIdentifier("fuel_attribution", "string", ctx.packageName)
        assertEquals(FuelAttribution.SOURCE_EN, ctx.getString(id)) // default (English) resources under test
    }

    @Test fun prefsStoreIsOffByDefaultNormalizesAndPersists() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences(PrefsFuelSettingsStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        val s1 = PrefsFuelSettingsStore(ctx)
        assertFalse(s1.settings.value.enabled)
        assertTrue(s1.settings.value.downloadedFuels.isEmpty())
        assertNull(s1.settings.value.mapFuel)
        s1.update { it.copy(enabled = true, downloadedFuels = setOf("gnc", "bogus"), mapFuel = "glp", refreshMinutes = 1) }
        val s2 = PrefsFuelSettingsStore(ctx)
        assertEquals(FuelSettings(true, setOf("gnc"), "gnc", 30, FuelSettings.DEFAULT_SOURCE_URL), s2.settings.value)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
    onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
