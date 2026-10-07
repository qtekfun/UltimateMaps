package com.qtekfun.ultimatemaps.ui.sheet

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** The three resting positions of the sheet (Apple Maps style). */
enum class SheetDetent {
    COLLAPSED, MEDIUM, FULL;

    /** Next detent when the handle is tapped: expands step by step and wraps from FULL to COLLAPSED. */
    fun next(): SheetDetent = entries[(ordinal + 1) % entries.size]

    /** One step up / down without wrapping (accessibility actions); null at the end. */
    fun above(): SheetDetent? = entries.getOrNull(ordinal + 1)
    fun below(): SheetDetent? = entries.getOrNull(ordinal - 1)
}

/** Pure sheet geometry, independent of Compose so it can be unit-tested on the JVM. All values in pixels. */
object SheetMath {
    /** Fling speed from which the sheet goes to the next detent whatever the position, in dp/s (was 800 px/s). */
    const val FLING_DP_PER_S = 200f

    /** Share of the gap to the next detent that a slow drag must cover to leave the starting detent (was 50 %). */
    const val ADVANCE_FRACTION = 0.25f

    /** Size of the tap/drag target of the handle (accessibility minimum). */
    val HANDLE_TARGET: Dp = 48.dp

    /** Visible height of the sheet at [detent]. */
    fun height(detent: SheetDetent, collapsedPx: Float, mediumPx: Float, fullPx: Float): Float = when (detent) {
        SheetDetent.COLLAPSED -> collapsedPx
        SheetDetent.MEDIUM -> mediumPx
        SheetDetent.FULL -> fullPx
    }

    /**
     * Detent to settle at after a drag that ends at [currentPx] with vertical [velocityPxPerS]
     * (positive = growing). A fling above [flingThreshold] goes to the next detent in its direction.
     * A slower release goes to the nearest detent, except that when the drag [start]ed at a detent it already
     * leaves it after covering [advanceFraction] of the gap towards its neighbour in the direction of the drag.
     */
    fun settle(
        currentPx: Float,
        velocityPxPerS: Float,
        collapsedPx: Float,
        mediumPx: Float,
        fullPx: Float,
        flingThreshold: Float = 500f,
        start: SheetDetent? = null,
        advanceFraction: Float = ADVANCE_FRACTION,
    ): SheetDetent {
        val nearest = nearest(currentPx, collapsedPx, mediumPx, fullPx)
        if (abs(velocityPxPerS) < flingThreshold) {
            if (start == null || nearest != start) return nearest
            val from = height(start, collapsedPx, mediumPx, fullPx)
            val neighbour = (if (currentPx > from) start.above() else if (currentPx < from) start.below() else null)
                ?: return start
            val gap = abs(height(neighbour, collapsedPx, mediumPx, fullPx) - from)
            return if (gap > 0f && abs(currentPx - from) >= advanceFraction * gap) neighbour else start
        }
        val up = velocityPxPerS > 0
        return when {
            up && currentPx < mediumPx -> if (nearest == SheetDetent.COLLAPSED) SheetDetent.MEDIUM else nearest
            up -> SheetDetent.FULL
            currentPx > mediumPx -> if (nearest == SheetDetent.FULL) SheetDetent.MEDIUM else nearest
            else -> SheetDetent.COLLAPSED
        }
    }

    fun nearest(currentPx: Float, collapsedPx: Float, mediumPx: Float, fullPx: Float): SheetDetent {
        val dc = abs(currentPx - collapsedPx)
        val dm = abs(currentPx - mediumPx)
        val df = abs(currentPx - fullPx)
        return when {
            dc <= dm && dc <= df -> SheetDetent.COLLAPSED
            dm <= df -> SheetDetent.MEDIUM
            else -> SheetDetent.FULL
        }
    }

