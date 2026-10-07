package com.qtekfun.mapas.nav

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.nav.AddStopResult
import com.qtekfun.mapas.core.nav.NavTrip
import com.qtekfun.mapas.core.nav.RouteProvider
import com.qtekfun.mapas.core.routing.RouteGuidance
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.TurnType
import com.qtekfun.mapas.core.routing.Maneuver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Add stop" mid-trip through the screen model: the trip, the service and the sinks must not restart. */
class NavScreenAddStopTest {
    private val rigs = ArrayList<NavTestRig>()

    private fun rig(routes: RouteProvider?) = NavTestRig(routes = routes).also { rigs += it }

    @After fun tearDown() = rigs.forEach { it.close() }

    /** Drives straight from the origin through every via to the destination (20 samples per leg). */
    private fun straight(calls: AtomicInteger? = null) = RouteProvider { from, _, via, destination, _ ->
        calls?.incrementAndGet()
        val points = arrayListOf(from)
        var prev = from
        for (p in via + destination) {
            for (i in 1..20) points += LatLon(prev.lat + (p.lat - prev.lat) * i / 20, prev.lon + (p.lon - prev.lon) * i / 20)
            prev = p
        }
        RoutePlan(points, 3000.0, 300.0, RouteGuidance(listOf(Maneuver(0, TurnType.DEPART), Maneuver(points.size - 1, TurnType.ARRIVE))))
    }

    @Test fun `adding a stop to a real trip re-plans it without restarting the service or the sinks`() {
        val calls = AtomicInteger()
        val h = rig(straight(calls))
        assertTrue(h.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = false))
        h.emitReal(pt(0.0, 100.0), seconds = 3)
        h.await("riding") { it.phase == NavPhase.ON_ROUTE && (it.nav?.traveledMeters ?: 0.0) > 50 }

        val result = runBlocking { h.screen.addStop(pt(250.0, 1000.0)) }
        assertEquals(AddStopResult.ADDED, result)
        assertEquals(1, calls.get())
        val ui = h.await("new route") { (it.nav?.routeRevision ?: 0) == 1 }
        assertEquals(1, ui.nav!!.stopsRemaining)
        assertTrue(ui.active)
        assertEquals(1, h.service.starts.get(), "the service keeps running, it is not started again")
        assertEquals(0, h.service.stops.get())
        assertEquals(listOf("started:false"), h.sink.log, "no new start and no end for the sinks (voice, camera)")
        assertEquals(1, h.controller.route.value!!.guidance.stops.size)
        assertTrue(h.store.load() != null, "the trip stays resumable")
    }

    @Test fun `a failed addition keeps the old trip and says no route`() {
        val h = rig { _, _, _, _, _ -> null }
        h.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = false)
        h.emitReal(pt(0.0, 100.0), seconds = 2)
        h.await("riding") { (it.nav?.traveledMeters ?: 0.0) > 50 }
        val before = h.controller.route.value
        assertEquals(AddStopResult.NO_ROUTE, runBlocking { h.screen.addStop(pt(250.0, 1000.0)) })
        h.settle()
        assertEquals(before, h.controller.route.value)
        assertEquals(0, h.screen.ui.value.nav!!.routeRevision)
        assertTrue(h.screen.ui.value.active)
    }

    @Test fun `there is nothing to add to when no trip is running or after the arrival summary`() {
        val h = rig(straight())
        assertEquals(AddStopResult.NOT_NAVIGATING, runBlocking { h.screen.addStop(pt(0.0, 100.0)) })
        h.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = true)
        h.await("arrived") { it.phase == NavPhase.ARRIVED }
        assertEquals(AddStopResult.NOT_NAVIGATING, runBlocking { h.screen.addStop(pt(0.0, 100.0)) })
    }

    @Test fun `a simulated trip goes on over the new route, reaches the added stop and arrives`() {
        val h = rig(straight())
        h.gate = CompletableDeferred()
        h.gateWhen = { (it.nav?.traveledMeters ?: 0.0) > 300.0 }
        assertTrue(h.screen.begin(cityPlan(), emptyList(), NavTrip(), simulate = true))
        h.awaitParked()
        assertTrue((h.screen.ui.value.nav?.traveledMeters ?: 0.0) > 300.0)

        h.gateWhen = { false }
        assertEquals(AddStopResult.ADDED, runBlocking { h.screen.addStop(pt(250.0, 1000.0)) })
        val end = h.await("arrived") { it.phase == NavPhase.ARRIVED }
        assertEquals(1, end.summary!!.stopsReached, "the added stop was reached on the way")
        assertFalse(h.real.isStarted)
        assertEquals(listOf("started:true", "ended:true"), h.sink.log)
    }
}
