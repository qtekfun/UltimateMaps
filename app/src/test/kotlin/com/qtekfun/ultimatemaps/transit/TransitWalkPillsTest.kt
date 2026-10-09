package com.qtekfun.ultimatemaps.transit

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.transit.ItineraryLeg
import com.qtekfun.ultimatemaps.core.transit.ItineraryStop
import com.qtekfun.ultimatemaps.core.transit.JourneyBadge
import com.qtekfun.ultimatemaps.core.transit.LineInfo
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The walking pills in the badge row: present, in order, never cut at 320 dp with a 1.3 font, spoken in en and es. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TransitWalkPillsTest {
    @get:Rule
    val rule = createComposeRule()

    private val p = LatLon(40.0, -3.0)
    private fun ride(name: String, type: Int): ItineraryLeg.Ride {
        val stop = ItineraryStop("A", p, 0, 0, null)
        return ItineraryLeg.Ride(LineInfo(name, name, 0xFF1565C0.toInt(), 0xFFFFFFFF.toInt(), type), "Head", listOf(stop, stop))
    }

    /** walk 6 > bus 480 > train C5 > walk 3 > train C4b > walk 11, as in the owner's example. */
    private val badges = listOf(
        JourneyBadge.Walk(6), JourneyBadge.Line(ride("480", 3), 0), JourneyBadge.Line(ride("C5", 2), 1),
        JourneyBadge.Walk(3), JourneyBadge.Line(ride("C4b", 2), 2), JourneyBadge.Walk(11),
    )

    private fun show(fontScale: Float, list: List<JourneyBadge> = badges, walkOnly: Boolean = false, dark: Boolean = false) = rule.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            MapasTheme(darkTheme = dark) { Column(Modifier.testTag("host")) { BadgeRow(0, list, walkOnly) } }
        }
    }

    private fun spoken(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: ""

    private data class T(val label: String, val box: Float, val textWidth: Float, val lines: Int)

    private fun texts(root: SemanticsNode): List<T> {
        val out = mutableListOf<T>()
        fun visit(n: SemanticsNode) {
            val results = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            results.firstOrNull()?.let { r -> out += T(r.layoutInput.text.text, n.boundsInRoot.width, r.multiParagraph.maxIntrinsicWidth, r.lineCount) }
            n.children.forEach(::visit)
        }
        visit(root)
        return out
    }

    private fun assertOrderAndFit() {
        val tags = listOf(
            "transit_option_0_walk_0", "transit_option_0_chip_0", "transit_option_0_chip_1",
            "transit_option_0_walk_3", "transit_option_0_chip_2", "transit_option_0_walk_5",
        )
        val width = rule.onNodeWithTag("host").fetchSemanticsNode().boundsInRoot.width
        var lastKey = -1f
        for (t in tags) {
            rule.onNodeWithTag(t).assertIsDisplayed()
            val b = rule.onNodeWithTag(t).fetchSemanticsNode().boundsInRoot
            assertTrue("$t sticks out: ${b.right} > $width", b.right <= width + 0.5f)
            val key = b.top * 10_000f + b.left // reading order: top to bottom, then left to right
            assertTrue("$t is out of order", key > lastKey)
            lastKey = key
        }
        val all = texts(rule.onNodeWithTag("host", useUnmergedTree = true).fetchSemanticsNode())
        assertTrue(all.size >= 6)
        for (t in all) {
            assertEquals("${t.label} wraps", 1, t.lines)
            assertTrue("${t.label} is cut: ${t.box} < ${t.textWidth}", t.box >= t.textWidth - 0.5f)
        }
    }

    @Test
    fun pillsAndLinesAppearInOrderAt411() {
        show(1f)
        assertOrderAndFit()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun nothingIsCutAt320WithLargeFontEnglish() {
        show(1.3f)
        assertOrderAndFit()
    }

    @Test @Config(qualifiers = "es-w320dp-h640dp-xhdpi")
    fun nothingIsCutAt320WithLargeFontSpanish() {
        show(1.3f)
        assertOrderAndFit()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun theRowWrapsOntoASecondLineAtLargeFont() {
        show(1.3f)
        val first = rule.onNodeWithTag("transit_option_0_walk_0").fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithTag("transit_option_0_walk_5").fetchSemanticsNode().boundsInRoot
        assertTrue("expected a second line", last.top >= first.bottom - 1f)
    }

    @Test
    fun pillsAreSpokenInEnglish() {
        show(1f)
        assertEquals("Walk 6 minutes", spoken("transit_option_0_walk_0"))
        assertEquals("Walk 3 minutes", spoken("transit_option_0_walk_3"))
        assertEquals("Walk 11 minutes", spoken("transit_option_0_walk_5"))
    }

    @Test
    fun oneMinuteIsSingular() {
        show(1f, listOf(JourneyBadge.Walk(1), JourneyBadge.Line(ride("1", 3), 0)))
        assertEquals("Walk 1 minute", spoken("transit_option_0_walk_0"))
    }

    @Test @Config(qualifiers = "es-w411dp-h891dp-xxhdpi")
    fun pillsAreSpokenInSpanish() {
        show(1f)
        assertEquals("Caminar 6 minutos", spoken("transit_option_0_walk_0"))
        assertEquals("Caminar 11 minutos", spoken("transit_option_0_walk_5"))
    }

    @Test
    fun walkOnlyShowsOneLabelledPill() {
        show(1f, listOf(JourneyBadge.Walk(15)), walkOnly = true)
        rule.onNodeWithTag("transit_option_0_walk_0").assertIsDisplayed()
        assertTrue(texts(rule.onNodeWithTag("host", useUnmergedTree = true).fetchSemanticsNode()).any { it.label == "Walk · 15 min" })
    }

    @Test
    fun regularPillShowsJustTheMinutes() {
        show(1f)
        val labels = texts(rule.onNodeWithTag("host", useUnmergedTree = true).fetchSemanticsNode()).map { it.label }
        assertTrue(labels.containsAll(listOf("6 min", "3 min", "11 min")))
    }

    @Test
    fun pillsRenderInDarkMode() {
        show(1f, dark = true)
        rule.onNodeWithTag("transit_option_0_walk_0").assertIsDisplayed()
    }
}
