package com.qtekfun.ultimatemaps.transit

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.nativecomaps.DetailedRoutingEngine
import com.qtekfun.ultimatemaps.nativecomaps.RouteCode
import com.qtekfun.ultimatemaps.nativecomaps.RouteOutcome
import com.qtekfun.ultimatemaps.places.PlaceInfo
import com.qtekfun.ultimatemaps.route.RoutePanel
import com.qtekfun.ultimatemaps.route.RoutePreviewController
import com.qtekfun.ultimatemaps.search.CoreMaps
import com.qtekfun.ultimatemaps.search.InstalledRegions
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import com.qtekfun.ultimatemaps.core.transit.LineInfo
import com.qtekfun.ultimatemaps.core.transit.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Two fixes for a Spanish 411 dp phone: the line badge names its mode (a bus "C2" is not the train "C2"), and the travel-mode
 * selector never cuts a label ("Transporte"), also at narrow widths and large font scales.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TransitBadgesAndModeLabelTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transitScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val service = TransitTestSupport.service()
    private val lookup = TransitLookup.Ready(service, "Testville")
    private val transit = TransitController(
        transitScope, Dispatchers.Unconfined, { _, _ -> lookup }, TransitTestSupport.clockAt("2026-10-14T07:55:00"),
    )

    private val route = RoutePreviewController(
        scope, Dispatchers.IO,
        object : InstalledRegions { override fun coreMaps() = CoreMaps(File("/maps"), 1) },
        backend = { _, _ ->
            object : DetailedRoutingEngine {
                private val outcome = RouteOutcome(RouteCode.NO_ERROR, RoutePlan(listOf(LatLon(40.0, -3.0), LatLon(40.1, -3.1)), 1_000.0, 100.0))
                override fun routeDetailed(request: com.qtekfun.ultimatemaps.core.routing.RouteRequest) = outcome
                override fun route(request: com.qtekfun.ultimatemaps.core.routing.RouteRequest) = outcome.plan
                override fun close() = Unit
            }
        },
        userLocation = { TransitTestSupport.nearA }, showRoute = {}, clearRoute = {}, clock = { 0L },
        log = { _, _, _ -> },
        transit = transit,
    )

    @After fun tearDown() {
        scope.cancel()
        transitScope.cancel()
    }

    private fun show(fontScale: Float = 1f) = rule.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            MapasTheme(darkTheme = false) { RoutePanel(route, onUseLocation = {}, originSearch = {}) }
        }
    }

    /** Opens the transit mode for a trip before composing, so the first composition reads the final state. */
    private fun startTransit() {
        route.setTransitMode(true)
        route.start(PlaceInfo("Charlie Town", TransitTestSupport.nearC))
    }

    private fun assertLabelsFullyShown(fontScale: Float, expected: List<String>) {
        route.start(PlaceInfo("Charlie Town", TransitTestSupport.nearC))
        show(fontScale)
        val tags = listOf("profile_car", "profile_foot", "profile_bike", "profile_transit")
        tags.forEachIndexed { i, tag ->
            rule.onNodeWithTag(tag).assertIsDisplayed()
            val node = rule.onNodeWithTag(tag + "_label", useUnmergedTree = true)
            node.assertIsDisplayed()
            val config = node.fetchSemanticsNode().config
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val layout = results.single()
            assertEquals(expected[i], layout.layoutInput.text.text)
            // softWrap is off, so the label is never cut by wrapping: it is cut only when its box is narrower than the text.
            val box = node.fetchSemanticsNode().boundsInRoot
            assertTrue("${expected[i]} is cut: box ${box.width} < text ${layout.multiParagraph.maxIntrinsicWidth}", box.width >= layout.multiParagraph.maxIntrinsicWidth - 0.5f)
            assertEquals("${expected[i]} wraps", 1, layout.lineCount)
        }
    }

    private val english = listOf("Car", "Walk", "Bike", "Transit")
    private val spanish = listOf("Coche", "A pie", "Bici", "Transporte")

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun englishLabelsFit411() = assertLabelsFullyShown(1f, english)

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun englishLabelsFit411LargeFont() = assertLabelsFullyShown(1.3f, english)

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun englishLabelsFit320LargeFont() = assertLabelsFullyShown(1.3f, english)

    @Test @Config(qualifiers = "es-w411dp-h891dp-xxhdpi")
    fun spanishLabelsFit411() = assertLabelsFullyShown(1f, spanish)

    @Test @Config(qualifiers = "es-w411dp-h891dp-xxhdpi")
    fun spanishLabelsFit411LargeFont() = assertLabelsFullyShown(1.3f, spanish)

    @Test @Config(qualifiers = "es-w320dp-h640dp-xhdpi")
    fun spanishLabelsFit320() = assertLabelsFullyShown(1f, spanish)

    @Test @Config(qualifiers = "es-w320dp-h640dp-xhdpi")
    fun spanishLabelsFit320LargeFont() = assertLabelsFullyShown(1.3f, spanish)

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun theBadgeNamesItsModeInTheListAndInTheLegs() {
        startTransit()
        show()
        rule.onNodeWithTag("transit_option_0_chip_0", useUnmergedTree = true).assertContentDescriptionEquals("Metro line M1")
        rule.onNodeWithTag("transit_option_0").performClick()
        rule.onNodeWithTag("transit_leg_1_chip", useUnmergedTree = true).assertContentDescriptionEquals("Metro line M1")
    }

    @Test @Config(qualifiers = "es-w411dp-h891dp-xxhdpi")
    fun theBadgeNamesItsModeInSpanish() {
        startTransit()
        show()
        rule.onNodeWithTag("transit_option_0_chip_0", useUnmergedTree = true).assertContentDescriptionEquals("Línea de metro M1")
    }

    @Test
    fun routeTypesMapToDistinctModesAndSpokenForms() {
        fun line(type: Int) = LineInfo("C2", "x", 0, 0, type)
        assertEquals(TransitMode.BUS, lineMode(line(3)))
        assertEquals(TransitMode.TRAIN, lineMode(line(2)))
        assertEquals(TransitMode.TRAIN, lineMode(line(109)))
        assertEquals(TransitMode.TRAM, lineMode(line(0)))
        assertEquals(TransitMode.METRO, lineMode(line(1)))
        assertEquals(TransitMode.FERRY, lineMode(line(4)))
        assertEquals(TransitMode.OTHER, lineMode(line(7)))
        val modes = TransitMode.entries
        assertEquals(modes.size, modes.map { TransitModeIcons.description(it) }.toSet().size)
        assertEquals(modes.size, modes.map { TransitModeIcons.of(it) }.toSet().size)
        assertTrue(modes.all { TransitModeIcons.of(it).name.startsWith("mode_") })
    }
}
