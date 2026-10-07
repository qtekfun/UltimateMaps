package com.qtekfun.ultimatemaps.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.core.routing.LaneDirection
import com.qtekfun.ultimatemaps.core.routing.TurnType

/**
 * The app's own turn and lane glyphs, drawn from scratch as vector paths on a 48 x 48 grid (own work, same licence
 * as the app; nothing copied from any icon set). Strokes only (4.5 wide, round caps and joins) plus a few small
 * filled dots, in one colour that the caller tints. Left variants are the right ones mirrored around x = 24, so a
 * left and a right glyph are always exact opposites. Built once per kind and cached.
 */
object NavIcons {
    private class Piece(val d: String, val filled: Boolean = false)

    private class Spec(val mirror: Boolean, val pieces: List<Piece>)

    private fun p(d: String) = Piece(d)
    private fun dot(cx: Int, cy: Int, r: Int) = Piece("M$cx ${cy}m-$r 0a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0-${2 * r} 0Z", filled = true)
    private fun ring(cx: Int, cy: Int, r: Int) = Piece("M$cx ${cy}m-$r 0a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0-${2 * r} 0Z")

    private val straight = listOf(p("M24 42V10"), p("M14 20L24 10L34 20"))
    private val slightRight = listOf(p("M16 42V32Q16 24 24 18L33 11"), p("M24 11H33V20"))
    private val right = listOf(p("M16 42V28Q16 18 26 18H38"), p("M31 11L38 18L31 25"))
    private val sharpRight = listOf(p("M12 42V18L36 34"), p("M26 34.2L36 34L32.4 24.7"))
    private val uTurnRight = listOf(p("M14 42V20A10 10 0 0 1 34 20V34"), p("M27 27L34 34L41 27"))
    private val roundaboutRing = ring(24, 24, 9)
    private val roundaboutEnter = listOf(roundaboutRing, p("M24 44V33"), p("M24 15V5"), p("M18 11L24 5L30 11"))
    private val roundaboutLeave = listOf(roundaboutRing, p("M24 44V33"), p("M31 17L39 9"), p("M31 9H39V17"))
    private val exitRight = listOf(p("M16 42V8"), p("M16 28Q16 20 26 16L37 12"), p("M28 12H37V21"))
    private val merge = listOf(p("M14 42Q14 30 24 24V8"), p("M34 42Q34 30 24 24"), p("M16 16L24 8L32 16"))
    private val depart = listOf(p("M24 38V10"), p("M14 20L24 10L34 20"), dot(24, 43, 3))
    private val arrive = listOf(p("M24 44Q10 30 10 20A14 14 0 0 1 38 20Q38 30 24 44Z"), ring(24, 20, 5))
    private val arriveRight = listOf(p("M20 42Q8 30 8 21A12 12 0 0 1 32 21Q32 30 20 42Z"), ring(20, 21, 4), p("M33 38H43"), p("M38 33L43 38L38 43"))

    private fun turnSpec(type: TurnType): Spec = when (type) {
        TurnType.DEPART -> Spec(false, depart)
        TurnType.STRAIGHT -> Spec(false, straight)
        TurnType.SLIGHT_RIGHT -> Spec(false, slightRight)
        TurnType.RIGHT -> Spec(false, right)
        TurnType.SHARP_RIGHT -> Spec(false, sharpRight)
        TurnType.SLIGHT_LEFT -> Spec(true, slightRight)
        TurnType.LEFT -> Spec(true, right)
        TurnType.SHARP_LEFT -> Spec(true, sharpRight)
        TurnType.U_TURN_RIGHT -> Spec(false, uTurnRight)
        TurnType.U_TURN_LEFT -> Spec(true, uTurnRight)
        TurnType.ROUNDABOUT_ENTER -> Spec(false, roundaboutEnter)
        TurnType.ROUNDABOUT_LEAVE -> Spec(false, roundaboutLeave)
        TurnType.EXIT_RIGHT -> Spec(false, exitRight)
        TurnType.EXIT_LEFT -> Spec(true, exitRight)
        TurnType.MERGE -> Spec(false, merge)
        TurnType.ARRIVE -> Spec(false, arrive)
        TurnType.ARRIVE_RIGHT -> Spec(false, arriveRight)
        TurnType.ARRIVE_LEFT -> Spec(true, arriveRight)
    }

    // Lane arrows share one stem (x = 24) so that several directions of one lane overlay into the usual fork.
    private val laneThrough = straight
    private val laneRight = listOf(p("M24 42V28Q24 18 34 18H41"), p("M34 11L41 18L34 25"))
    private val laneSlightRight = listOf(p("M24 42V32Q24 24 30 18L37 11"), p("M28 11H37V20"))
    private val laneSharpRight = listOf(p("M20 42V22L38 36"), p("M28 35.5L38 36L35 26.8"))
    private val laneMergeRight = listOf(p("M16 42V34Q16 26 30 22V10"), p("M22 18L30 10L38 18"))
    private val laneUTurn = listOf(p("M32 42V20A8 8 0 0 0 16 20V30"), p("M10 24L16 31L22 24"))

    private fun laneSpec(d: LaneDirection): Spec = when (d) {
        LaneDirection.THROUGH -> Spec(false, laneThrough)
        LaneDirection.RIGHT -> Spec(false, laneRight)
        LaneDirection.LEFT -> Spec(true, laneRight)
        LaneDirection.SLIGHT_RIGHT -> Spec(false, laneSlightRight)
        LaneDirection.SLIGHT_LEFT -> Spec(true, laneSlightRight)
        LaneDirection.SHARP_RIGHT -> Spec(false, laneSharpRight)
        LaneDirection.SHARP_LEFT -> Spec(true, laneSharpRight)
        LaneDirection.MERGE_RIGHT -> Spec(false, laneMergeRight)
        LaneDirection.MERGE_LEFT -> Spec(true, laneMergeRight)
        LaneDirection.U_TURN -> Spec(false, laneUTurn)
    }

    private val turnCache = HashMap<TurnType, ImageVector>()
    private val laneCache = HashMap<LaneDirection, ImageVector>()

    /** The glyph for [type]; drawn in black, tint it with a colour filter. */
    @Synchronized
    fun turn(type: TurnType): ImageVector = turnCache.getOrPut(type) { build("turn_${type.name.lowercase()}", turnSpec(type)) }

    /** The glyph for one lane [direction]; drawn in black, tint it with a colour filter. */
    @Synchronized
    fun lane(direction: LaneDirection): ImageVector = laneCache.getOrPut(direction) { build("lane_${direction.name.lowercase()}", laneSpec(direction)) }

    private fun build(name: String, spec: Spec): ImageVector {
        val b = ImageVector.Builder(name, 48.dp, 48.dp, 48f, 48f)
        if (spec.mirror) b.addGroup(scaleX = -1f, pivotX = 24f)
        for (piece in spec.pieces) {
            val nodes = PathParser().parsePathString(piece.d).toNodes()
            b.addPath(
                pathData = nodes,
                fill = if (piece.filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        if (spec.mirror) b.clearGroup()
        return b.build()
    }

    private const val STROKE = 4.5f
}