    /** True when the sheet rests (within half a pixel) on one of its detents. */
    fun atDetent(currentPx: Float, collapsedPx: Float, mediumPx: Float, fullPx: Float): Boolean {
        val n = nearest(currentPx, collapsedPx, mediumPx, fullPx)
        return abs(currentPx - height(n, collapsedPx, mediumPx, fullPx)) < 0.5f
    }

    /**
     * Nested scrolling, before the child: a finger moving up ([fingerDy] < 0) grows the sheet first, until
     * [maxPx]. Returns the height gained (>= 0); the child gets the rest.
     */
    fun growBeforeChild(heightPx: Float, fingerDy: Float, maxPx: Float): Float =
        if (fingerDy < 0f) minOf(-fingerDy, (maxPx - heightPx).coerceAtLeast(0f)) else 0f

    /**
     * Nested scrolling, after the child: what the child could not scroll ([fingerDy] > 0, finger moving down,
     * list already at the top) shrinks the sheet, down to [minPx]. Returns the height lost (>= 0).
     */
    fun shrinkAfterChild(heightPx: Float, fingerDy: Float, minPx: Float): Float =
        if (fingerDy > 0f) minOf(fingerDy, (heightPx - minPx).coerceAtLeast(0f)) else 0f
}

/** Height of the sheet outside of composition: drag is synchronous, settling is a spring that keeps the fling speed. */
@Stable
internal class SheetMotion(initial: Float, private val scope: CoroutineScope) {
    var height by mutableFloatStateOf(initial)
        private set
    var min = 0f
    var max = 0f

    /** Detent the current gesture started from (null when no gesture is in progress). */
    var gestureStart: SheetDetent? = null

    /** True between the start of a drag and its first delta (see the slop compensation). */
    var slopPending = false

    private var job: Job? = null
    private var animatingTo: Float? = null

    fun dragBy(growPx: Float) {
        job?.cancel()
        animatingTo = null
        height = (height + growPx).coerceIn(min, max)
    }

    fun animateTo(target: Float, velocity: Float = 0f) {
        if (animatingTo == target) return
        if (animatingTo == null && abs(height - target) < 0.5f) {
            height = target
            return
        }
        job?.cancel()
        animatingTo = target
        job = scope.launch {
            try {
                animate(height, target, velocity, SPRING) { v, _ -> height = v.coerceIn(min, max) }
            } finally {
                if (animatingTo == target) animatingTo = null
            }
        }
    }

    companion object {
        /** Faster than the old medium-low stiffness (400), still slightly under-damped: no visible overshoot at the end. */
        val SPRING = spring<Float>(dampingRatio = 0.9f, stiffness = 800f)
    }
}

/**
 * Lets a list inside the sheet move the sheet: swiping up grows it before the list scrolls, and swiping down on a
 * list that is already at the top shrinks it (instead of getting stuck). When the finger lifts between two detents
 * the sheet settles with the fling speed and the list does not fling.
 */
internal class SheetNestedScroll(
    private val motion: SheetMotion,
    private val detents: () -> Triple<Float, Float, Float>,
    private val begin: () -> Unit,
    private val settle: (growVelocity: Float) -> Unit,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        val gain = SheetMath.growBeforeChild(motion.height, available.y, motion.max)
        if (gain <= 0f) return Offset.Zero
        begin()
        motion.dragBy(gain)
        return Offset(0f, -gain)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        val loss = SheetMath.shrinkAfterChild(motion.height, available.y, motion.min)
        if (loss <= 0f) return Offset.Zero
        begin()
        motion.dragBy(-loss)
        return Offset(0f, loss)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (motion.gestureStart == null) return Velocity.Zero
        val (c, m, f) = detents()
        if (SheetMath.atDetent(motion.height, c, m, f)) {
            motion.gestureStart = null // e.g. grew to FULL: the list may fling on its own
            return Velocity.Zero
        }
        settle(-available.y)
        return Velocity(0f, available.y)
    }
}

