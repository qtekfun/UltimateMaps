package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.nav.NavTrip
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
import com.qtekfun.ultimatemaps.core.traffic.TrafficEstimate
import com.qtekfun.ultimatemaps.core.traffic.TrafficOutcome
import com.qtekfun.ultimatemaps.core.traffic.TrafficProvider
import org.junit.After
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The time still to drive follows live traffic on a real car trip (a fake feed; nothing here waits on real time). */
class NavTrafficEtaTest {
    private val rigs = ArrayList<NavTestRig>()
    private val asked = AtomicInteger()

    /** Doubles whatever time is left: a jam on the rest of the route. */
    private val jam = object : TrafficProvider {
        override val enabled = true
        override suspend fun estimate(geometry: List<LatLon>, coreSeconds: Double, baseSpeedMps: ((Double) -> Double)?): TrafficOutcome {
            asked.incrementAndGet()
            return TrafficOutcome.Ready(TrafficEstimate(coreSeconds * 2, coreSeconds, 0.5, 0.0, 1.0))
        }
    }

    @After fun tearDown() = rigs.forEach { it.close() }

    private fun rig(traffic: TrafficProvider) = NavTestRig(traffic = traffic).also { rigs += it }

    @Test fun `a jam on the rest of the route pushes the arrival time and shows the delay`() {
        val h = rig(jam)
        assertTrue(h.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = false))
        h.emitReal(pt(0.0, 40.0), seconds = 3)
        val ui = h.await("traffic known") { it.trafficDelaySeconds > 0.0 }
        assertEquals(2.0, ui.trafficFactor, 1e-9)
        val remaining = ui.nav!!.remainingSeconds
        assertEquals(h.clock.get() + remaining * 2000, ui.etaMillis.toDouble(), 5_000.0 + remaining * 1000)
    }

    @Test fun `a walking trip or a simulation never asks for traffic`() {
        val h = rig(jam)
        assertTrue(h.screen.begin(cityPlan(), emptyList(), NavTrip(profile = RoutingProfile.FOOT), simulate = false))
        h.emitReal(pt(0.0, 40.0), seconds = 3)
        h.await("on route") { (it.nav?.traveledMeters ?: 0.0) > 20 }
        h.stopAndSettle()
        val sim = rig(jam)
        assertTrue(sim.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = true, speedKmh = 50))
        sim.await("arrived") { it.phase == NavPhase.ARRIVED }
        assertEquals(0, asked.get())
    }

    @Test fun `without a feed the arrival time is the router's and nothing changes`() {
        val h = rig(com.qtekfun.ultimatemaps.core.traffic.NoTrafficProvider)
        h.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = false)
        h.emitReal(pt(0.0, 40.0), seconds = 3)
        val ui = h.await("on route") { (it.nav?.traveledMeters ?: 0.0) > 20 }
        assertEquals(1.0, ui.trafficFactor)
        assertEquals(0.0, ui.trafficDelaySeconds)
    }

    private fun NavTestRig.stopAndSettle() { screen.stop(); settle() }
}

/** The faster-route card: found with traffic, accepted or declined by the driver, never imposed. */
class NavFasterRouteSuggestionTest {
    private val rigs = ArrayList<NavTestRig>()

    // 8 km straight north, a point every 40 m, just a start and an arrival.
    private fun longPlan(eastMeters: Double = 0.0, seconds: Double = 600.0): com.qtekfun.ultimatemaps.core.routing.RoutePlan {
        val pts = (0..200).map { pt(eastMeters, it * 40.0) }
        val g = com.qtekfun.ultimatemaps.core.routing.RouteGuidance(
            listOf(
                com.qtekfun.ultimatemaps.core.routing.Maneuver(0, com.qtekfun.ultimatemaps.core.routing.TurnType.DEPART),
                com.qtekfun.ultimatemaps.core.routing.Maneuver(pts.lastIndex, com.qtekfun.ultimatemaps.core.routing.TurnType.ARRIVE),
            ),
        )
        return com.qtekfun.ultimatemaps.core.routing.RoutePlan(pts, 8_000.0 + eastMeters / 100, seconds, g)
    }

    private val eastRoute = longPlan(eastMeters = 2_000.0, seconds = 650.0)

    /** The road you are on has a 15 minute jam; the one 2 km east is clear. */
    private val feed = object : TrafficProvider {
        override val enabled = true
        override suspend fun estimate(geometry: List<LatLon>, coreSeconds: Double, baseSpeedMps: ((Double) -> Double)?): TrafficOutcome {
            val east = geometry.any { it.lon > pt(1_000.0, 0.0).lon }
            val delay = if (east) 0.0 else 900.0
            return TrafficOutcome.Ready(TrafficEstimate(coreSeconds + delay, delay, if (east) 1.0 else 0.3, 0.0, 1.0))
        }
    }

    private val provider = com.qtekfun.ultimatemaps.core.nav.RouteProvider { _, _, _, _, _ -> eastRoute }

    @After fun tearDown() = rigs.forEach { it.close() }

    private fun rig(routes: com.qtekfun.ultimatemaps.core.nav.RouteProvider? = provider) =
        NavTestRig(traffic = feed, suggestions = routes).also { rigs += it }

    private fun start(h: NavTestRig) {
        assertTrue(h.screen.begin(longPlan(), emptyList(), NavTrip(), simulate = false))
        h.emitReal(pt(0.0, 40.0), seconds = 3)
    }

    @Test fun `a jam with a clear road beside it offers the faster route and accepting switches to it`() {
        val h = rig()
        start(h)
        val ui = h.await("suggestion") { it.suggestion != null }
        assertTrue(ui.suggestion!!.savedSeconds >= 300.0, "${ui.suggestion!!.savedSeconds}")
        assertEquals(eastRoute, ui.suggestion!!.plan)
        h.screen.acceptSuggestion()
        h.await("route replaced") { it.suggestion == null && (it.nav?.routeRevision ?: 0) >= 1 }
        assertEquals(eastRoute.geometry, h.controller.route.value!!.geometry)
    }

    @Test fun `declining keeps the route and clears the card`() {
        val h = rig()
        start(h)
        h.await("suggestion") { it.suggestion != null }
        h.screen.dismissSuggestion()
        assertEquals(null, h.screen.ui.value.suggestion)
        assertEquals(0, h.screen.ui.value.nav!!.routeRevision)
    }

    @Test fun `without a provider for the alternatives there is no card`() {
        val h = rig(routes = null)
        start(h)
        h.await("delay known") { it.trafficDelaySeconds > 0.0 }
        assertEquals(null, h.screen.ui.value.suggestion)
    }
}
