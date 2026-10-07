package com.qtekfun.ultimatemaps.search

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.search.SearchEngine
import com.qtekfun.ultimatemaps.core.search.SearchResult
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
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Category browsing with a fake engine on Unconfined dispatchers: no real time, no other threads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CategoryBrowseTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val origin = LatLon(40.0, -3.0)

    /** Returns the same three places in a scrambled order, whatever the category. */
    private class FakeEngine(var places: List<SearchResult>, var fail: Boolean = false) : SearchEngine {
        val categoryQueries = mutableListOf<String>()
        val textQueries = mutableListOf<String>()
        override fun search(query: String, near: LatLon?, limit: Int): List<SearchResult> {
            textQueries += query
            return emptyList()
        }
        override fun searchCategory(categoryName: String, near: LatLon?, limit: Int): List<SearchResult> {
            categoryQueries += categoryName
            if (fail) error("native failure")
            return places
        }
        override fun close() = Unit
    }

    private val far = SearchResult("Far Pharmacy", LatLon(40.1, -3.0), "Far street", "Pharmacy")
    private val near = SearchResult("Near Pharmacy", LatLon(40.001, -3.0), null, "Pharmacy")
    private val mid = SearchResult("Mid Pharmacy", LatLon(40.01, -3.0), null, "Pharmacy")

    private val pins = mutableListOf<List<LatLon>>()
    private var regions: CoreMaps? = CoreMaps(File("/maps"), 1)

    private fun coordinator(engine: SearchEngine) = SearchCoordinator(
        scope, Dispatchers.Unconfined,
        object : InstalledRegions { override fun coreMaps() = regions },
        backend = { engine },
        near = { origin }, clock = { 0L },
        log = object : SearchLog {
            override fun engineReady(millis: Long, regionCount: Int) = Unit
            override fun searched(queryLength: Int, results: Int, millis: Long, firstSinceReady: Boolean) = Unit
            override fun failed(kind: String) = Unit
        },
        debounceMs = 0,
        onCategoryResults = { pins += it },
    )

    @After fun tearDown() = scope.cancel()

    @Test fun browsingListsTheNearestFirstWithDistancesAndPins() {
        val engine = FakeEngine(listOf(far, near, mid))
        val c = coordinator(engine)
        c.browseCategory(PlaceCategory.PHARMACY, "pharmacy")

        assertEquals(listOf("pharmacy"), engine.categoryQueries)
        assertTrue(engine.textQueries.isEmpty())
        assertEquals(PlaceCategory.PHARMACY, c.state.category)
        assertEquals(SearchStatus.DONE, c.state.status)
        assertEquals(listOf("Near Pharmacy", "Mid Pharmacy", "Far Pharmacy"), c.state.results.map { it.name })
        val d = c.state.results.map { it.distanceMeters!! }
        assertTrue(d[0] < d[1] && d[1] < d[2], "distances ascend: $d")
        assertTrue(d[0] in 100.0..120.0, "0.001 degrees of latitude is about 111 m, got ${d[0]}")
        // The map pins follow the sorted list; the first call is the empty reset before the search.
        assertEquals(emptyList(), pins.first())
        assertEquals(c.state.results.map { it.point }, pins.last())
    }

    @Test fun typingLeavesTheCategoryAndRemovesThePins() {
        val c = coordinator(FakeEngine(listOf(near)))
        c.browseCategory(PlaceCategory.CAFE, "cafe")
        c.onQueryChange("calle")
        assertNull(c.state.category)
        assertEquals(emptyList(), pins.last())
    }

    @Test fun clearingEmptiesTheListAndThePins() {
        val c = coordinator(FakeEngine(listOf(near)))
        c.browseCategory(PlaceCategory.FUEL, "fuel")
        c.clearCategory()
        assertNull(c.state.category)
        assertTrue(c.state.results.isEmpty())
        assertEquals(SearchStatus.IDLE, c.state.status)
        assertEquals(emptyList(), pins.last())
    }

    @Test fun noRegionsAndEngineFailureAreReported() {
        regions = null
        val c = coordinator(FakeEngine(emptyList()))
        c.browseCategory(PlaceCategory.ATM, "ATM")
        assertEquals(SearchStatus.NO_REGIONS, c.state.status)

        regions = CoreMaps(File("/maps"), 1)
        val failing = coordinator(FakeEngine(listOf(near), fail = true))
        failing.browseCategory(PlaceCategory.ATM, "ATM")
        assertEquals(SearchStatus.ERROR, failing.state.status)
        assertTrue(failing.state.results.isEmpty())
    }

    @Test fun withoutAnOriginTheCoreOrderIsKeptAndNoDistanceIsShown() {
        val sorted = sortedByDistance(listOf(far, near), null)
        assertEquals(listOf("Far Pharmacy", "Near Pharmacy"), sorted.map { it.name })
        assertNull(sorted[0].distanceMeters)
    }

    @Test fun everyCategoryHasADistinctQueryAndLabelResource() {
        val entries = PlaceCategory.entries
        assertEquals(10, entries.size)
        assertEquals(10, entries.map { it.query }.toSet().size)
        assertEquals(10, entries.map { it.id }.toSet().size)
    }

    @Test fun chipsListNearbyPlacesWithDistanceAndTappingTheSelectedOneClears() {
        val engine = FakeEngine(listOf(far, near))
        val c = coordinator(engine)
        var opened = 0
        rule.setContent {
            MapasTheme(darkTheme = false) {
                androidx.compose.foundation.layout.Column {
                    CategoryRow(c, onOpened = { opened++ })
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(c.state.results.size) { i ->
                            androidx.compose.foundation.text.BasicText(
                                c.state.results[i].name + "|" + (c.state.results[i].distanceMeters?.let { "d" } ?: "-"),
                                modifier = Modifier.testTag("row_$i"),
                            )
                        }
                    }
                }
            }
        }
        for (cat in PlaceCategory.entries) rule.onNodeWithTag("category_${cat.id}").assertExists()
        rule.onNodeWithText("Pharmacy").assertIsDisplayed()

        rule.onNodeWithTag("category_pharmacy").performClick()
        rule.waitForIdle()
        assertEquals(listOf("pharmacy"), engine.categoryQueries) // the English query: Robolectric runs in English
        assertEquals(1, opened)
        rule.onNodeWithText("Near Pharmacy|d").assertIsDisplayed()

        rule.onNodeWithTag("category_pharmacy").performClick() // the selected chip clears
        rule.waitForIdle()
        assertNull(c.state.category)
        rule.onAllNodesWithTag("row_0").assertCountEquals(0)
        assertEquals(1, opened)
    }
}
