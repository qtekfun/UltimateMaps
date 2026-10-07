package com.qtekfun.ultimatemaps.nativecomaps

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.routing.Lane
import com.qtekfun.ultimatemaps.core.routing.LaneDirection
import com.qtekfun.ultimatemaps.core.routing.Maneuver
import com.qtekfun.ultimatemaps.core.routing.RouteGuidance
import com.qtekfun.ultimatemaps.core.routing.RouteRequest
import com.qtekfun.ultimatemaps.core.routing.SpeedLimit
import com.qtekfun.ultimatemaps.core.routing.TurnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hand-built arrays in the format of `um_core.hpp`; there is no native core on the JVM. */
class GuidanceWireTest {
    private fun d(vararg v: Number) = DoubleArray(v.size) { v[it].toDouble() }
    private fun decode(raw: DoubleArray, names: Array<String> = emptyArray(), points: Int = 10) =
        GuidanceWire.decode(raw, names, points)

    @Test fun `empty array means no guidance`() {
        assertEquals(RouteGuidance.EMPTY, decode(DoubleArray(0)))
    }

    @Test fun `no turns and no limits`() {
        assertEquals(RouteGuidance.EMPTY, decode(d(1, 0, 0)))
    }

    @Test fun `simple turn with street name`() {
        // maneuver: index 4, turn LEFT(6), no exit, name 0, no lanes
        val g = decode(d(1, 1, 0, 4, 6, -1, 0, 0), arrayOf("Calle Mayor"))
        assertEquals(listOf(Maneuver(4, TurnType.LEFT, "Calle Mayor")), g.maneuvers)
        assertTrue(g.speedLimits.isEmpty())
    }

    @Test fun `turn without street name`() {
        val g = decode(d(1, 1, 0, 9, 15, -1, -1, 0))
        assertEquals(Maneuver(9, TurnType.ARRIVE), g.maneuvers.single())
        assertNull(g.maneuvers.single().streetName)
    }

    @Test fun `roundabout enter and leave carry the exit number`() {
        val g = decode(
            d(1, 2, 0, /* enter */ 2, 10, 3, -1, 0, /* leave */ 3, 11, 3, 0, 0),
            arrayOf("Avenida de Europa"),
        )
        assertEquals(TurnType.ROUNDABOUT_ENTER, g.maneuvers[0].type)
        assertEquals(3, g.maneuvers[0].roundaboutExit)
        assertEquals(3, g.maneuvers[1].roundaboutExit)
        assertEquals("Avenida de Europa", g.maneuvers[1].streetName)
    }

    @Test fun `exit number on a non roundabout turn is ignored`() {
        assertNull(decode(d(1, 1, 0, 2, 3, 2, -1, 0)).maneuvers.single().roundaboutExit)
    }

    @Test fun `lanes keep order, directions and recommended flag`() {
        // 3 lanes: [Left] not rec, [Through|Right] rec, [Right] rec. Mask = sum of 1 << LaneWay
        val left = 1 shl 3
        val throughRight = (1 shl 6) or (1 shl 9)
        val right = 1 shl 9
        val g = decode(d(1, 1, 0, 5, 3, -1, -1, 3, left, 0, throughRight, 1, right, 1))
        assertEquals(
            listOf(
                Lane(setOf(LaneDirection.LEFT), false),
                Lane(setOf(LaneDirection.THROUGH, LaneDirection.RIGHT), true),
                Lane(setOf(LaneDirection.RIGHT), true),
            ),
            g.maneuvers.single().lanes,
        )
    }

    @Test fun `unrestricted lane has no directions and both reverse ways map to u turn`() {
        val g = decode(d(1, 1, 0, 5, 1, -1, -1, 2, 1, 0, (1 shl 1) or (1 shl 11), 0))
        assertEquals(emptySet(), g.maneuvers.single().lanes[0].directions)
        assertEquals(setOf(LaneDirection.U_TURN), g.maneuvers.single().lanes[1].directions)
    }

    @Test fun `speed limits include null runs`() {
        val g = decode(d(1, 0, 3, 0, 4, 50, 4, 6, -1, 6, 9, 90))
        assertEquals(listOf(SpeedLimit(0, 4, 50), SpeedLimit(4, 6, null), SpeedLimit(6, 9, 90)), g.speedLimits)
    }

    @Test fun `every wire turn code maps and unknown ones are rejected`() {
        val types = (0..17).map { decode(d(1, 1, 0, 1, it, -1, -1, 0)).maneuvers.single().type }
        assertEquals(TurnType.entries.toSet(), types.toSet())
        assertFailsWith<IllegalArgumentException> { decode(d(1, 1, 0, 1, 18, -1, -1, 0)) }
    }

