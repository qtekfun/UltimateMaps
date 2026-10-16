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
