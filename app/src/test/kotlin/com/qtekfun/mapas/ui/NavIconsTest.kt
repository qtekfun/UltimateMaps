package com.qtekfun.mapas.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import com.qtekfun.mapas.core.routing.LaneDirection
import com.qtekfun.mapas.core.routing.TurnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NavIconsTest {
    private fun signature(node: VectorNode): String = when (node) {
        is VectorGroup -> "G(${node.scaleX},${node.pivotX})[" + node.joinToString(",") { signature(it) } + "]"
        is VectorPath -> "P(" + node.pathData.joinToString("") { it.toString() } + "|" + (node.fill != null) + ")"
        else -> node.toString()
    }

    private fun sig(v: ImageVector) = signature(v.root)

    @Test fun everyTurnTypeHasItsOwnGlyph() {
        val sigs = TurnType.entries.associateWith { sig(NavIcons.turn(it)) }
        for ((t, s) in sigs) assertTrue(s.contains("P("), "${t.name} draws something")
        assertEquals(TurnType.entries.size, sigs.values.toSet().size, "no two turn types share a glyph")
    }

    @Test fun everyLaneDirectionHasItsOwnGlyph() {
        val sigs = LaneDirection.entries.associateWith { sig(NavIcons.lane(it)) }
        for ((d, s) in sigs) assertTrue(s.contains("P("), "${d.name} draws something")
        assertEquals(LaneDirection.entries.size, sigs.values.toSet().size, "no two lane directions share a glyph")
    }

    @Test fun leftGlyphsAreTheRightOnesMirrored() {
        val pairs = listOf(
            TurnType.LEFT to TurnType.RIGHT, TurnType.SLIGHT_LEFT to TurnType.SLIGHT_RIGHT, TurnType.SHARP_LEFT to TurnType.SHARP_RIGHT,
            TurnType.U_TURN_LEFT to TurnType.U_TURN_RIGHT, TurnType.EXIT_LEFT to TurnType.EXIT_RIGHT, TurnType.ARRIVE_LEFT to TurnType.ARRIVE_RIGHT,
        )
        for ((l, r) in pairs) {
            val left = NavIcons.turn(l).root.first() as VectorGroup
            assertEquals(-1f, left.scaleX, l.name)
            assertEquals(24f, left.pivotX, l.name)
            assertEquals(left.joinToString(",") { signature(it) }, NavIcons.turn(r).root.joinToString(",") { signature(it) }, "${l.name} mirrors ${r.name}")
            assertNotEquals(sig(NavIcons.turn(l)), sig(NavIcons.turn(r)))
        }
    }

    @Test fun glyphsAreBuiltOnceAndOnA48Grid() {
        assertSame(NavIcons.turn(TurnType.RIGHT), NavIcons.turn(TurnType.RIGHT))
        assertSame(NavIcons.lane(LaneDirection.THROUGH), NavIcons.lane(LaneDirection.THROUGH))
        val v = NavIcons.turn(TurnType.STRAIGHT)
        assertEquals(48f, v.viewportWidth)
        assertEquals(48f, v.viewportHeight)
    }
}
