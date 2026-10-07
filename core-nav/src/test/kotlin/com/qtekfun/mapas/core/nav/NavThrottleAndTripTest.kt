package com.qtekfun.mapas.core.nav

import com.qtekfun.mapas.core.routing.BikeCycleways
import com.qtekfun.mapas.core.routing.RouteOptions
import com.qtekfun.mapas.core.routing.RoutingProfile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavNotificationThrottleTest {
    @Test fun throttlesByGapButLetsNewManeuversThrough() {
        val t = NavNotificationThrottle()
        assertTrue(t.shouldPost(0, 5_000, "a"))
        assertFalse(t.shouldPost(1_000, 5_000, "a")) // same key, under the gap (battery saver)
        assertTrue(t.shouldPost(1_500, 5_000, "b")) // new maneuver after 1 s
        assertFalse(t.shouldPost(1_800, 5_000, "c")) // changes again too soon
        assertTrue(t.shouldPost(7_000, 5_000, "c")) // gap passed
        assertTrue(t.shouldPost(7_100, 5_000, "c", force = true))
        assertTrue(t.shouldPost(100, 5_000, "c")) // clock went back: do not freeze the notification
    }
}

class NavTripPersistenceTest {
    @Test fun theTripOptionsSurviveTheFile() {
        val f = File.createTempFile("trip", ".bin").also { it.delete(); it.deleteOnExit() }
        val store = NavStateStore(f, { 1L })
        val trip = NavTrip(RoutingProfile.BIKE, RouteOptions(avoidTolls = true, avoidUnpaved = true))
        store.save(lPlan(), 3.0, trip)
        assertEquals(trip, store.load()!!.trip)
    }

    @Test fun theBikeCycleLevelSurvivesTheFile() {
        val f = File.createTempFile("trip", ".bin").also { it.delete(); it.deleteOnExit() }
        val store = NavStateStore(f, { 1L })
        for (level in BikeCycleways.entries) {
            val trip = NavTrip(RoutingProfile.BIKE, RouteOptions(avoidFerries = true, bikeCycleways = level))
            store.save(lPlan(), 3.0, trip)
            assertEquals(trip, store.load()!!.trip, level.name)
        }
    }
}
