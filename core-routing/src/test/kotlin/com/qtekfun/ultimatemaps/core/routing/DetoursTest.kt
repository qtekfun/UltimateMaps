package com.qtekfun.ultimatemaps.core.routing

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetoursTest {
    // A 100 km route due north from (40, -3), a point every ~1 km.
    private val north = (0..100).map { LatLon(40.0 + it * 0.009, -3.0) }

    @Test fun theViaPointIsOnTheRequestedSideOfTheTravelDirection() {
        val left = assertNotNull(Detours.viaPoint(north, Detours.Side.LEFT))
        val right = assertNotNull(Detours.viaPoint(north, Detours.Side.RIGHT))
        assertTrue(left.lon < -3.0, "going north, left is west: ${left.lon}")
        assertTrue(right.lon > -3.0, "going north, right is east: ${right.lon}")
    }

    @Test fun theOffsetIsAFifthOfTheRouteAndNeverLessThanAKilometreOrMoreThanTwenty() {
        val mid = north[50]
        val d = mid.distanceTo(assertNotNull(Detours.viaPoint(north, Detours.Side.LEFT)))
        assertTrue(d in 19_000.0..21_000.0, "20 % of 100 km, capped at 20 km: $d")
        val short = (0..9).map { LatLon(40.0 + it * 0.0045, -3.0) } // about 4.5 km
        val ds = short[5].distanceTo(assertNotNull(Detours.viaPoint(short, Detours.Side.RIGHT)))
        assertTrue(ds in 900.0..1_300.0, "at least 1 km: $ds")
    }

    @Test fun aTinyRouteHasNoDetour() {
        assertNull(Detours.viaPoint(listOf(LatLon(40.0, -3.0), LatLon(40.005, -3.0)), Detours.Side.LEFT))
        assertNull(Detours.viaPoint(emptyList(), Detours.Side.LEFT))
    }

    @Test fun sharedFractionTellsTheSameRouteFromAParallelOne() {
        assertTrue(Detours.sharedFraction(north, north) > 0.99)
        val parallel = north.map { LatLon(it.lat, -3.2) } // about 17 km west
        assertTrue(Detours.sharedFraction(north, parallel) < 0.01)
        val half = north.take(51) + north.drop(51).map { LatLon(it.lat, -3.2) }
        val f = Detours.sharedFraction(north, half)
        assertTrue(f in 0.45..0.58, "about half of it is shared: $f")
    }
}
