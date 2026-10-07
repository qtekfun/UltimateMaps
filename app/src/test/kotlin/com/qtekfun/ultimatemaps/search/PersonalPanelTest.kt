package com.qtekfun.ultimatemaps.search

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.qtekfun.ultimatemaps.core.data.SpecialSlot
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.places.ListStyle
import com.qtekfun.ultimatemaps.places.PanelMode
import com.qtekfun.ultimatemaps.places.PersonalFixture
import com.qtekfun.ultimatemaps.places.PlaceInfo
import com.qtekfun.ultimatemaps.places.PlacesController
import com.qtekfun.ultimatemaps.places.QuickPlacesController
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sheet with the personal features: quick places, recent searches, set Home/Work, list editor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PersonalPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val fx = PersonalFixture()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val routed = mutableListOf<PlaceInfo>()
    private var sos = 0
    private var here: LatLon? = LatLon(40.4, -3.7)
    private val markers = mutableListOf<LatLon?>()
    private val settings = MemoryHistorySettings()

    private val search = SearchCoordinator(
        scope, Dispatchers.IO, object : InstalledRegions { override fun coreMaps(): CoreMaps? = null }, { error("not used") },
        near = { null }, clock = { 0L }, log = LogcatSearchLog,
    )

    private val places = PlacesController(fx.scope, fx.io, fx.lazyService, near = { here }, onMarkers = {})
    private val quick = QuickPlacesController(
        fx.scope, fx.io, fx.lazyService, location = { here }, requestLocation = {}, onParking = { markers += it },
        parkingName = { "Parked car" },
    )
    private val history = SearchHistory(fx.scope, fx.io, fx.lazyService, settings)

    private val actions = PanelActions(
        onPickResult = {}, onShowSaved = {}, onRoute = { routed += it }, onShare = {}, onImport = {}, onExport = {}, onFocusField = {},
    )

    private fun show() = rule.setContent {
        MapasTheme(darkTheme = false) {
            SheetPanel(search, places, actions, quick = quick, history = history, onEmergency = { sos++ })
        }
    }

    @After fun tearDown() {
        scope.cancel()
        fx.close()
    }

    private val casa = PlaceInfo("Calle Mayor 1", LatLon(40.4154, -3.7074))

    // --- Quick places ---

    @Test fun quickChipsAreShownUnderTheSearchField() {
        show()
        rule.onNodeWithTag("quick_home").assertIsDisplayed()
        rule.onNodeWithTag("quick_work").assertIsDisplayed()
        rule.onNodeWithTag("quick_parking").assertIsDisplayed()
        rule.onNodeWithTag("quick_sos").assertIsDisplayed()
        rule.onNodeWithText("Park").assertIsDisplayed()
    }

    @Test fun anEmptyHomeChipExplainsHowToSetIt() {
        show()
        rule.onNodeWithTag("quick_home").performClick()
        rule.onNodeWithTag("quick_message").assertIsDisplayed()
        rule.onNodeWithText("Open a place and tap “Set as Home”.").assertIsDisplayed()
        assertTrue(routed.isEmpty())
    }

    @Test fun homeIsSetFromThePlaceCardAndOneTapRoutesThere() {
        places.showCard(casa)
        show()
        rule.onNodeWithTag("place_set_home").performClick()
        assertEquals(casa, quick.destination(SpecialSlot.HOME))
        rule.onNodeWithText("Home set.").assertIsDisplayed()

        places.closeCard()
        rule.onNodeWithTag("quick_home").performClick()
        assertEquals(listOf(casa), routed)
    }

    @Test fun workIsSetFromThePlaceCardToo() {
        places.showCard(casa)
        show()
        rule.onNodeWithTag("place_set_work").performClick()
        assertEquals(casa, quick.destination(SpecialSlot.WORK))
        places.closeCard()
        rule.onNodeWithTag("quick_work").performClick()
        assertEquals(listOf(casa), routed)
    }

    @Test fun parkHereMarksThePositionThenTheChipRoutesBackAndCanBeCleared() {
        show()
        rule.onNodeWithTag("quick_parking").performClick()
        assertEquals(here, quick.state.parking?.point)
        assertEquals(listOf<LatLon?>(here), markers)
        rule.onNodeWithText("Parked car").assertIsDisplayed()
        rule.onNodeWithTag("quick_park_clear").assertIsDisplayed()

        rule.onNodeWithTag("quick_parking").performClick()
        assertEquals(listOf(PlaceInfo("Parked car", here!!)), routed)

        rule.onNodeWithTag("quick_park_clear").performClick()
        assertNull(quick.state.parking)
        assertNull(markers.last())
        rule.onAllNodesWithTag("quick_park_clear").assertCountEquals(0)
        rule.onNodeWithText("Park").assertIsDisplayed()
    }

    @Test fun theSosChipOpensTheEmergencyScreen() {
        show()
        rule.onNodeWithTag("quick_sos").performClick()
        assertEquals(1, sos)
    }

    @Test fun quickPlacesAreNotShownWhileTyping() {
        search.state.query = "cafe"
        show()
        rule.onAllNodesWithTag("quick_places").assertCountEquals(0)
    }

    // --- Recent searches ---

    @Test fun recentSearchesListAndTappingOneFillsTheField() {
        history.record("farmacia"); history.record("gasolinera")
        show()
        rule.onAllNodesWithTag("recent_row").assertCountEquals(2)
        rule.onNodeWithText("gasolinera").assertIsDisplayed()
        rule.onAllNodesWithTag("recent_row")[1].performClick()
        assertEquals("farmacia", search.state.query)
    }

    @Test fun theClearButtonRemovesTheRecents() {
        history.record("farmacia")
        show()
        rule.onNodeWithTag("recent_clear").performClick()
        rule.onAllNodesWithTag("recent_row").assertCountEquals(0)
        rule.onAllNodesWithTag("recent_header").assertCountEquals(0)
        assertTrue(fx.service.recentSearches().isEmpty())
    }

    @Test fun withTheHistoryOffNothingIsListed() {
        history.record("farmacia")
        settings.enabled = false
        history.refresh()
        show()
        rule.onAllNodesWithTag("recent_row").assertCountEquals(0)
        rule.onNodeWithTag("quick_places").assertIsDisplayed() // the rest of the panel is unaffected
    }

    // --- List editor ---

    @Test fun aListCanBeCustomisedWithEmojiColorAndNotes() {
        val id = fx.service.createList("Viaje")!!
        places.showMode(PanelMode.LISTS)
        places.openList(places.state.lists.single { it.id == id })
        show()
        rule.onNodeWithTag("list_customize").performClick()
        rule.onNodeWithTag("list_editor").assertIsDisplayed()
        rule.onNodeWithTag("edit_emoji").performTextInput("🏖️")
        rule.onNodeWithTag("swatch_3").performClick()
        rule.onNodeWithTag("edit_notes").performTextInput("Verano")
        rule.onNodeWithTag("edit_save").performClick()

        val saved = fx.service.lists().single { it.id == id }
        assertEquals("🏖️", saved.icon)
        assertEquals(ListStyle.PALETTE[3], saved.color)
        assertEquals("Verano", saved.notes)
        rule.onNodeWithText("🏖️ Viaje").assertIsDisplayed()
        rule.onNodeWithTag("list_notes").assertIsDisplayed()
    }

    @Test fun cancellingTheEditorChangesNothing() {
        val id = fx.service.createList("Viaje")!!
        places.showMode(PanelMode.LISTS)
        places.openList(places.state.lists.single { it.id == id })
        show()
        rule.onNodeWithTag("list_customize").performClick()
        rule.onNodeWithTag("edit_emoji").performTextInput("🏖️")
        rule.onNodeWithTag("edit_cancel").performClick()
        assertNull(fx.service.lists().single { it.id == id }.icon)
        rule.onAllNodesWithTag("list_editor").assertCountEquals(0)
        assertNotNull(rule.onNode(hasTestTag("list_customize")))
    }

    @Test fun theOverviewShowsEmojiAndNotesOfCustomisedLists() {
        val id = fx.service.createList("Viaje")!!
        fx.service.customiseList(id, "Viaje", "🏖️", ListStyle.PALETTE[0], "Verano")
        places.showMode(PanelMode.LISTS)
        show()
        rule.onNodeWithText("🏖️ Viaje").assertIsDisplayed()
        rule.onNodeWithText("Verano").assertIsDisplayed()
    }
}
