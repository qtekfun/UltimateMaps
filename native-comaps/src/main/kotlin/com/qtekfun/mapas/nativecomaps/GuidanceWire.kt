package com.qtekfun.mapas.nativecomaps

import com.qtekfun.mapas.core.routing.Lane
import com.qtekfun.mapas.core.routing.LaneDirection
import com.qtekfun.mapas.core.routing.Maneuver
import com.qtekfun.mapas.core.routing.RouteGuidance
import com.qtekfun.mapas.core.routing.SpeedLimit
import com.qtekfun.mapas.core.routing.TurnType

/**
 * Decoder of the guidance returned by `um::Core::Route(withGuidance = true)` (see `um_core.hpp`).
 * Everything is integers inside a `DoubleArray`:
 * `[version, nManeuvers, nLimits, maneuvers..., limits...]`
 * - maneuver: `geometryIndex, turn, roundaboutExit(-1), nameIndex(-1), nLanes, (laneWayMask, recommended)*`
 * - limit: `from, to, kmh(-1 = no data)`
 * An empty array means "no guidance". Any inconsistency throws [IllegalArgumentException] (it never reads out of bounds).
 */
internal object GuidanceWire {
    const val VERSION = 1
    private const val MAX_LANES = 16

    fun decode(raw: DoubleArray, names: Array<String>, geometrySize: Int): RouteGuidance {
        if (raw.isEmpty()) return RouteGuidance.EMPTY
        val r = Reader(raw)
        val version = r.int("version")
        require(version == VERSION) { "unknown guidance version: $version" }
        val nMan = r.count("nManeuvers", raw.size)
        val nLim = r.count("nLimits", raw.size)

        val maneuvers = ArrayList<Maneuver>(nMan)
        repeat(nMan) {
            val index = r.int("geometryIndex")
            require(index in 0 until geometrySize) { "geometry index out of range: $index (points: $geometrySize)" }
            val type = turnOf(r.int("giro"))
            val exit = r.int("roundaboutExit")
            require(exit >= -1) { "invalid roundabout exit: $exit" }
            val nameIdx = r.int("nameIndex")
            require(nameIdx in -1 until names.size) { "name index out of range: $nameIdx (names: ${names.size})" }
            val nLanes = r.count("nLanes", MAX_LANES)
            val lanes = List(nLanes) {
                val mask = r.int("mascara")
                val rec = r.int("recommended")
                require(rec == 0 || rec == 1) { "invalid recommended lane flag: $rec" }
                Lane(directionsOf(mask), rec == 1)
            }
            val isRoundabout = type == TurnType.ROUNDABOUT_ENTER || type == TurnType.ROUNDABOUT_LEAVE
            maneuvers += Maneuver(
                geometryIndex = index,
                type = type,
                streetName = if (nameIdx >= 0) names[nameIdx].ifBlank { null } else null,
                roundaboutExit = if (exit >= 0 && isRoundabout) exit else null,
                lanes = lanes,
            )
        }

        val limits = ArrayList<SpeedLimit>(nLim)
        repeat(nLim) {
            val start = r.int("from")
            val end = r.int("to")
            val kmh = r.int("kmh")
            require(start in 0 until geometrySize && end in start until geometrySize) {
                "invalid limit stretch: $start..$end (points: $geometrySize)"
            }
            require(kmh == -1 || kmh in 1..400) { "invalid speed limit: $kmh" }
            limits += SpeedLimit(start, end, if (kmh == -1) null else kmh)
        }
        require(r.exhausted()) { "guidance with leftover data: ${raw.size - r.pos}" }
        return RouteGuidance(maneuvers, limits)
    }

    private class Reader(private val a: DoubleArray) {
        var pos = 0
            private set

        fun exhausted() = pos == a.size

        fun int(what: String): Int {
            require(pos < a.size) { "guidance truncated while reading $what (position $pos of ${a.size})" }
            val v = a[pos++]
            require(v == Math.rint(v) && v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) { "$what is not an integer: $v" }
            return v.toInt()
        }

        fun count(what: String, max: Int): Int {
            val n = int(what)
            require(n in 0..max) { "$what out of range: $n" }
            return n
        }
    }

    /** Codes of `um::WireTurn`. Explicit on purpose: they do not depend on the order of the Kotlin enum. */
    private fun turnOf(code: Int): TurnType = when (code) {
        0 -> TurnType.DEPART
        1 -> TurnType.STRAIGHT
        2 -> TurnType.SLIGHT_RIGHT
        3 -> TurnType.RIGHT
        4 -> TurnType.SHARP_RIGHT
        5 -> TurnType.SLIGHT_LEFT
        6 -> TurnType.LEFT
        7 -> TurnType.SHARP_LEFT
        8 -> TurnType.U_TURN_LEFT
        9 -> TurnType.U_TURN_RIGHT
        10 -> TurnType.ROUNDABOUT_ENTER
        11 -> TurnType.ROUNDABOUT_LEAVE
        12 -> TurnType.EXIT_LEFT
        13 -> TurnType.EXIT_RIGHT
        14 -> TurnType.MERGE
        15 -> TurnType.ARRIVE
        16 -> TurnType.ARRIVE_LEFT
        17 -> TurnType.ARRIVE_RIGHT
        else -> throw IllegalArgumentException("unknown turn: $code")
    }

    /** Bit i of the mask = `routing::turns::lanes::LaneWay` of value i. Bit 0 (`None`) contributes no direction. */
    private val laneBits = mapOf(
        1 to LaneDirection.U_TURN, // ReverseLeft
        2 to LaneDirection.SHARP_LEFT,
        3 to LaneDirection.LEFT,
        4 to LaneDirection.MERGE_LEFT,
        5 to LaneDirection.SLIGHT_LEFT,
        6 to LaneDirection.THROUGH,
        7 to LaneDirection.SLIGHT_RIGHT,
        8 to LaneDirection.MERGE_RIGHT,
        9 to LaneDirection.RIGHT,
        10 to LaneDirection.SHARP_RIGHT,
        11 to LaneDirection.U_TURN, // ReverseRight
    )

    private fun directionsOf(mask: Int): Set<LaneDirection> {
        require(mask >= 0 && mask < (1 shl 12)) { "invalid lane mask: $mask" }
        return laneBits.filterKeys { mask and (1 shl it) != 0 }.values.toSet()
    }
}
