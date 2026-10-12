package com.qtekfun.ultimatemaps.ui.fold

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.qtekfun.ultimatemaps.ui.LandscapeTestSupport
import kotlin.math.roundToInt
import kotlin.test.assertTrue

/**
 * Fake folds for the layout tests. Windows are drawn at 420 dpi (density 2.625, what the Pixel Fold and the Galaxy Z Fold
 * report), the sizes are the ones those devices report in dp, rounded.
 */
object FoldTestSupport {
    const val DENSITY = 2.625f

    /** Window sizes in dp (width, height). Values come from the manufacturers' pixel sizes at 420 dpi; see docs/ui-foldables.md. */
    const val PIXEL_FOLD_INNER_W = 841
    const val PIXEL_FOLD_INNER_H = 701
    const val PIXEL_FOLD_COVER_W = 411
    const val PIXEL_FOLD_COVER_H = 797
    const val Z_FOLD_INNER_W = 829
    const val Z_FOLD_INNER_H = 690
    const val Z_FOLD_COVER_W = 344
    const val Z_FOLD_COVER_H = 882

    private fun px(dp: Float) = (dp * DENSITY).roundToInt()

    /** A crease at [xDp] (window dp) spanning the whole [heightDp]; [widthDp] > 0 is a hinge that hides part of the screen. */
    fun vertical(xDp: Float, heightDp: Int, widthDp: Float = 0f, halfOpened: Boolean = true, separating: Boolean = true, occluding: Boolean = false) =
        FoldFeatureInfo(px(xDp), 0, px(xDp + widthDp), px(heightDp.toFloat()), vertical = true, halfOpened = halfOpened, separating = separating, occluding = occluding)

    fun horizontal(yDp: Float, widthDp: Int, heightDp: Float = 0f, halfOpened: Boolean = true, separating: Boolean = true, occluding: Boolean = false) =
        FoldFeatureInfo(0, px(yDp), px(widthDp.toFloat()), px(yDp + heightDp), vertical = false, halfOpened = halfOpened, separating = separating, occluding = occluding)

    /** Whether [r] straddles or covers the crease (a zero-width crease is a line: [r] must not have both sides of it). */
    fun crosses(r: Rect, h: FoldFeatureInfo): Boolean =
        if (h.vertical) r.right > h.left + 1f && r.left < h.right - 1f else r.bottom > h.top + 1f && r.top < h.bottom - 1f

    fun assertNoneCrosses(rule: ComposeContentTestRule, tags: List<String>, hinge: FoldFeatureInfo) {
        for (tag in tags) {
            val r = LandscapeTestSupport.bounds(rule, tag)
            assertTrue(!crosses(r, hinge), "$tag $r crosses the hinge $hinge")
        }
    }

    /** [tag] lies entirely at the start side of a vertical crease (the book's column pane). */
    fun assertBeforeVertical(rule: ComposeContentTestRule, tag: String, hinge: FoldFeatureInfo) {
        val r = LandscapeTestSupport.bounds(rule, tag)
        assertTrue(r.right <= hinge.left + 1f, "$tag $r is not in the pane before the hinge ${hinge.left}")
    }

    fun assertAfterVertical(rule: ComposeContentTestRule, tag: String, hinge: FoldFeatureInfo) {
        val r = LandscapeTestSupport.bounds(rule, tag)
        assertTrue(r.left >= hinge.right - 1f, "$tag $r is not in the pane after the hinge ${hinge.right}")
    }

    fun assertAboveHorizontal(rule: ComposeContentTestRule, tag: String, hinge: FoldFeatureInfo) {
        val r = LandscapeTestSupport.bounds(rule, tag)
        assertTrue(r.bottom <= hinge.top + 1f, "$tag $r is not in the top pane (hinge top ${hinge.top})")
    }

    fun assertBelowHorizontal(rule: ComposeContentTestRule, tag: String, hinge: FoldFeatureInfo) {
        val r = LandscapeTestSupport.bounds(rule, tag)
        assertTrue(r.top >= hinge.bottom - 1f, "$tag $r is not in the bottom pane (hinge bottom ${hinge.bottom})")
    }
}
