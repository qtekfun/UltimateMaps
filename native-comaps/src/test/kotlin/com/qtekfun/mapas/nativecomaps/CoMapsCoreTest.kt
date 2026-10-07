package com.qtekfun.mapas.nativecomaps

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.routing.RouteOptions
import com.qtekfun.mapas.core.routing.RouteRequest
import com.qtekfun.mapas.core.routing.RoutingProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FakeBridge : NativeBridge {
    var initError = ""
    var lastRoute: Triple<Int, List<Double>, Int>? = null
    var routeReply = doubleArrayOf(0.0, 1500.0, 120.0, 40.0, -3.0, 40.1, -3.1)
    var searchReply = arrayOf("Cafe Central", "Calle Mayor 1", "cafe", "40.4168", "-3.7038")

    override fun init(apk: String, writableDir: String, tmpDir: String, locale: String) = initError
    override fun refreshMaps() = 3
    override fun search(
        query: String, hasPos: Boolean, lat: Double, lon: Double, limit: Int, timeoutMs: Int, locale: String,
    ) = searchReply

    override fun route(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int): DoubleArray {
        lastRoute = Triple(profile, points.toList(), avoidFlags)
        return routeReply
    }

    var guidedReply = RawGuidedRoute(routeReply, DoubleArray(0), emptyArray())
    var guidedCalls = 0

    override fun routeGuidance(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int): RawGuidedRoute {
        guidedCalls++
        return guidedReply
    }
}

class CoMapsCoreTest {
    private val a = LatLon(40.0, -3.0)
    private val b = LatLon(40.1, -3.1)

    private fun core(f: FakeBridge = FakeBridge()) = CoMapsCore(f).also { it.init("a.apk", "/maps", "/tmp") }

    @Test fun `init failure is reported`() {
        val f = FakeBridge().apply { initError = "sin classificator" }
        assertFailsWith<IllegalStateException> { CoMapsCore(f).init("a.apk", "/maps", "/tmp") }
    }

    @Test fun `use before init fails`() {
        assertFailsWith<IllegalStateException> { CoMapsCore(FakeBridge()).refreshMaps() }
    }

    @Test fun `search decodes results`() {
        val r = core().searchEngine().search("cafe", a)
        assertEquals(1, r.size)
        assertEquals("Cafe Central", r[0].name)
        assertEquals("cafe", r[0].category)
        assertEquals(LatLon(40.4168, -3.7038), r[0].point)
    }

    @Test fun `blank query never reaches the engine`() {
        assertTrue(core().searchEngine().search("  ").isEmpty())
    }

    @Test fun `route sends via points in order and maps profile and avoid flags`() {
        val f = FakeBridge()
        val via = LatLon(40.05, -3.05)
        val plan = core(f).routingEngine().route(
            RouteRequest(a, b, listOf(via), RoutingProfile.BIKE, RouteOptions(avoidMotorways = true, avoidUnpaved = true)),
        )
        assertEquals(Triple(2, listOf(40.0, -3.0, 40.05, -3.05, 40.1, -3.1), 1 or 8), f.lastRoute)
        assertEquals(1500.0, plan!!.distanceMeters)
        assertEquals(2, plan.geometry.size)
    }

    @Test fun `all four avoid options map to distinct bits`() {
        assertEquals(15, RouteOptions(true, true, true, true).toFlags())
        assertEquals(0, RouteOptions().toFlags())
    }

    @Test fun `route not found returns null plan with code`() {
        val f = FakeBridge().apply { routeReply = doubleArrayOf(8.0, 0.0, 0.0) }
        val c = core(f)
        assertNull(c.routingEngine().route(RouteRequest(a, b)))
        assertEquals(RouteCode.ROUTE_NOT_FOUND, c.routingEngine().routeDetailed(RouteRequest(a, b)).code)
    }

    @Test fun `malformed replies are rejected`() {
        assertFailsWith<IllegalArgumentException> { decodeSearch(arrayOf("x")) }
        assertFailsWith<IllegalArgumentException> { decodeRoute(doubleArrayOf(0.0, 1.0)) }
    }
}
