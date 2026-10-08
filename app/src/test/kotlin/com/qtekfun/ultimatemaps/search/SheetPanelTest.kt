package com.qtekfun.ultimatemaps.search

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.qtekfun.ultimatemaps.core.data.SqlitePlacesRepository
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.search.SearchEngine
import com.qtekfun.ultimatemaps.core.search.SearchResult
import com.qtekfun.ultimatemaps.places.DefaultList
import com.qtekfun.ultimatemaps.places.LongSetting
import com.qtekfun.ultimatemaps.places.PanelMode
import com.qtekfun.ultimatemaps.places.PlaceInfo
import com.qtekfun.ultimatemaps.places.PlacesController
import com.qtekfun.ultimatemaps.places.PlacesService
import com.qtekfun.ultimatemaps.places.toPlaceInfo
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SheetPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val picked = mutableListOf<SearchResult>()
    private val shared = mutableListOf<PlaceInfo>()
    private val routed = mutableListOf<PlaceInfo>()

    private val sol = SearchResult("Puerta del Sol", LatLon(40.41689, -3.70351), "Madrid", "Square")

    private val search = SearchCoordinator(
        scope, Dispatchers.IO, object : InstalledRegions { override fun coreMaps(): CoreMaps? = null }, { error("not used") }, near = { null }, clock = { 0L },
        log = LogcatSearchLog,
    )

    private val places = PlacesController(
        scope, Dispatchers.IO,
        lazy {
            val repo = SqlitePlacesRepository(AndroidSQLiteDriver(), ":memory:")
            val setting = object : LongSetting {
                var v: Long? = null
                override fun get() = v
                override fun set(value: Long) { v = value }
            }
            PlacesService(repo, DefaultList(repo, setting) { "Favorites" })
        },
        near = { LatLon(40.4168, -3.7038) }, onMarkers = {},
    )

    private val actions = PanelActions(
        onPickResult = { picked += it },
        onShowSaved = {},
        onRoute = { routed += it },
        onShare = { shared += it },
        onImport = {}, onExport = {}, onFocusField = {},
    )

    private fun show() = rule.setContent { MapasTheme(darkTheme = false) { SheetPanel(search, places, actions) } }

    @After fun tearDown() = scope.cancel()

    @Test
    fun withoutInstalledRegionsTheEmptyStateIsShown() {
        search.state.regionsAvailable = false
        show()
        rule.onNodeWithTag("search_no_regions").assertIsDisplayed()
        rule.onNodeWithText("No regions installed").assertIsDisplayed()
    }

    @Test
    fun tappingAResultReportsIt() {
        search.state.regionsAvailable = true
        search.state.results = listOf(sol)
        search.state.status = SearchStatus.DONE
        show()
        rule.onNodeWithText("Puerta del Sol").assertIsDisplayed()
        rule.onNodeWithText("Square · Madrid").assertIsDisplayed()
        rule.onNodeWithTag("search_result").performClick()
        assertEquals(listOf(sol), picked)
    }

    @Test
    fun categoryResultsShowTheDistanceAndTheNoteAndTheChipsAreThere() {
        search.state.regionsAvailable = true
        search.state.category = PlaceCategory.PHARMACY
        search.state.results = listOf(sol.copy(distanceMeters = 350.0))
        search.state.status = SearchStatus.DONE
        show()
        rule.onNodeWithTag("category_row").assertIsDisplayed()
        rule.onNodeWithTag("category_pharmacy").assertIsDisplayed()
        rule.onNodeWithText("350 m").assertIsDisplayed()
        rule.onNodeWithTag("category_note").assertIsDisplayed()
        rule.onNodeWithTag("search_result").performClick()
        assertEquals(listOf(sol.copy(distanceMeters = 350.0)), picked)
    }

    @Test
    fun anEmptyCategoryResultSaysNothingWasFound() {
        search.state.regionsAvailable = true
        search.state.category = PlaceCategory.ATM
        search.state.results = emptyList()
        search.state.status = SearchStatus.DONE
        show()
        rule.onNodeWithText("Nothing found nearby in the downloaded maps.").assertIsDisplayed()
    }

    @Test
    fun placeCardShowsDetailsAndSavesToFavorites() {
        places.showCard(sol.toPlaceInfo())
        show()
        rule.onNodeWithTag("place_name").assertIsDisplayed()
        rule.onNodeWithText("Puerta del Sol").assertIsDisplayed()
        rule.onNodeWithTag("place_subtitle").assertIsDisplayed()
        rule.onNodeWithText("40.41689, -3.70351").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed()

        rule.onNodeWithTag("place_save").performClick()
        rule.waitUntil(30_000) { places.state.cardSavedId != null }
        rule.onNodeWithText("Saved").assertIsDisplayed()
        rule.onNodeWithText("Saved in Favorites").assertIsDisplayed()

        rule.onNodeWithTag("place_share").performClick()
        assertEquals(listOf(sol.toPlaceInfo()), shared)
        rule.onNodeWithTag("place_route").performClick()
        assertEquals(listOf(sol.toPlaceInfo()), routed)
    }

    @Test
    fun savedPlaceShowsUpInTheListsTabSortedAndSearchable() {
        places.showCard(sol.toPlaceInfo())
        places.toggleSaved()
        rule.waitUntil(30_000) { places.state.cardSavedId != null }
        places.closeCard()
        show()

        rule.onNodeWithTag("tab_lists").performClick()
        rule.waitUntil(30_000) { places.state.mode == PanelMode.LISTS && places.state.lists.isNotEmpty() }
        rule.onNodeWithText("Favorites").assertIsDisplayed()
        rule.onNodeWithTag("list_row").performClick()
        rule.waitUntil(30_000) { places.state.openList != null && places.state.rows.isNotEmpty() }
        assertNotNull(places.state.rows.single().distanceMeters)
        rule.onAllNodesWithTag("place_row").assertCountEquals(1)
        rule.onNodeWithTag("sort_name").performClick()
        rule.onNodeWithTag("list_import").assertIsDisplayed()
        rule.onNodeWithTag("list_export_gpx").assertIsDisplayed()
        rule.onNodeWithTag("list_export_kml").assertIsDisplayed()

        places.setQuery("zzz")
        rule.waitUntil(30_000) { places.state.rows.isEmpty() }
        rule.onNodeWithTag("list_empty").assertIsDisplayed()
        rule.onNode(hasTestTag("place_row")).assertDoesNotExist()
    }
}