/**
 * Bottom sheet with three detents. The visible height is read only in the layout phase, so dragging does not
 * recompose [content]. [topInset] reserves room at the top in FULL (status bar and map buttons).
 *
 * The whole sheet is draggable (handle, tabs, any non-scrolling area) and scrolling lists inside it cooperate through
 * nested scrolling. The handle is a 48 dp target; it also exposes "expand" and "collapse" accessibility actions.
 */
@Composable
fun BottomSheet(
    detent: SheetDetent,
    onDetentChange: (SheetDetent) -> Unit,
    sheetDescription: String,
    handleDescription: String,
    detentLabel: (SheetDetent) -> String,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    expandActionLabel: String = "",
    collapseActionLabel: String = "",
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val containerPx = with(density) { maxHeight.toPx() }
        val collapsedPx = with(density) { Mapas.dimens.sheetCollapsedHeight.toPx() }
        val fullPx = containerPx - with(density) { topInset.toPx() }
        val mediumPx = (containerPx * Mapas.dimens.sheetMediumFraction).coerceIn(collapsedPx, fullPx)
        val flingPx = with(density) { SheetMath.FLING_DP_PER_S.dp.toPx() }
        val slopPx = LocalViewConfiguration.current.touchSlop
        val target = SheetMath.height(detent, collapsedPx, mediumPx, fullPx)
        val scope = rememberCoroutineScope()
        val motion = remember { SheetMotion(target, scope) }
        motion.min = collapsedPx
        motion.max = fullPx
        val currentDetent by rememberUpdatedState(detent)
        val onChange by rememberUpdatedState(onDetentChange)
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current

        LaunchedEffect(detent, collapsedPx, mediumPx, fullPx) { motion.animateTo(target) }

        fun begin() {
            if (motion.gestureStart == null) motion.gestureStart = currentDetent
        }

        fun settle(growVelocity: Float) {
            val next = SheetMath.settle(
                motion.height, growVelocity, collapsedPx, mediumPx, fullPx, flingPx, motion.gestureStart,
            )
            motion.gestureStart = null
            onChange(next)
            if (next != SheetDetent.FULL) {
                // A field with the keyboard open must not fight the sheet going down.
                focus.clearFocus()
                keyboard?.hide()
            }
            motion.animateTo(SheetMath.height(next, collapsedPx, mediumPx, fullPx), growVelocity)
        }

        val nested = remember(motion, collapsedPx, mediumPx, fullPx) {
            SheetNestedScroll(motion, { Triple(collapsedPx, mediumPx, fullPx) }, ::begin, ::settle)
        }

        val dragState = rememberDraggableState { delta ->
            begin()
            // draggable swallows the touch slop: give it back so the sheet stays under the finger.
            val d = if (motion.slopPending) { motion.slopPending = false; delta + slopPx * sign(delta) } else delta
            motion.dragBy(-d)
        }

        val actions = buildList {
            detent.above()?.let { up ->
                add(CustomAccessibilityAction(expandActionLabel.ifEmpty { handleDescription }) { onChange(up); true })
            }
            detent.below()?.let { down ->
                add(CustomAccessibilityAction(collapseActionLabel.ifEmpty { handleDescription }) { onChange(down); true })
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val h = motion.height.toInt().coerceAtLeast(0)
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .clip(Mapas.shapes.sheet)
                .background(Mapas.colors.sheet)
                .nestedScroll(nested)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStarted = { motion.slopPending = true },
                    onDragStopped = { velocity ->
                        motion.slopPending = false
                        settle(-velocity)
                    },
                )
                .testTag("sheet")
                .semantics {
                    contentDescription = sheetDescription
                    stateDescription = detentLabel(detent)
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SheetMath.HANDLE_TARGET)
                    .clickable(onClickLabel = handleDescription) { onChange(currentDetent.next()) }
                    .semantics { customActions = actions }
                    .testTag("sheet_handle"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 5.dp)
                        .clip(Mapas.shapes.pill)
                        .background(Mapas.colors.sheetHandle),
                )
            }
            content()
        }
    }
}
