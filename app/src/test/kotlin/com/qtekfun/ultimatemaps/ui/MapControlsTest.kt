package com.qtekfun.ultimatemaps.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.CameraState
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Compass, north reset and scale bar: the maths on the JVM, the controls in Compose. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MapControlsTest {
    @get:Rule
    val rule = createComposeRule()

    // ---- compass -------------------------------------------------------------------------------------------

    @Test fun degreesFromNorthWrapsAround() {
        assertEquals(0f, degreesFromNorth(0f))
        assertEquals(0.2f, degreesFromNorth(359.8f), 1e-3f)
        assertEquals(0.2f, degreesFromNorth(-0.2f), 1e-3f)
        assertEquals(90f, degreesFromNorth(90f))
        assertEquals(90f, degreesFromNorth(270f))
        assertEquals(180f, degreesFromNorth(180f))
        assertEquals(10f, degreesFromNorth(730f), 1e-3f)
    }

    @Test fun theCompassIsHiddenWhenFlatAndNorthUpIncludingJustBelow360() {
        assertFalse(compassVisible(0f, 0f))
        assertFalse(compassVisible(0.4f, 0f))
        assertFalse(compassVisible(359.8f, 0f)) // the engine reports 359.8, not -0.2: still north-up
        assertTrue(compassVisible(40f, 0f))
        assertTrue(compassVisible(350f, 0f))
    }

    @Test fun theCompassAlsoShowsWhenOnlyTilted() {
        assertTrue(compassVisible(0f, 45f))
        assertFalse(compassVisible(0f, 0.3f))
    }

    @Test fun tappingTheCompassCallsResetNorthAndItIsAbsentWhenNorthUp() {
        var resets = 0
        val state = MapScreenState()
        rule.setContent {
            MapasTheme(darkTheme = false) { MapScreen(state, onLocate = {}, onResetNorth = { resets++ }) {} }
        }
        rule.onNodeWithTag("btn_compass").assertDoesNotExist()
        state.onCamera(CameraState(LatLon(40.0, -3.0), 12.0, bearing = 359.9))
        rule.waitForIdle()
        rule.onNodeWithTag("btn_compass").assertDoesNotExist()
        state.onCamera(CameraState(LatLon(40.0, -3.0), 12.0, bearing = 0.0, tilt = 50.0))
        rule.waitForIdle()
        rule.onNodeWithTag("btn_compass").assertIsDisplayed()
        rule.onNodeWithTag("btn_compass").performClick()
        assertEquals(1, resets)
        state.onCamera(CameraState(LatLon(40.0, -3.0), 12.0)) // after the reset the engine reports north-up and flat
        rule.waitForIdle()
        rule.onNodeWithTag("btn_compass").assertDoesNotExist()
    }

    @Test fun theStateTakesEveryCameraValue() {
        val state = MapScreenState()
        assertFalse(state.cameraKnown)
        state.onCamera(CameraState(LatLon(40.5, -3.5), 14.5, bearing = 33.0, tilt = 12.0))
        assertTrue(state.cameraKnown)
        assertEquals(33f, state.bearing)
        assertEquals(12f, state.tilt)
        assertEquals(40.5, state.centerLatitude)
        assertEquals(14.5, state.zoom)
    }

    // ---- settings button in the right-hand column -------------------------------------------------------------

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test fun theSettingsButtonSitsInTheRightColumnUnderLocateAndCompass() {
        var opened = 0
        val state = MapScreenState().apply { onOpenSettings = { opened++ }; onCamera(CameraState(LatLon(0.0, 0.0), 12.0, bearing = 40.0)) }
        rule.setContent { MapasTheme(darkTheme = false) { MapScreen(state, onLocate = {}, onResetNorth = {}) {} } }
        val locate = bounds("btn_locate")
        val compass = bounds("btn_compass")
        val settings = bounds("btn_settings")
        assertTrue(locate.bottom <= compass.top && compass.bottom <= settings.top, "order: locate, compass, settings")
        assertEquals(locate.left, settings.left, 0.5f) // one column, same width
        assertEquals(locate.right, settings.right, 0.5f)
        assertEquals(locate.width, settings.width, 0.5f) // same chip size
        // The attribution stays top-left with nothing under it but the scale bar, which does not touch any button.
        val attribution = bounds("attribution")
        assertTrue(attribution.right < settings.left && attribution.top < settings.bottom)
        val scale = bounds("map_scale")
        assertTrue(scale.left >= attribution.left - 0.5f && scale.top >= attribution.bottom, "the scale bar is under the attribution")
        assertTrue(scale.right < settings.left, "the scale bar does not collide with the right column")
        rule.onNodeWithTag("btn_settings").performClick()
        assertEquals(1, opened)
    }

    @Test fun withoutTheCompassTheSettingsButtonIsRightUnderLocateAndKeepsItsDescription() {
        val state = MapScreenState().apply { onCamera(CameraState(LatLon(0.0, 0.0), 12.0)) }
        rule.setContent { MapasTheme(darkTheme = false) { MapScreen(state, onLocate = {}, onResetNorth = {}) {} } }
        rule.onNodeWithTag("btn_compass").assertDoesNotExist()
        val locate = bounds("btn_locate")
        val settings = bounds("btn_settings")
        assertTrue(settings.top >= locate.bottom && settings.top - locate.bottom < locate.height, "only a small gap")
        assertEquals("Settings", rule.onNodeWithTag("btn_settings").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.single())
    }

    @Test fun gloveModeGrowsEveryButtonOfTheColumnTo56dp() {
        val state = MapScreenState().apply { onCamera(CameraState(LatLon(0.0, 0.0), 12.0, bearing = 40.0)) }
        rule.setContent { MapasTheme(darkTheme = false, gloveMode = true) { MapScreen(state, onLocate = {}, onResetNorth = {}) {} } }
        val dp56 = with(rule.density) { 56.dp.toPx() }
        listOf("btn_locate", "btn_compass", "btn_settings").forEach { assertEquals(dp56, bounds(it).height, 0.5f, it) }
    }

    @Test fun noSettingsButtonWhileNavigating() {
        val state = MapScreenState()
        rule.setContent { MapasTheme(darkTheme = false) { MapScreen(state, onLocate = {}, onResetNorth = {}, navigating = true) {} } }
        rule.onNodeWithTag("btn_settings").assertDoesNotExist()
    }

    // ---- scale maths ---------------------------------------------------------------------------------------

    @Test fun metersPerDpHalvesWithEachZoomLevelAndShrinksWithLatitude() {
        val z0 = MapScale.metersPerDp(0.0, 0.0)
        assertEquals(MapScale.EQUATOR_METERS / 512.0, z0, 1e-9)
        assertEquals(z0 / 2, MapScale.metersPerDp(0.0, 1.0), 1e-9)
        assertEquals(z0 / 1024, MapScale.metersPerDp(0.0, 10.0), 1e-9)
        assertEquals(MapScale.metersPerDp(0.0, 8.0) / 2, MapScale.metersPerDp(60.0, 8.0), 1e-6) // cos 60 degrees = 1/2
        assertEquals(MapScale.metersPerDp(45.0, 8.0), MapScale.metersPerDp(-45.0, 8.0), 1e-9)
    }

    @Test fun niceMetersPicksTheLargestOneTwoOrFive() {
        assertNull(MapScale.niceMeters(0.5))
        assertNull(MapScale.niceMeters(Double.NaN))
        assertEquals(1, MapScale.niceMeters(1.0))
        assertEquals(1, MapScale.niceMeters(1.99))
        assertEquals(2, MapScale.niceMeters(2.0))
        assertEquals(2, MapScale.niceMeters(4.99))
        assertEquals(5, MapScale.niceMeters(5.0))
        assertEquals(5, MapScale.niceMeters(9.99))
        assertEquals(10, MapScale.niceMeters(10.0))
        assertEquals(200, MapScale.niceMeters(399.0))
        assertEquals(500, MapScale.niceMeters(999.0))
        assertEquals(1000, MapScale.niceMeters(1000.0))
        assertEquals(5000, MapScale.niceMeters(7500.0))
    }

    @Test fun theBarNeverExceedsTheMaximumWidthAndRepresentsItsDistance() {
        for (zoom in listOf(0.0, 3.0, 8.5, 12.0, 15.0, 18.0, 21.0)) for (lat in listOf(0.0, 40.4, -55.0, 75.0)) {
            val bar = MapScale.bar(lat, zoom, 96f) ?: continue
            assertTrue(bar.widthDp <= 96f + 1e-3f, "width $zoom/$lat")
            assertTrue(bar.widthDp > 96f / 2.5f - 1e-3f, "not too short $zoom/$lat") // 1-2-5 steps are at most 2.5x apart
            assertEquals(bar.meters.toDouble(), bar.widthDp * MapScale.metersPerDp(lat, zoom), 1e-3 * bar.meters, "distance $zoom/$lat")
        }
    }

    @Test fun noBarAtThePoleOrBeyondAbsurdZoom() {
        assertNull(MapScale.bar(90.0, 10.0, 96f))
        assertNull(MapScale.bar(0.0, 40.0, 96f)) // under a metre across the whole bar
    }

    // ---- scale bar ------------------------------------------------------------------------------------------

    private fun label(): String? =
        rule.onNodeWithTag("map_scale_label").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    @Test fun theScaleBarWaitsForTheFirstCameraThenShowsMetresAndKilometres() {
        val state = MapScreenState()
        rule.setContent {
            MapasTheme(darkTheme = false) { MapScreen(state, onLocate = {}, onResetNorth = {}) {} }
        }
        rule.onNodeWithTag("map_scale").assertDoesNotExist()
        // Equator, zoom 12: 40075016.686 / 512 / 4096 = 19.1 m per dp, 96 dp = 1834 m, so the bar is 1 km.
        state.onCamera(CameraState(LatLon(0.0, 0.0), 12.0))
        rule.waitForIdle()
        rule.onNodeWithTag("map_scale").assertIsDisplayed()
        assertEquals("1 km", label())
        // Zoom 15: 2.39 m per dp, 229 m across: 200 m.
        state.onCamera(CameraState(LatLon(0.0, 0.0), 15.0))
        rule.waitForIdle()
        assertEquals("200 m", label())
        val description = rule.onNodeWithTag("map_scale").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription)?.single()
        assertEquals("Map scale: 200 m", description)
        assertNotNull(description)
    }

    @Test fun theScaleBarIsHiddenWhileNavigating() {
        val state = MapScreenState().apply { onCamera(CameraState(LatLon(0.0, 0.0), 15.0)) }
        rule.setContent {
            MapasTheme(darkTheme = false) { MapScreen(state, onLocate = {}, onResetNorth = {}, navigating = true) {} }
        }
        rule.onNodeWithTag("map_scale").assertDoesNotExist()
    }

    @Test @Config(sdk = [34], qualifiers = "es-rES-w411dp-h891dp-xxhdpi")
    fun theSpanishScaleDescription() {
        val state = MapScreenState().apply { onCamera(CameraState(LatLon(0.0, 0.0), 12.0)) }
        rule.setContent {
            MapasTheme(darkTheme = false) { MapScreen(state, onLocate = {}, onResetNorth = {}) {} }
        }
        val description = rule.onNodeWithTag("map_scale").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription)?.single()
        assertEquals("Escala del mapa: 1 km", description)
    }
}
