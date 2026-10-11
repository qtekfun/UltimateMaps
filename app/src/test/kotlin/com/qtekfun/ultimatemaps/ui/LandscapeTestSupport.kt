package com.qtekfun.ultimatemaps.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import kotlin.test.assertTrue

/** Bounds checks for the landscape layout tests: the screens are drawn at 800 x 360 dp and no key node may overlap another. */
object LandscapeTestSupport {
    /** Bounds in the root, in px. */
    fun bounds(rule: ComposeContentTestRule, tag: String): Rect =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    fun rootBounds(rule: ComposeContentTestRule): Rect = rule.onRoot().fetchSemanticsNode().boundsInRoot

    fun overlaps(a: Rect, b: Rect): Boolean {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return w > 1f && h > 1f
    }

    /** Fails naming the two tags when any pair of the nodes overlaps, or when one leaves the window. */
    fun assertDisjointAndInside(rule: ComposeContentTestRule, tags: List<String>) {
        val root = rootBounds(rule)
        val all = tags.associateWith { bounds(rule, it) }
        for ((tag, r) in all) {
            assertTrue(
                r.left >= root.left - 1f && r.top >= root.top - 1f && r.right <= root.right + 1f && r.bottom <= root.bottom + 1f,
                "$tag $r leaves the window $root",
            )
        }
        for (i in tags.indices) for (j in i + 1 until tags.size) {
            assertTrue(!overlaps(all.getValue(tags[i]), all.getValue(tags[j])), "${tags[i]} ${all[tags[i]]} overlaps ${tags[j]} ${all[tags[j]]}")
        }
    }
}
