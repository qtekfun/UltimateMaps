package com.qtekfun.ultimatemaps.regions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.qtekfun.ultimatemaps.core.regions.AssetKind
import com.qtekfun.ultimatemaps.core.regions.InstalledRegion
import com.qtekfun.ultimatemaps.core.regions.Region
import com.qtekfun.ultimatemaps.core.regions.RegionAsset
import com.qtekfun.ultimatemaps.core.regions.RegionCatalog
import com.qtekfun.ultimatemaps.core.regions.StorageLocation
import com.qtekfun.ultimatemaps.ui.MapScreen
import com.qtekfun.ultimatemaps.ui.MapScreenState
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RegionsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private var state by mutableStateOf(RegionsUiState(CatalogState.NoServer))
    private val log = mutableListOf<String>()
    private var serverResult = true

    private val actions = RegionsActions(
        onClose = { log += "close" },
        onRefresh = { log += "refresh" },
        onSetOffline = { log += "offline=$it" },
        onSaveServer = { log += "server=$it"; serverResult },
        onSelectLocation = { log += "loc=$it" },
        onDownload = { log += "download=${it.id}" },
        onPause = { log += "pause=$it" },
        onCancel = { log += "cancel=$it" },
        onDelete = { log += "delete=$it" },
        onDismissFailure = { log += "dismiss=$it" },
    )

    private val hash = "c".repeat(64)
    private fun leaf(id: String, version: String = "2") = Region(
        id, id.replaceFirstChar { it.uppercase() }, "spain", version,
        mapOf(
            AssetKind.RENDER to RegionAsset("https://x/$id.pmtiles", 30_000_000, hash, "$id.pmtiles"),
            AssetKind.SEARCH to RegionAsset("https://x/$id.mwm", 10_000_000, hash, "$id.mwm"),
        ),
    )

    private val catalog = RegionCatalog(
        "t",
        listOf(Region("spain", "Spain", null, "2"), leaf("madrid"), leaf("galicia"), Region("andorra", "Andorra", null, "2")),
    )

    private fun installed(id: String, version: String) = InstalledEntry(InstalledRegion(id, version, emptyMap()), "internal", 40_000_000)

    private fun storage(free: Long = 5_000_000_000) =
        StorageInfo(StorageLocation("internal", "Internal storage", File("/x"), false), free, 64_000_000_000)

    private fun show() {
        rule.setContent { MapasTheme(darkTheme = false) { RegionsScreen(state, actions) } }
    }

    private fun openSettingsTab() {
        rule.onNodeWithTag("regions_tab_settings").performClick()
        rule.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        rule.onNodeWithTag("regions_list").performScrollToNode(hasTestTag(tag))
    }

    private fun expandSpain() {
        scrollTo("region_spain")
        rule.onNodeWithTag("region_spain").performClick()
    }

    @Test
    fun noServerShowsTheEmptyStateAndNothingToDownload() {
        show()
        rule.onNodeWithTag("status_no_server").assertIsDisplayed()
        openSettingsTab()
        rule.onNodeWithTag("offline_card").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTagCount("region_spain") == 0)
    }

    @Test
    fun serverFieldValidatesAndRefreshesOnlyWhenValid() {
        serverResult = false
        show()
        openSettingsTab()
        rule.onNodeWithTag("server_save").performClick()
        rule.onNodeWithTag("server_invalid").assertIsDisplayed()
        assertFalse("refresh" in log)
        serverResult = true
        rule.onNodeWithTag("server_save").performClick()
        assertEquals("refresh", log.last())
    }

    @Test
    fun loadingAndErrorStates() {
        state = RegionsUiState(CatalogState.Loading)
        show()
        rule.onNodeWithTag("status_loading").assertIsDisplayed()
        state = RegionsUiState(CatalogState.Failed(CatalogError.NETWORK))
        rule.waitForIdle()
        rule.onNodeWithTag("status_error").assertIsDisplayed()
        rule.onNodeWithTag("status_retry").performClick()
        assertEquals("refresh", log.last())
    }

    @Test
    fun offlineModeExplainsWhyTheCatalogWasNotRequested() {
        state = RegionsUiState(CatalogState.Failed(CatalogError.OFFLINE_MODE), offline = true)
        show()
        rule.onNodeWithText("Offline mode is on, so the catalog was not requested. Turn it off to refresh.").assertIsDisplayed()
        openSettingsTab()
        rule.onNodeWithTag("offline_switch").performClick()
        assertEquals("offline=false", log.last())
    }

    @Test
    fun staleCatalogIsMarked() {
        state = RegionsUiState(CatalogState.Loaded(catalog, stale = true, refreshError = CatalogError.NETWORK))
        show()
        rule.onNodeWithTag("status_stale").assertIsDisplayed()
    }

    @Test
    fun emptyCatalogShowsEmptyState() {
        state = RegionsUiState(CatalogState.Loaded(RegionCatalog("t", emptyList())))
        show()
        rule.onNodeWithTag("status_empty").assertIsDisplayed()
    }

    @Test
    fun hierarchyExpandsAndDownloadsALeaf() {
        state = RegionsUiState(CatalogState.Loaded(catalog), storage = listOf(storage()))
        show()
        expandSpain()
        scrollTo("download_madrid")
        rule.onNode(hasText("40.0 MB") and hasAnyAncestor(hasTestTag("region_madrid"))).assertIsDisplayed()
        rule.onNodeWithTag("download_madrid").performClick()
        assertEquals("download=madrid", log.last())
        scrollTo("region_andorra")
        rule.onNodeWithText("Not available yet").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount("download_andorra"))
    }

    @Test
    fun progressPauseResumeAndCancel() {
        state = RegionsUiState(CatalogState.Loaded(catalog), downloads = mapOf("madrid" to DownloadState.Running(20_000_000, 40_000_000)))
        show()
        expandSpain()
        scrollTo("pause_madrid")
        rule.onNodeWithTag("progress").assertIsDisplayed()
        rule.onNodeWithTag("pause_madrid").performClick()
        rule.onNodeWithTag("cancel_madrid").performClick()
        assertEquals(listOf("pause=madrid", "cancel=madrid"), log.takeLast(2))
        state = state.copy(downloads = mapOf("madrid" to DownloadState.Paused(20_000_000, 40_000_000)))
        rule.waitForIdle()
        scrollTo("resume_madrid")
        rule.onNodeWithText("Paused at 50 %").assertIsDisplayed()
        rule.onNodeWithTag("resume_madrid").performClick()
        assertEquals("download=madrid", log.last())
    }

    @Test
    fun noSpaceAndOtherFailuresAreExplainedWithRetry() {
        state = RegionsUiState(
            CatalogState.Loaded(catalog),
            downloads = mapOf(
                "madrid" to DownloadState.Failed(FailureReason.NO_SPACE, 40_000_000, 5_000_000),
                "galicia" to DownloadState.Failed(FailureReason.OFFLINE_MODE),
            ),
        )
        show()
        expandSpain()
        scrollTo("retry_madrid")
        rule.onNodeWithText("Not enough space: needs 40.0 MB, 5.0 MB free.").assertIsDisplayed()
        scrollTo("retry_galicia")
        rule.onNodeWithText("Offline mode is on: nothing was downloaded.").assertIsDisplayed()
        rule.onNodeWithTag("retry_madrid").performClick()
        assertEquals("download=madrid", log.last())
        rule.onNodeWithTag("dismiss_galicia").performClick()
        assertEquals("dismiss=galicia", log.last())
    }

    @Test
    fun installedRegionOffersUpdateAndConfirmedDelete() {
        state = RegionsUiState(CatalogState.Loaded(catalog), installed = listOf(installed("madrid", "1"), installed("galicia", "2")))
        show()
        expandSpain()
        scrollTo("update_madrid")
        rule.onNodeWithTag("update_madrid").performClick()
        assertEquals("download=madrid", log.last())
        scrollTo("delete_galicia")
        rule.onNodeWithTag("delete_galicia").performClick()
        rule.onNodeWithTag("delete_dialog").assertIsDisplayed()
        assertFalse(log.any { it.startsWith("delete=") }, "needs confirmation first")
        rule.onNodeWithTag("delete_yes").performClick()
        assertEquals("delete=galicia", log.last())
    }

    @Test
    fun installedRegionsMissingFromTheCatalogCanStillBeDeleted() {
        state = RegionsUiState(CatalogState.Failed(CatalogError.NETWORK), installed = listOf(installed("old-region", "1")))
        show()
        scrollTo("orphan_old-region")
        rule.onNodeWithTag("delete_old-region").performClick()
        rule.onNodeWithTag("delete_yes").performClick()
        assertEquals("delete=old-region", log.last())
    }

    @Test
    fun storageChoiceShowsFreeSpace() {
        val card = StorageInfo(StorageLocation("card1", "SD card", File("/sd"), true), 2_000_000_000, 32_000_000_000)
        state = RegionsUiState(CatalogState.NoServer, storage = listOf(storage(), card))
        show()
        openSettingsTab()
        rule.onNodeWithText("5.00 GB free of 64.00 GB").assertIsDisplayed()
        rule.onNodeWithTag("storage_card1").performClick()
        assertEquals("loc=card1", log.last())
    }

    @Test
    fun mapScreenOffersTheMapsEntry() {
        val s = MapScreenState()
        s.onOpenMaps = { log += "open" }
        rule.setContent { MapasTheme(darkTheme = false) { MapScreen(s, onLocate = {}, onResetNorth = {}) {} } }
        rule.onNodeWithTag("open_maps").performClick()
        assertEquals("open", log.last())
    }

    private fun loadedForSearch() {
        state = RegionsUiState(
            CatalogState.Loaded(catalog), storage = listOf(storage()),
            installed = listOf(installed("galicia", "2")),
            downloads = mapOf("madrid" to DownloadState.Running(10_000_000, 40_000_000)),
        )
    }

    @Test
    fun searchFindsCollapsedLeavesWithPathAndKeepsActionsWorking() {
        loadedForSearch()
        show()
        rule.onNodeWithTag("regions_search").performTextInput("MÁDRID")
        rule.waitForIdle()
        scrollTo("region_madrid")
        rule.onNodeWithTag("path_madrid").assertIsDisplayed()
        rule.onNodeWithText("Spain › Madrid").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount("offline_card"))
        rule.onNodeWithTag("pause_madrid").performClick()
        assertEquals("pause=madrid", log.last())
        rule.onNodeWithTag("cancel_madrid").performClick()
        assertEquals("cancel=madrid", log.last())

        rule.onNodeWithTag("regions_search").performTextReplacement("galicia")
        rule.waitForIdle()
        scrollTo("delete_galicia")
        rule.onNodeWithTag("delete_galicia").performClick()
        rule.onNodeWithTag("delete_yes").performClick()
        assertEquals("delete=galicia", log.last())
    }

    @Test
    fun searchEmptyStateAndClearButton() {
        loadedForSearch()
        show()
        assertEquals(0, rule.onAllNodesWithTagCount("regions_search_clear"))
        rule.onNodeWithTag("regions_search").performTextInput("zzzz")
        rule.waitForIdle()
        rule.onNodeWithTag("regions_search_empty").assertIsDisplayed()
        rule.onNodeWithText("No maps match “zzzz”.").assertIsDisplayed()
        rule.onNodeWithTag("regions_search_clear").performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTagCount("regions_search_empty"))
        rule.onNodeWithTag("regions_search").assertTextEquals("")
        rule.onNodeWithTag("regions_installed_title").assertIsDisplayed()
    }

    // Spain has regions whose names do not contain "Spain"/"España"; madrid is installed: "1 of 4 installed".
    private val bigCatalog = RegionCatalog(
        "t",
        listOf(
            Region("spain", "Spain", null, "2"), leaf("andalusia"), leaf("aragon"), leaf("madrid"), leaf("galicia"),
            Region("andorra", "Andorra", null, "2"),
        ),
    )

    private fun loadedBig(downloads: Map<String, DownloadState> = emptyMap()) {
        state = RegionsUiState(
            CatalogState.Loaded(bigCatalog), storage = listOf(storage()),
            installed = listOf(installed("madrid", "2")), downloads = downloads,
        )
    }

    private fun typeSpain(text: String = "España") {
        rule.onNodeWithTag("regions_search").performTextInput(text)
        rule.waitForIdle()
    }

    @Test
    fun searchByCountryShowsItWithTheInstalledCount() {
        loadedBig()
        show()
        typeSpain()
        scrollTo("region_spain")
        rule.onNodeWithTag("region_spain").assertIsDisplayed()
        rule.onNodeWithText("1 of 4 installed", substring = true).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount("region_andalusia"))
    }

    @Test
    fun searchIsAccentAndCaseInsensitive() {
        loadedBig()
        show()
        typeSpain("ESPANA")
        scrollTo("region_spain")
        rule.onNodeWithTag("region_spain").assertIsDisplayed()
    }

    @Test
    fun expandingAMatchingCountryKeepsTheQueryAndShowsAllItsRegions() {
        loadedBig()
        show()
        typeSpain()
        scrollTo("region_spain")
        rule.onNodeWithTag("region_spain").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("regions_search").assertTextEquals("España")
        for (id in listOf("andalusia", "aragon", "madrid", "galicia")) {
            scrollTo("region_$id")
            rule.onNodeWithTag("region_$id").assertIsDisplayed()
        }
        rule.onNodeWithText("1 of 4 installed", substring = true).assertIsDisplayed()
        // A region of the unfolded list can be downloaded and the search and the unfolded state survive the progress.
        scrollTo("download_aragon")
        rule.onNodeWithTag("download_aragon").performClick()
        assertEquals("download=aragon", log.last())
        loadedBig(mapOf("aragon" to DownloadState.Running(10_000_000, 40_000_000)))
        rule.waitForIdle()
        rule.onNodeWithTag("regions_search").assertTextEquals("España")
        scrollTo("pause_aragon")
        rule.onNodeWithTag("pause_aragon").assertIsDisplayed()
        scrollTo("region_galicia")
        rule.onNodeWithTag("region_galicia").assertIsDisplayed()
    }

    @Test
    fun collapsingAndReExpandingKeepsTheQuery() {
        loadedBig()
        show()
        typeSpain()
        scrollTo("region_spain")
        rule.onNodeWithTag("region_spain").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("region_spain").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("regions_search").assertTextEquals("España")
        assertEquals(0, rule.onAllNodesWithTagCount("region_andalusia"))
        rule.onNodeWithTag("region_spain").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("regions_search").assertTextEquals("España")
        scrollTo("region_andalusia")
        rule.onNodeWithTag("region_andalusia").assertIsDisplayed()
    }

    @Test
    fun searchByARegionNameShowsItUnderItsCountryPath() {
        loadedBig()
        show()
        typeSpain("aragon")
        scrollTo("region_aragon")
        rule.onNodeWithTag("region_aragon").assertIsDisplayed()
        rule.onNodeWithText("Spain › Aragon").assertIsDisplayed()
    }

    @Test
    fun clearingTheQueryRestoresTheFullListKeepingTheUnfoldedCountry() {
        loadedBig()
        show()
        typeSpain()
        scrollTo("region_spain")
        rule.onNodeWithTag("region_spain").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("regions_search_clear").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("regions_search").assertTextEquals("")
        scrollTo("region_andorra")
        rule.onNodeWithTag("region_andorra").assertIsDisplayed()
        scrollTo("region_andalusia")
        rule.onNodeWithTag("region_andalusia").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rotationKeepsTheQueryAndTheUnfoldedCountry() = runComposeUiTest {
        loadedBig()
        val restorer = StateRestorationTester(this)
        restorer.setContent { MapasTheme(darkTheme = false) { RegionsScreen(state, actions) } }
        onNodeWithTag("regions_search").performTextInput("España")
        waitForIdle()
        onNodeWithTag("regions_list").performScrollToNode(hasTestTag("region_spain"))
        onNodeWithTag("region_spain").performClick()
        waitForIdle()
        restorer.emulateSaveAndRestore()
        waitForIdle()
        onNodeWithTag("regions_search").assertTextEquals("España")
        onNodeWithTag("regions_list").performScrollToNode(hasTestTag("region_aragon"))
        onNodeWithTag("region_aragon").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun searchTextSurvivesRecreation() = runComposeUiTest {
        loadedForSearch()
        val restorer = StateRestorationTester(this)
        restorer.setContent { MapasTheme(darkTheme = false) { RegionsScreen(state, actions) } }
        onNodeWithTag("regions_search").performTextInput("madrid")
        waitForIdle()
        restorer.emulateSaveAndRestore()
        waitForIdle()
        onNodeWithTag("regions_search").assertTextEquals("madrid")
        onNodeWithTag("regions_list").performScrollToNode(hasTestTag("region_madrid"))
        onNodeWithTag("region_madrid").assertIsDisplayed()
        assertEquals(0, onAllNodes(hasTestTag("offline_card")).fetchSemanticsNodes().size)
    }

    // --- Sections (tabs) ---

    private fun transitRow(id: String) = com.qtekfun.ultimatemaps.transit.TransitCityRow(id, "City $id", "2026-10-07", "2099-01-01", 3_000_000, false, false, false, false, null, listOf("Powered by Test"))

    private fun loadedWithTransit() {
        loadedForSearch()
        state = state.copy(transit = listOf(transitRow("a"), transitRow("b")))
    }

    @Test
    fun opensOnTheMapsTabWithSearchAndNoOtherSection() {
        loadedWithTransit()
        show()
        rule.onNodeWithTag("regions_tab_maps").assertIsSelected()
        rule.onNodeWithTag("regions_tab_transit").assertIsNotSelected()
        rule.onNodeWithTag("regions_search").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount("transit_maps"))
        assertEquals(0, rule.onAllNodesWithTagCount("offline_card"))
    }

    @Test
    fun tabsHaveTheTabRoleAndSelectedState() {
        loadedWithTransit()
        show()
        for (tag in listOf("regions_tab_maps", "regions_tab_transit", "regions_tab_settings")) {
            rule.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        }
        rule.onNodeWithTag("regions_tab_transit").performClick()
        rule.onNodeWithTag("regions_tab_transit").assertIsSelected()
        rule.onNodeWithTag("regions_tab_maps").assertIsNotSelected()
    }

    @Test
    fun switchingTabsShowsOnlyThatSection() {
        loadedWithTransit()
        show()
        rule.onNodeWithTag("regions_tab_transit").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("transit_city_a").assertIsDisplayed()
        rule.onNodeWithTag("transit_city_b").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount("regions_search"))
        assertEquals(0, rule.onAllNodesWithTagCount("region_spain"))
        assertEquals(0, rule.onAllNodesWithTagCount("offline_card"))
        rule.onNodeWithTag("regions_tab_settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("offline_card").assertIsDisplayed()
        rule.onNodeWithTag("server_card").assertIsDisplayed()
        rule.onNodeWithTag("storage_card").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount("transit_city_a"))
        rule.onNodeWithTag("regions_tab_maps").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("regions_search").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount("transit_city_a"))
    }

    @Test
    fun transitTabWithoutCitiesSaysSo() {
        loadedForSearch()
        show()
        rule.onNodeWithTag("regions_tab_transit").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("transit_maps_empty").assertIsDisplayed()
    }

    @Test
    fun searchFiltersOnlyTheMapsTabAndIsKeptWhenComingBack() {
        loadedWithTransit()
        show()
        rule.onNodeWithTag("regions_search").performTextInput("madrid")
        rule.waitForIdle()
        rule.onNodeWithTag("regions_tab_transit").performClick()
        rule.waitForIdle()
        // Both cities stay: the Maps search never filters the transport list.
        rule.onNodeWithTag("transit_city_a").assertIsDisplayed()
        rule.onNodeWithTag("transit_city_b").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount("regions_search"))
        rule.onNodeWithTag("regions_tab_maps").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("regions_search").assertTextEquals("madrid")
        scrollTo("region_madrid")
        rule.onNodeWithTag("region_madrid").assertIsDisplayed()
    }

    @Test
    fun installedMapsComeFirstWithTheirOwnActions() {
        state = RegionsUiState(CatalogState.Loaded(catalog), installed = listOf(installed("madrid", "1"), installed("galicia", "2")))
        show()
        rule.onNodeWithTag("regions_installed_title").assertIsDisplayed()
        rule.onNodeWithTag("installed_region_galicia").assertIsDisplayed()
        rule.onNodeWithTag("installed_update_madrid").performClick()
        assertEquals("download=madrid", log.last())
        rule.onNodeWithTag("installed_delete_galicia").performClick()
        rule.onNodeWithTag("delete_yes").performClick()
        assertEquals("delete=galicia", log.last())
        scrollTo("regions_all_title")
        rule.onNodeWithTag("regions_all_title").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun selectedTabSurvivesRecreation() = runComposeUiTest {
        loadedWithTransit()
        val restorer = StateRestorationTester(this)
        restorer.setContent { MapasTheme(darkTheme = false) { RegionsScreen(state, actions) } }
        onNodeWithTag("regions_tab_transit").performClick()
        waitForIdle()
        restorer.emulateSaveAndRestore()
        waitForIdle()
        onNodeWithTag("regions_tab_transit").assertIsSelected()
        onNodeWithTag("transit_city_a").assertIsDisplayed()
        assertEquals(0, onAllNodes(hasTestTag("regions_search")).fetchSemanticsNodes().size)
    }

    private fun assertTabLabelsFullyShown(fontScale: Float, expected: List<String>) {
        loadedWithTransit()
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                MapasTheme(darkTheme = false) { RegionsScreen(state, actions) }
            }
        }
        listOf("regions_tab_maps", "regions_tab_transit", "regions_tab_settings").forEachIndexed { i, tag ->
            rule.onNodeWithTag(tag).assertIsDisplayed()
            val node = rule.onNodeWithTag(tag + "_label", useUnmergedTree = true)
            node.assertIsDisplayed()
            val results = mutableListOf<TextLayoutResult>()
            node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val layout = results.single()
            assertEquals(expected[i], layout.layoutInput.text.text)
            val box = node.fetchSemanticsNode().boundsInRoot
            assertTrue(box.width >= layout.multiParagraph.maxIntrinsicWidth - 0.5f, "${expected[i]} box ${box.width} is narrower than its text")
            assertFalse(layout.didOverflowHeight, "${expected[i]} is cut vertically")
            assertTrue(layout.lineCount <= 2, "${expected[i]} wraps in ${layout.lineCount} lines")
            // No word is split: every line break falls on a space.
            val text = layout.layoutInput.text.text
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line)
                assertTrue(text.getOrNull(end - 1) == ' ', "${expected[i]} is broken inside a word")
            }
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun englishTabLabelsAreNotCutAt320WithLargeFont() = assertTabLabelsFullyShown(1.3f, listOf("Maps", "Public transport", "Downloads"))

    @Test @Config(qualifiers = "es-w320dp-h640dp-xhdpi")
    fun spanishTabLabelsAreNotCutAt320WithLargeFont() = assertTabLabelsFullyShown(1.3f, listOf("Mapas", "Transporte público", "Descargas"))

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun englishTabLabelsFitAt411() = assertTabLabelsFullyShown(1f, listOf("Maps", "Public transport", "Downloads"))

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
}
