package com.qtekfun.mapas.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.ui.sheet.SheetDetent
import com.qtekfun.mapas.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drag, fling and nested-scroll behaviour of the bottom sheet under Robolectric. Gestures are synthetic: they check
 * the logic (which detent, who scrolls), not how the real finger feels. Container 891 dp at 3x: collapsed 321 px,
 * medium ~1230 px, full ~2481 px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SheetGestureTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var listState: LazyListState

    private fun show(state: MapScreenState, withList: Boolean = false) {
        rule.setContent {
            MapasTheme(darkTheme = false) {
                listState = rememberLazyListState()
                val panel: (@androidx.compose.runtime.Composable () -> Unit)? = if (withList) {
                    {
                        LazyColumn(Modifier.fillMaxSize().testTag("test_list"), state = listState) {
                            items(200) { BasicText("Item $it", Modifier.height(48.dp)) }
                        }
                    }
                } else null
                MapScreen(state, onLocate = {}, onResetNorth = {}, sheetPanel = panel) {}
            }
        }
    }

    /** Vertical swipe on [tag] by [dy] px (negative = up) lasting [ms], starting [fromTop] px below the node top. */
    private fun swipe(tag: String, dy: Float, ms: Long, fromTop: Float = 200f) {
        rule.onNodeWithTag(tag).performTouchInput {
            val x = width / 2f
            down(Offset(x, fromTop))
            val steps = (ms / 16).toInt().coerceAtLeast(2)
            repeat(steps) {
                moveBy(Offset(0f, dy / steps), 16)
            }
            up()
        }
        rule.waitForIdle()
    }

    @Test
    fun handleIsAtLeast48dpAndTheWholeSheetIsDraggable() {
        val state = MapScreenState().apply { detent = SheetDetent.MEDIUM }
        show(state)
        rule.onNodeWithTag("sheet_handle").assertHeightIsAtLeast(48.dp)
        // Starts on the title area, far from the handle.
        swipe("sheet", dy = -150f, ms = 50, fromTop = 300f)
        assertEquals(SheetDetent.FULL, state.detent)
    }

    @Test
    fun plainSwipeUpAndSwipeDownMoveTheSheet() {
        val state = MapScreenState().apply { detent = SheetDetent.MEDIUM }
        show(state)
        rule.onNodeWithTag("sheet").performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertEquals(SheetDetent.FULL, state.detent)
        rule.onNodeWithTag("sheet").performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertTrue(state.detent != SheetDetent.FULL, "${state.detent}")
    }

    @Test
    fun fastShortFlingMovesToTheNextDetentInItsDirection() {
        val state = MapScreenState().apply { detent = SheetDetent.COLLAPSED }
        show(state)
        swipe("sheet", dy = -150f, ms = 50)
        assertEquals(SheetDetent.MEDIUM, state.detent)
        swipe("sheet", dy = -150f, ms = 50)
        assertEquals(SheetDetent.FULL, state.detent)
        swipe("sheet", dy = 150f, ms = 50)
        assertEquals(SheetDetent.MEDIUM, state.detent)
        swipe("sheet", dy = 150f, ms = 50)
        assertEquals(SheetDetent.COLLAPSED, state.detent)
    }

    @Test
    fun slowDragGoesToTheNextDetentAfterAQuarterOfTheGapButNotBefore() {
        val state = MapScreenState().apply { detent = SheetDetent.MEDIUM }
        show(state)
        // Gap to FULL is ~1250 px; 25 % = ~310 px (plus 24 px of slop given back), slow = well under 600 px/s.
        swipe("sheet", dy = -150f, ms = 1500)
        assertEquals(SheetDetent.MEDIUM, state.detent, "12 % of the gap is not enough")
        swipe("sheet", dy = -500f, ms = 1000, fromTop = 1100f)
        assertEquals(SheetDetent.FULL, state.detent, "40 % of the gap is")
    }

    @Test
    fun swipingUpOnAListGrowsTheSheetBeforeTheListScrolls() {
        val state = MapScreenState().apply { detent = SheetDetent.MEDIUM }
        show(state, withList = true)
        swipe("test_list", dy = -600f, ms = 1500, fromTop = 400f)
        assertEquals(SheetDetent.FULL, state.detent)
        assertEquals(0, listState.firstVisibleItemIndex, "the list waits until the sheet is full")
    }

    @Test
    fun swipingDownOnAListAtTheTopMovesTheSheetInsteadOfGettingStuck() {
        val state = MapScreenState().apply { detent = SheetDetent.FULL }
        show(state, withList = true)
        assertEquals(0, listState.firstVisibleItemIndex)
        swipe("test_list", dy = 150f, ms = 50, fromTop = 100f)
        assertEquals(SheetDetent.MEDIUM, state.detent)
        assertEquals(0, listState.firstVisibleItemIndex)
    }

    @Test
    fun aFullSheetLetsTheListScroll() {
        val state = MapScreenState().apply { detent = SheetDetent.FULL }
        show(state, withList = true)
        swipe("test_list", dy = -600f, ms = 400, fromTop = 800f)
        assertEquals(SheetDetent.FULL, state.detent)
        assertTrue(listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0, "list scrolled")
    }

    @Test
    fun aScrolledListIsNotStolenByTheSheet() {
        val state = MapScreenState().apply { detent = SheetDetent.FULL }
        show(state, withList = true)
        swipe("test_list", dy = -900f, ms = 600, fromTop = 1000f)
        val scrolled = listState.firstVisibleItemIndex
        assertTrue(scrolled > 0)
        swipe("test_list", dy = 150f, ms = 300, fromTop = 300f)
        assertEquals(SheetDetent.FULL, state.detent, "list not at the top: it scrolls back, the sheet stays")
        assertTrue(listState.firstVisibleItemIndex < scrolled)
    }

    @Test
    fun handleOffersExpandAndCollapseActionsForTalkBack() {
        val state = MapScreenState().apply { detent = SheetDetent.COLLAPSED }
        show(state)
        fun actions() = rule.onNodeWithTag("sheet_handle").fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf("Expand panel"), actions().map { it.label })
        rule.runOnIdle { actions().single().action() }
        rule.waitForIdle()
        assertEquals(SheetDetent.MEDIUM, state.detent)
        assertEquals(listOf("Expand panel", "Collapse panel"), actions().map { it.label })
        rule.runOnIdle { actions().last().action() }
        rule.waitForIdle()
        assertEquals(SheetDetent.COLLAPSED, state.detent)
        state.detent = SheetDetent.FULL
        rule.waitForIdle()
        assertEquals(listOf("Collapse panel"), actions().map { it.label })
    }
}
