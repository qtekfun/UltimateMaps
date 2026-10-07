package com.qtekfun.mapas.regions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.qtekfun.mapas.core.regions.Region
import com.qtekfun.mapas.core.regions.RegionCatalog
import com.qtekfun.mapas.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** World and WorldCoasts are base files, and region names follow the language of place information. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RegionNamesTest {
    @get:Rule
    val rule = createComposeRule()

    private val catalog = RegionCatalog(
        "t",
        listOf(
            Region("world", "World", null, "2", comapsId = "World"),
            Region("worldcoasts", "WorldCoasts", null, "2", comapsId = "WorldCoasts"),
            Region("spain", "Spain", null, "2", names = mapOf("es" to "España")),
            Region("spain_community-of-madrid", "Community of Madrid", "spain", "2", names = mapOf("es" to "Comunidad de Madrid")),
            Region("abkhazia", "Abkhazia", null, "2", names = mapOf("es" to "Abjasia")),
        ),
    )

    private val actions = RegionsActions(
        onClose = {}, onRefresh = {}, onSetOffline = {}, onSaveServer = { true }, onSelectLocation = {},
        onDownload = {}, onPause = {}, onCancel = {}, onDelete = {}, onDismissFailure = {},
    )

    private fun show(language: String) = rule.setContent {
        MapasTheme(darkTheme = false) { RegionsScreen(RegionsUiState(CatalogState.Loaded(catalog)), actions, nameLanguage = language) }
    }

    @Test fun theModelHidesTheBaseFilesAtTheTopLevel() {
        val rows = RegionsModel.rows(catalog, emptySet(), emptyMap(), emptyMap())
        assertEquals(listOf("spain", "abkhazia"), rows.map { it.region.id })
    }

    @Test fun theSearchIndexHidesThemToo() {
        val index = RegionSearch.Index(catalog, "es")
        assertTrue(index.entries.none { it.region.isBaseFile })
        assertTrue(RegionSearch.filter(index, "world").isEmpty())
    }

    @Test fun theListDoesNotShowWorldAndShowsSpanishNamesOnASpanishPhone() {
        show("es")
        rule.onNodeWithText("España").assertIsDisplayed()
        rule.onNodeWithText("Abjasia").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("region_world").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("region_worldcoasts").fetchSemanticsNodes().size)
    }

    @Test fun englishKeepsTheCatalogNames() {
        show("en")
        rule.onNodeWithTag("region_abkhazia").assertIsDisplayed()
        rule.onNodeWithText("Abkhazia").assertIsDisplayed()
        rule.onNodeWithText("Spain").assertIsDisplayed()
    }

    @Test fun searchMatchesTheNamesOfEveryLanguageAndShowsTheChosenOne() {
        val spanish = RegionSearch.Index(catalog, "es")
        assertEquals(listOf("spain_community-of-madrid"), RegionSearch.filter(spanish, "comunidad").map { it.region.id })
        assertEquals(listOf("spain_community-of-madrid"), RegionSearch.filter(spanish, "community").map { it.region.id })
        assertEquals("España › Comunidad de Madrid", RegionSearch.filter(spanish, "madrid").single().path)
        assertEquals("Spain › Community of Madrid", RegionSearch.filter(RegionSearch.Index(catalog, "en"), "madrid").single().path)
    }
}
