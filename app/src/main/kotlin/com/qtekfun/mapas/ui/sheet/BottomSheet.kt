package com.qtekfun.mapas.ui.sheet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.ui.theme.Mapas
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The three resting positions of the sheet (Apple Maps style). */
enum class SheetDetent {
    COLLAPSED, MEDIUM, FULL;

    /** Next detent when the handle is tapped: expands step by step and wraps from FULL to COLLAPSED. */
    fun next(): SheetDetent = entries[(ordinal + 1) % entries.size]
}

/** Pure sheet geometry, independent of Compose so it can be unit-tested on the JVM. All values in pixels. */
object SheetMath {
    /** Visible height of the sheet at [detent]. */
    fun height(detent: SheetDetent, collapsedPx: Float, mediumPx: Float, fullPx: Float): Float = when (detent) {
        SheetDetent.COLLAPSED -> collapsedPx
        SheetDetent.MEDIUM -> mediumPx
        SheetDetent.FULL -> fullPx
    }

    /**
     * Detent to settle at after a drag that ends at [currentPx] with vertical [velocityPxPerS]
     * (positive = growing). A fling above [flingThreshold] goes to the next detent in its direction;
     * otherwise the nearest one wins.
     */
    fun settle(
        currentPx: Float,
        velocityPxPerS: Float,
        collapsedPx: Float,
        mediumPx: Float,
        fullPx: Float,
        flingThreshold: Float = 800f,
    ): SheetDetent {
        val nearest = nearest(currentPx, collapsedPx, mediumPx, fullPx)
        if (abs(velocityPxPerS) < flingThreshold) return nearest
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
}

/**
 * Bottom sheet with three detents. The visible height is animated and read only in the layout phase,
 * so dragging does not recompose [content]. [topInset] reserves room at the top in FULL (status bar and map buttons).
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
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val containerPx = with(density) { maxHeight.toPx() }
        val collapsedPx = with(density) { Mapas.dimens.sheetCollapsedHeight.toPx() }
        val fullPx = containerPx - with(density) { topInset.toPx() }
        val mediumPx = (containerPx * Mapas.dimens.sheetMediumFraction).coerceIn(collapsedPx, fullPx)
        val target = SheetMath.height(detent, collapsedPx, mediumPx, fullPx)
        val height = remember { Animatable(target) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(detent, collapsedPx, mediumPx, fullPx) {
            height.animateTo(target, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        }

        val dragState = rememberDraggableState { delta ->
            scope.launch { height.snapTo((height.value - delta).coerceIn(collapsedPx, fullPx)) }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val h = height.value.toInt().coerceAtLeast(0)
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .clip(Mapas.shapes.sheet)
                .background(Mapas.colors.sheet)
                .testTag("sheet")
                .semantics {
                    contentDescription = sheetDescription
                    stateDescription = detentLabel(detent)
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            val next = SheetMath.settle(height.value, -velocity, collapsedPx, mediumPx, fullPx)
                            onDetentChange(next)
                            // If the detent did not change, LaunchedEffect will not run: settle explicitly.
                            scope.launch {
                                height.animateTo(
                                    SheetMath.height(next, collapsedPx, mediumPx, fullPx),
                                    spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                                )
                            }
                        },
                    )
                    .clickable(onClickLabel = handleDescription) { onDetentChange(detent.next()) }
                    .padding(vertical = 10.dp)
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


