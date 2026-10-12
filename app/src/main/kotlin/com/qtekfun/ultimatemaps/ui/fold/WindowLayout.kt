package com.qtekfun.ultimatemaps.ui.fold

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min as dpMin
import com.qtekfun.ultimatemaps.core.map.CameraPadding
import com.qtekfun.ultimatemaps.ui.LandscapeLayout
import com.qtekfun.ultimatemaps.ui.startSafeInsets
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How the map screen, the search sheet and the navigation panels are arranged in the window. */
enum class Posture {
    /** Narrow window (a phone upright, a cover display, a narrow split-screen window): bottom sheet and full-width banners. */
    PORTRAIT,

    /** A column at the start side and the map beside it: a wide window, or a large one (unfolded inner display, tablet). */
    SIDE_COLUMN,

    /** Half-open with a vertical crease (or a dual-screen hinge): the column on one side of it, the map on the other. */
    BOOK,

    /** Half-open with a horizontal crease: the map on the top half, panel and controls on the bottom half. */
    TABLETOP,
}

/**
 * The arrangement for one window size and fold, all in dp and independent of Compose, so it is tested on the JVM.
 * One rule set decides it for every screen (see `docs/ui-foldables.md`); the screens only read these numbers.
 */
@Immutable
class WindowLayout(
    val posture: Posture,
    val width: Dp,
    val height: Dp,
    val rtl: Boolean,
    /** Width of the side column (start safe inset not included); in [Posture.TABLETOP] the widest the bottom pane content gets. */
    val panelWidth: Dp,
    /** From the start edge: where overlays drawn over the map may begin (0 when no side column). */
    val reserve: Dp,
    /** From the start edge: how much of the map the side column covers (inset and column; the crease side in a book). */
    val coveredStart: Dp,
    /** [Posture.TABLETOP]: the top pane ends here (the crease starts); otherwise the window height. */
    val topPaneHeight: Dp,
    /** [Posture.TABLETOP]: the bottom pane begins here (the crease ends); otherwise the window height. */
    val bottomPaneTop: Dp,
    /** [Posture.BOOK]: width of the pane at the start side (up to the crease); otherwise the window width. */
    val startPaneWidth: Dp,
) {
    val sideColumn: Boolean get() = posture == Posture.SIDE_COLUMN || posture == Posture.BOOK
    val tabletop: Boolean get() = posture == Posture.TABLETOP

    /** Height of the bottom pane in [Posture.TABLETOP]; unconstrained otherwise. */
    val bottomPaneHeight: Dp get() = if (tabletop) height - bottomPaneTop else Dp.Unspecified

    /** Widest a centred card (arrival summary, resume) gets; in a book it also has to fit the start pane. */
    fun cardMaxWidth(max: Dp): Dp = if (posture == Posture.BOOK) dpMin(max, startPaneWidth) else max

    /** Pixels the panels cover of the map, for the camera: the start column and the bottom pane. */
    fun coveredPadding(density: Float): CameraPadding {
        val start = (coveredStart.value * density).roundToInt().coerceAtLeast(0)
        val bottom = if (tabletop) ((height - topPaneHeight).value * density).roundToInt().coerceAtLeast(0) else 0
        return if (rtl) CameraPadding(right = start, bottom = bottom) else CameraPadding(left = start, bottom = bottom)
    }

    companion object {
        /** From this size on (both sides) a window is a large display: an unfolded inner screen or a tablet. */
        val LARGE_MIN_SIDE: Dp = 600.dp

        /** The side column on a large window: 46 % of the width, between these two (a wider column is hard to read). */
        val LARGE_PANEL_MIN: Dp = 360.dp
        val LARGE_PANEL_MAX: Dp = 440.dp

        /** A split-screen window never gets a column narrower than this (or the window itself). */
        val MIN_PANEL: Dp = 240.dp

        /** Widest the column gets beside a vertical crease. */
        val BOOK_PANEL_MAX: Dp = 480.dp

        /** Widest the bottom pane content gets in tabletop. */
        val TABLETOP_CONTENT_MAX: Dp = 720.dp

        /** A pane smaller than this is not worth splitting for: the window falls back to the plain rules. */
        val MIN_START_PANE: Dp = 260.dp
        val MIN_END_PANE: Dp = 200.dp
        val MIN_TOP_PANE: Dp = 180.dp
        val MIN_BOTTOM_PANE: Dp = 200.dp

        /** The portrait arrangement of a window [width] by [height]. */
        private fun portrait(width: Dp, height: Dp, rtl: Boolean) =
            WindowLayout(Posture.PORTRAIT, width, height, rtl, width, 0.dp, 0.dp, height, height, width)

        /** Width of the plain side column in a window [width] wide and [height] high. */
        fun sideColumnWidth(width: Dp, height: Dp): Dp =
            if (min(width.value, height.value) >= LARGE_MIN_SIDE.value) {
                (width * LandscapeLayout.MAX_PANEL_SHARE).coerceIn(LARGE_PANEL_MIN, LARGE_PANEL_MAX)
            } else {
                max(LandscapeLayout.panelWidth(width).value, min(MIN_PANEL.value, width.value)).dp
            }

        /**
         * The layout for a window [width] x [height] (dp), with [startInset] of system bars or cutout at the start side, and
         * the [fold] (window pixels) at [density]. Pure.
         */
        fun compute(width: Dp, height: Dp, startInset: Dp, fold: FoldFeatureInfo?, density: Float, rtl: Boolean): WindowLayout {
            if (fold != null && fold.splits && density > 0f) {
                val l = fold.left / density
                val t = fold.top / density
                val r = fold.right / density
                val b = fold.bottom / density
                if (fold.vertical && r > 0f && l < width.value) {
                    // Distances from the start edge to the near and far side of the crease.
                    val near = if (rtl) width.value - r else l
                    val far = if (rtl) width.value - l else r
                    if (near >= MIN_START_PANE.value && width.value - far >= MIN_END_PANE.value) {
                        val panel = min(BOOK_PANEL_MAX.value, near - startInset.value).coerceAtLeast(0f)
                        return WindowLayout(
                            Posture.BOOK, width, height, rtl, panel.dp, far.dp, far.dp, height, height, near.dp,
                        )
                    }
                } else if (!fold.vertical && b > 0f && t < height.value) {
                    if (t >= MIN_TOP_PANE.value && height.value - b >= MIN_BOTTOM_PANE.value) {
                        return WindowLayout(
                            Posture.TABLETOP, width, height, rtl, min(TABLETOP_CONTENT_MAX.value, width.value).dp, 0.dp, 0.dp,
                            t.dp, b.dp, width,
                        )
                    }
                }
            }
            val large = min(width.value, height.value) >= LARGE_MIN_SIDE.value
            if (width > height || large) {
                val panel = sideColumnWidth(width, height)
                return WindowLayout(
                    Posture.SIDE_COLUMN, width, height, rtl, panel, startInset + panel + LandscapeLayout.GAP, startInset + panel,
                    height, height, width,
                )
            }
            return portrait(width, height, rtl)
        }
    }
}

/**
 * The [WindowLayout] of the window of this composition ([width] x [height] are the constraints of the screen, which fills the
 * window), from the fold the activity provides in [LocalFoldFeature]. Recomputed only when one of the inputs changes.
 */
@Composable
fun rememberWindowLayout(width: Dp, height: Dp): WindowLayout {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val fold = LocalFoldFeature.current
    val insets: WindowInsets = startSafeInsets()
    val startInsetPx = if (rtl) insets.getRight(density, LayoutDirection.Rtl) else insets.getLeft(density, LayoutDirection.Ltr)
    return remember(width, height, startInsetPx, fold, density.density, rtl) {
        WindowLayout.compute(width, height, (startInsetPx / density.density).dp, fold, density.density, rtl)
    }
}
