package com.qtekfun.mapas.settings.backup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollTo
import com.qtekfun.mapas.core.fuel.FuelCache
import com.qtekfun.mapas.core.fuel.FuelClient
import com.qtekfun.mapas.core.fuel.FuelDataManager
import com.qtekfun.mapas.core.fuel.InMemoryFuelSettingsStore
import com.qtekfun.mapas.core.net.AllowedEndpoint
import com.qtekfun.mapas.core.net.ConnectionPurpose
import com.qtekfun.mapas.core.net.DefaultNetworkPolicy
import com.qtekfun.mapas.core.regions.AssetKind
import com.qtekfun.mapas.core.regions.InstalledRegion
import com.qtekfun.mapas.core.regions.Region
import com.qtekfun.mapas.core.regions.RegionAsset
import com.qtekfun.mapas.core.regions.RegionCatalog
import com.qtekfun.mapas.regions.CatalogState
import com.qtekfun.mapas.regions.InstalledEntry
import com.qtekfun.mapas.regions.RegionsActions
import com.qtekfun.mapas.regions.RegionsScreen
import com.qtekfun.mapas.regions.RegionsUiState
import com.qtekfun.mapas.settings.BackupSettingsEnv
import com.qtekfun.mapas.settings.BackupUiState
import com.qtekfun.mapas.settings.SettingsEnv
import com.qtekfun.mapas.settings.SettingsScreen
import com.qtekfun.mapas.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BackupInSettingsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val policy = DefaultNetworkPolicy(listOf(AllowedEndpoint("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES)))
    private val store = InMemoryFuelSettingsStore()
    private val manager = FuelDataManager(
        store, policy, policy::addEndpoint, policy::removeEndpoint,
        FuelCache(Files.createTempDirectory("backupui").toFile()), FuelClient(policy),
    )
    private val state = BackupUiState()
    private val log = ArrayList<String>()

    private fun env(withBackup: Boolean) = SettingsEnv(
        store, manager, policy, offline = { false }, setOffline = {}, catalogUrl = { "https://example.org/c.json" }, openMaps = {},
        backup = if (withBackup) {
            BackupSettingsEnv(state, { log += "export" }, { log += "all" }, { log += "import" }, {}, {})
        } else null,
    )

    @Test fun theSectionIsInTheSettingsScreenAndWorks() {
        rule.setContent { MapasTheme(darkTheme = false) { SettingsScreen(env(true), onBack = {}, initialCategory = "data") } }
        rule.onNodeWithTag("backup_export").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("backup_import").performScrollTo().performClick()
        assertEquals(listOf("import"), log)
    }

    @Test fun withoutTheEnvironmentTheSectionIsHidden() {
        rule.setContent { MapasTheme(darkTheme = false) { SettingsScreen(env(false), onBack = {}, initialCategory = "data") } }
        assertEquals(0, rule.onAllNodesWithTag("backup_card").fetchSemanticsNodes().size)
    }

    @Test fun aRestoreRebuildsTheOtherSectionsSoTheyShowTheNewValues() {
        // The history switch keeps its state in the composition: a revision bump must make it re-read the setting.
        var enabled = true
        val history = com.qtekfun.mapas.settings.HistorySettingsEnv(
            object : com.qtekfun.mapas.search.HistorySettings { override var enabled: Boolean
                get() = enabled
                set(v) { enabled = v } },
            clear = {},
        )
        val e = SettingsEnv(
            store, manager, policy, offline = { false }, setOffline = {}, catalogUrl = { "" }, openMaps = {},
            history = history, backup = BackupSettingsEnv(state, {}, {}, {}, {}, {}),
        )
        rule.setContent { MapasTheme(darkTheme = false) { SettingsScreen(e, onBack = {}, initialCategory = "data") } }
        rule.onNodeWithTag("history_switch").performScrollTo().assertIsDisplayed()
        enabled = false // what a restore did behind the screen's back
        state.revision++
        rule.waitForIdle()
        // The rebuilt section reads false now: tapping turns it ON again (it would turn it off if it still showed on).
        rule.onNodeWithTag("history_switch").performScrollTo().performClick()
        assertTrue(enabled)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RestoredRegionsOfferTest {
    @get:Rule
    val rule = createComposeRule()

    private var state by mutableStateOf(RegionsUiState(CatalogState.NoServer))
    private val log = ArrayList<String>()
    private var dismissed = 0
    private val actions = RegionsActions(onDownload = { log += it.id }, onDismissRestored = { dismissed++ })

    private val hash = "c".repeat(64)
    private fun leaf(id: String) = Region(
        id, id, "spain", "2",
        mapOf(
            AssetKind.RENDER to RegionAsset("https://x/$id.pmtiles", 30_000_000, hash, "$id.pmtiles"),
            AssetKind.SEARCH to RegionAsset("https://x/$id.mwm", 10_000_000, hash, "$id.mwm"),
        ),
    )
    private val catalog = RegionCatalog("t", listOf(Region("spain", "Spain", null, "2"), leaf("madrid"), leaf("galicia")))
    private fun loaded() = CatalogState.Loaded(catalog)

    private fun show() = rule.setContent { MapasTheme(darkTheme = false) { RegionsScreen(state, actions) } }

    @Test fun offersOnlyTheMissingMapsAndDownloadsNothingUntilAsked() {
        state = RegionsUiState(
            loaded(), restoredRegionIds = setOf("madrid", "galicia", "gone"),
            installed = listOf(InstalledEntry(InstalledRegion("galicia", "2", emptyMap()), "internal", 1)),
        )
        show()
        rule.onNodeWithTag("restored_regions_card").assertIsDisplayed()
        rule.onNodeWithTag("restored_regions_unavailable").assertIsDisplayed() // "gone" is not in the catalog
        assertTrue(log.isEmpty(), "showing the offer must not download")
        rule.onNodeWithTag("restored_regions_download").performClick()
        assertEquals(listOf("madrid"), log)
    }

    @Test fun offlineModeHidesTheDownloadButton() {
        state = RegionsUiState(loaded(), restoredRegionIds = setOf("madrid"), offline = true)
        show()
        rule.onNodeWithTag("restored_regions_offline").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("restored_regions_download").fetchSemanticsNodes().size)
        assertTrue(log.isEmpty())
    }

    @Test fun dismissCallsBackAndNothingIsShownWhenAllAreInstalled() {
        state = RegionsUiState(loaded(), restoredRegionIds = setOf("madrid"))
        show()
        rule.onNodeWithTag("restored_regions_dismiss").performClick()
        assertEquals(1, dismissed)
        state = RegionsUiState(
            loaded(), restoredRegionIds = setOf("madrid"),
            installed = listOf(InstalledEntry(InstalledRegion("madrid", "2", emptyMap()), "internal", 1)),
        )
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("restored_regions_card").fetchSemanticsNodes().size)
    }

    @Test fun noOfferWithoutARestore() {
        state = RegionsUiState(loaded())
        show()
        assertEquals(0, rule.onAllNodesWithTag("restored_regions_card").fetchSemanticsNodes().size)
    }
}