    @Test fun `malformed arrays are rejected`() {
        val bad = mapOf(
            "version" to d(2, 0, 0),
            "truncated header" to d(1, 0),
            "truncated maneuver" to d(1, 1, 0, 4, 6),
            "truncated lanes" to d(1, 1, 0, 4, 6, -1, -1, 2, 8, 1),
            "missing limits" to d(1, 0, 1, 0, 4),
            "trailing data" to d(1, 0, 0, 7),
            "negative count" to d(1, -1, 0),
            "huge count" to d(1, 1e9, 0),
            "too many lanes" to d(1, 1, 0, 4, 6, -1, -1, 99),
            "non integer" to d(1, 1, 0, 4.5, 6, -1, -1, 0),
            "NaN" to d(1, 1, 0, Double.NaN, 6, -1, -1, 0),
            "index out of geometry" to d(1, 1, 0, 10, 6, -1, -1, 0),
            "negative index" to d(1, 1, 0, -1, 6, -1, -1, 0),
            "name out of table" to d(1, 1, 0, 4, 6, -1, 0, 0),
            "bad recommended flag" to d(1, 1, 0, 4, 6, -1, -1, 1, 8, 2),
            "bad lane mask" to d(1, 1, 0, 4, 6, -1, -1, 1, 1 shl 12, 0),
            "limit end before start" to d(1, 0, 1, 5, 4, 50),
            "limit past geometry" to d(1, 0, 1, 0, 10, 50),
            "limit zero" to d(1, 0, 1, 0, 4, 0),
            "limit absurd" to d(1, 0, 1, 0, 4, 999),
        )
        for ((why, raw) in bad) {
            assertFailsWith<IllegalArgumentException>(why) { decode(raw) }
        }
    }

    // --- integration with the facade ---

    private val a = LatLon(40.0, -3.0)
    private val b = LatLon(40.1, -3.1)
    private val route = doubleArrayOf(0.0, 1500.0, 120.0, 40.0, -3.0, 40.05, -3.05, 40.1, -3.1)

    private fun core(f: FakeBridge) = CoMapsCore(f).also { it.init("a.apk", "/maps", "/tmp") }

    @Test fun `plain routing never asks for guidance and keeps an empty one`() {
        val f = FakeBridge()
        val plan = core(f).routingEngine().route(RouteRequest(a, b))!!
        assertEquals(0, f.guidedCalls)
        assertEquals(RouteGuidance.EMPTY, plan.guidance)
    }

    @Test fun `guided routing decodes maneuvers and limits`() {
        val f = FakeBridge()
        f.guidedReply = RawGuidedRoute(route, d(1, 1, 1, 1, 6, -1, 0, 0, 0, 2, 50), arrayOf("Calle Mayor"))
        val out = core(f).routingEngine(withGuidance = true).routeDetailed(RouteRequest(a, b))
        assertNull(out.guidanceError)
        val plan = assertNotNull(out.plan)
        assertEquals(1, f.guidedCalls)
        assertEquals(listOf(Maneuver(1, TurnType.LEFT, "Calle Mayor")), plan.guidance.maneuvers)
        assertEquals(listOf(SpeedLimit(0, 2, 50)), plan.guidance.speedLimits)
    }

    @Test fun `broken guidance keeps the route and reports the error`() {
        val f = FakeBridge()
        f.guidedReply = RawGuidedRoute(route, d(1, 1, 0, 99, 6, -1, -1, 0), emptyArray())
        val out = core(f).routingEngine(withGuidance = true).routeDetailed(RouteRequest(a, b))
        assertEquals(3, out.plan!!.geometry.size)
        assertEquals(RouteGuidance.EMPTY, out.plan.guidance)
        assertTrue(out.guidanceError!!.contains("out of range"))
    }

    @Test fun `guided route not found returns the code without a plan`() {
        val f = FakeBridge()
        f.guidedReply = RawGuidedRoute(doubleArrayOf(8.0, 0.0, 0.0), DoubleArray(0), emptyArray())
        val out = core(f).routingEngine(withGuidance = true).routeDetailed(RouteRequest(a, b))
        assertEquals(RouteCode.ROUTE_NOT_FOUND, out.code)
        assertNull(out.plan)
    }

    @Test fun `malformed route part of a guided reply is still rejected`() {
        assertFailsWith<IllegalArgumentException> { decodeGuidedRoute(RawGuidedRoute(d(0, 1), DoubleArray(0), emptyArray())) }
    }
}
