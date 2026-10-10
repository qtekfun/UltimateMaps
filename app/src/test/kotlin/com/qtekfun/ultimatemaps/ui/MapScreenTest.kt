package com.qtekfun.ultimatemaps.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.CameraState
import com.qtekfun.ultimatemaps.map.PrefsCameraStateStore
import com.qtekfun.ultimatemaps.ui.sheet.SheetDetent
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MapScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private var dark by mutableStateOf(false)

    private fun show(state: MapScreenState, onLocate: () -> Unit = {}) {
        rule.setContent {
            MapasTheme(darkTheme = dark) { MapScreen(state, onLocate, onResetNorth = {}) {} }
        }
    }

    private fun detentText() =
        rule.onNodeWithTag("sheet").fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    @Test
    fun osmAttributionIsAlwaysVisibleInEverySheetDetentAndTheme() {
        val state = MapScreenState()
        show(state)
        for (isDark in listOf(false, true)) for (d in SheetDetent.entries) {
            dark = isDark
            state.detent = d
            rule.waitForIdle()
            rule.onNodeWithTag("attribution").assertIsDisplayed()
        }
    }

    @Test
    fun attributionOpensLicenceNotice() {
        val state = MapScreenState()
        show(state)
        rule.onNodeWithTag("attribution").performClick()
        rule.waitForIdle()
        assertEquals(true, state.aboutVisible)
        rule.onNodeWithTag("about_dialog").assertIsDisplayed()
    }

    @Test
    fun handleTapCyclesThroughThreeDetents() {
        val state = MapScreenState().apply { detent = SheetDetent.COLLAPSED }
        show(state)
        assertEquals("Collapsed", detentText())
        rule.onNodeWithTag("sheet_handle").performClick()
        rule.waitForIdle()
        assertEquals(SheetDetent.MEDIUM, state.detent)
        assertEquals("Half", detentText())
        rule.onNodeWithTag("sheet_handle").performClick()
        rule.waitForIdle()
        assertEquals(SheetDetent.FULL, state.detent)
        assertEquals("Full", detentText())
    }

    @Test
    fun locateButtonCallsBackAndCompassOnlyWhenRotated() {
        var clicks = 0
        val state = MapScreenState()
        show(state, onLocate = { clicks++ })
        rule.onNodeWithTag("btn_locate").performClick()
        assertEquals(1, clicks)
        rule.onAllNodesWithTagCount("btn_compass", 0)
        state.bearing = 40f
        rule.waitForIdle()
        rule.onNodeWithTag("btn_compass").assertIsDisplayed()
    }

    @Test
    fun theMainScreenHasNoMapsButtonAndTheNoMapsCardOpensSettings() {
        var opened = 0
        val state = MapScreenState().apply { hasTiles = false; onOpenSettings = { opened++ } }
        show(state)
        rule.onAllNodesWithTagCount("open_maps", 0) // downloads live in Settings, Maps and network
        rule.onNodeWithTag("card_no_maps").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun noMapsHintShownOnlyWithoutTiles() {
        val state = MapScreenState().apply { hasTiles = false }
        show(state)
        rule.onNodeWithTag("card_no_maps").assertIsDisplayed()
        state.hasTiles = true
        rule.waitForIdle()
        rule.onAllNodesWithTagCount("card_no_maps", 0)
    }

    @Test
    fun cameraStateRoundTripsThroughPreferences() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = PrefsCameraStateStore(ctx)
        assertNull(store.load())
        val s = CameraState(LatLon(40.416775, -3.703790), 12.25, 33.5, 20.0)
        store.save(s)
        assertEquals(s, PrefsCameraStateStore(ctx).load())
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String, expected: Int) {
        assertEquals(expected, onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size)
    }

    @Test
    fun theSheetStaysHiddenWhileNavigatingUnlessAStationCardAsksForIt() {
        val state = MapScreenState()
        var navSheet by mutableStateOf(false)
        rule.setContent {
            MapasTheme(darkTheme = false) {
                MapScreen(
                    state, onLocate = {}, onResetNorth = {}, sheetPanel = { androidx.compose.foundation.text.BasicText("card") },
                    navigating = true, navSheet = navSheet,
                ) {}
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("sheet").assertDoesNotExist()
        navSheet = true
        rule.waitForIdle()
        rule.onNodeWithTag("sheet").assertIsDisplayed()
    }

    @Test
    fun aTappedHazardCardOpensAboveTheNavigationScreenAndItsCloseButtonWorks() {
        val state = MapScreenState()
        val card = com.qtekfun.ultimatemaps.cameras.HazardCardState()
        val controller = com.qtekfun.ultimatemaps.cameras.HazardCardController(
            describer = { id -> com.qtekfun.ultimatemaps.cameras.HazardInfo("Slow traffic $id", listOf("A-1"), null, "DGT") },
            card = card,
        )
        rule.setContent {
            MapasTheme(darkTheme = false) {
                MapScreen(
                    state, onLocate = {}, onResetNorth = {},
                    sheetPanel = { com.qtekfun.ultimatemaps.cameras.HazardCard(card) },
                    navigating = true, navSheet = card.info != null,
                ) {}
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("sheet").assertDoesNotExist()
        controller.onTap("incident-1")
        rule.waitForIdle()
        rule.onNodeWithTag("sheet").assertIsDisplayed()
        rule.onNodeWithTag("hazard_card").assertIsDisplayed()
        rule.onNodeWithTag("hazard_close").performClick()
        rule.waitForIdle()
        assertNull(card.info)
        rule.onNodeWithTag("sheet").assertDoesNotExist()
    }
}
