package com.qtekfun.ultimatemaps.core.cameras

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.map.SimulatedLocationSource
import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the warner with a [SimulatedLocationSource] along straight roads; time is the fix counter (one fix per second,
 * never the real clock), so the tests are deterministic.
 */
class AlertWarnerTest {
    private val perMeter = 1.0 / TargetGrid.METERS_PER_DEGREE
    private val lon0 = -3.7
    private val lat0 = 40.0

    private val allOn = CameraSettings(
        fixedEnabled = true, mobileZonesEnabled = true, incidentsEnabled = true, v16Enabled = true, acknowledged = true,
    )

    private fun camera(id: String, metersNorth: Double, axis: Int? = 0, sense: AxisSense = AxisSense.BOTH, limit: Int? = null, category: AlertCategory = AlertCategory.FIXED_CAMERA) =
        AlertTarget(id, id, category, lat0 + metersNorth * perMeter, lon0, axis, sense, 50, limit)

    private class Harness(settings: () -> CameraSettings, targets: List<AlertTarget>) {
        val alerts = ArrayList<Pair<Int, AlertEvent>>() // second -> event
        var second = 0
        val source = SimulatedLocationSource()
        val warner = AlertWarner(listOf(TargetGrid(targets)), settings) { alerts += second to it }
        val feed = FreeDrivingFeed(source, warner) { second * 1000L }
    }

    /** Drives north along the road at [kmh] from [fromMeters] to [toMeters] (north of lat0), one fix per second. */
    private fun drive(h: Harness, kmh: Double, fromMeters: Double, toMeters: Double, bearing: Float? = 0f, heading: Double = 0.0) {
        var m = fromMeters
        val v = kmh / 3.6
        val dir = if (heading == 180.0) -1 else 1
        while ((dir > 0 && m <= toMeters) || (dir < 0 && m >= toMeters)) {
            h.source.emit(LocationFix(LatLon(lat0 + m * perMeter, lon0), bearingDegrees = bearing, speedMps = v.toFloat(), timeMillis = h.second * 1000L))
            h.second++
            m += dir * v
        }
    }

    @Test fun approachingAtDifferentSpeedsWarnsOnceAtASpeedDependentDistance() {
        for ((kmh, lookahead) in listOf(50.0 to 417.0, 90.0 to 750.0, 120.0 to 1000.0)) {
            val h = Harness({ allOn }, listOf(camera("c", 6000.0)))
            h.feed.start()
            drive(h, kmh, 3000.0, 6200.0)
            assertEquals(1, h.alerts.size, "one warning at $kmh km/h")
            val e = h.alerts.single().second
            assertEquals(AlertStage.FAR, e.stage)
            assertTrue(e.distanceMeters <= lookahead + 1, "within the look-ahead of $lookahead m at $kmh km/h, was ${e.distanceMeters}")
            assertTrue(e.distanceMeters > lookahead - kmh / 3.6 - 1, "and not earlier than one fix before it, was ${e.distanceMeters}")
        }
    }

    @Test fun aCameraIsAnnouncedAgainOnTheNextApproach() {
        val h = Harness({ allOn }, listOf(camera("c", 2000.0)))
        h.feed.start()
        drive(h, 90.0, 1000.0, 2400.0)
        assertEquals(1, h.alerts.size)
        drive(h, 90.0, 2400.0, 3500.0) // far beyond: forgotten
        drive(h, 90.0, 3500.0, 1000.0, heading = 180.0, bearing = 180f) // drives back: the other direction (axis both)
        assertEquals(2, h.alerts.size, "returning along the road is a new approach")
    }

    @Test fun theOppositeDirectionOfAOneWayCameraIsIgnoredAndTheRightOneIsNot() {
        val targets = listOf(camera("c", 2000.0, axis = 0, sense = AxisSense.ALONG))
        val north = Harness({ allOn }, targets).also { it.feed.start() }
        drive(north, 90.0, 1000.0, 2300.0)
        assertEquals(1, north.alerts.size, "northbound traffic is the one checked")
        val south = Harness({ allOn }, targets).also { it.feed.start() }
        drive(south, 90.0, 3000.0, 1700.0, bearing = 180f, heading = 180.0)
        assertTrue(south.alerts.isEmpty(), "southbound traffic is not checked by this camera")
    }

    @Test fun aCrossingRoadAndABackwardsCameraAreIgnored() {
        val h = Harness({ allOn }, listOf(camera("c", 2000.0, axis = 90))) // camera watches an east-west road
        h.feed.start()
        drive(h, 90.0, 1000.0, 2300.0) // we drive north, towards it, on the crossing road
        assertTrue(h.alerts.isEmpty())
        val h2 = Harness({ allOn }, listOf(camera("c", 500.0, axis = null)))
        h2.feed.start()
        drive(h2, 90.0, 1000.0, 3000.0) // camera is behind us
        assertTrue(h2.alerts.isEmpty())
    }

    @Test fun nothingWhenEverythingIsOffAndTheSourceIsNotNeededThen() {
        val h = Harness({ CameraSettings() }, listOf(camera("c", 2000.0)))
        h.feed.start()
        drive(h, 90.0, 1000.0, 2300.0)
        assertTrue(h.alerts.isEmpty(), "off by default: silent")
    }

    @Test fun eachCategoryHasItsOwnSwitch() {
        val targets = listOf(
            camera("f", 2000.0, category = AlertCategory.FIXED_CAMERA),
            camera("z", 4000.0, category = AlertCategory.MOBILE_ZONE),
            camera("v", 6000.0, category = AlertCategory.V16),
            camera("a", 8000.0, category = AlertCategory.ACCIDENT),
        )
        fun categoriesFor(s: CameraSettings): Set<AlertCategory> {
            val h = Harness({ s }, targets).also { it.feed.start() }
            drive(h, 90.0, 1000.0, 8300.0)
            return h.alerts.map { it.second.target.category }.toSet()
        }
        assertEquals(setOf(AlertCategory.FIXED_CAMERA), categoriesFor(CameraSettings(fixedEnabled = true, acknowledged = true)))
        assertEquals(setOf(AlertCategory.MOBILE_ZONE), categoriesFor(CameraSettings(mobileZonesEnabled = true, acknowledged = true)))
        assertEquals(setOf(AlertCategory.V16), categoriesFor(CameraSettings(v16Enabled = true)))
        assertEquals(setOf(AlertCategory.ACCIDENT), categoriesFor(CameraSettings(incidentsEnabled = true)))
        assertEquals(4, categoriesFor(allOn).size)
    }

    @Test fun slowOrStoppedAndWithoutADirectionThereAreNoWarnings() {
        val h = Harness({ allOn }, listOf(camera("c", 1100.0, axis = null)))
        h.feed.start()
        drive(h, 5.0, 1000.0, 1050.0) // walking pace
        assertTrue(h.alerts.isEmpty())
        // No bearing in the fix and no earlier movement to derive one from: a single fix says nothing.
        h.source.emit(LocationFix(LatLon(lat0 + 1000 * perMeter, lon0), speedMps = 25f))
        assertTrue(h.alerts.isEmpty())
    }

    @Test fun theHeadingIsDerivedFromMovementWhenTheFixHasNone() {
        val h = Harness({ allOn }, listOf(camera("c", 2000.0, axis = null)))
        h.feed.start()
        drive(h, 90.0, 1000.0, 2300.0, bearing = null)
        assertEquals(1, h.alerts.size)
    }

    @Test fun knownLimitWarnsNearOnlyWhenSpeedingAndHonoursOnlyIfSpeeding() {
        val limit = camera("c", 3000.0, limit = 90)
        val fast = Harness({ allOn }, listOf(limit)).also { it.feed.start() }
        drive(fast, 110.0, 1500.0, 3100.0)
        assertEquals(listOf(AlertStage.FAR, AlertStage.NEAR), fast.alerts.map { it.second.stage })
        assertTrue(fast.alerts.all { it.second.limitKmh == 90 })
        assertTrue(fast.alerts.last().second.speeding)
        assertTrue(fast.alerts.last().second.distanceMeters <= AlertWarner.NEAR_METERS)

        val slow = Harness({ allOn }, listOf(limit)).also { it.feed.start() }
        drive(slow, 80.0, 1500.0, 3100.0)
        assertEquals(listOf(AlertStage.FAR), slow.alerts.map { it.second.stage }, "no NEAR warning when not speeding")

        val quiet = Harness({ allOn.copy(warnOnlyIfSpeeding = true) }, listOf(limit)).also { it.feed.start() }
        drive(quiet, 80.0, 1500.0, 3100.0)
        assertTrue(quiet.alerts.isEmpty(), "only-if-speeding: under the known limit nothing is said")
        val loud = Harness({ allOn.copy(warnOnlyIfSpeeding = true) }, listOf(limit)).also { it.feed.start() }
        drive(loud, 110.0, 1500.0, 3100.0)
        assertFalse(loud.alerts.isEmpty())
        val unknown = Harness({ allOn.copy(warnOnlyIfSpeeding = true) }, listOf(camera("u", 3000.0, limit = null))).also { it.feed.start() }
        drive(unknown, 80.0, 1500.0, 3100.0)
        assertEquals(1, unknown.alerts.size, "a camera with unknown limit always warns")
    }

    @Test fun twoEndsOfTheSameGroupAreOneAnnouncement() {
        val a = AlertTarget("za", "z", AlertCategory.MOBILE_ZONE, lat0 + 2000 * perMeter, lon0, 0, AxisSense.ALONG, 50, null)
        val b = AlertTarget("zb", "z", AlertCategory.MOBILE_ZONE, lat0 + 2200 * perMeter, lon0, 0, AxisSense.ALONG, 50, null)
        val h = Harness({ allOn }, listOf(a, b)).also { it.feed.start() }
        drive(h, 90.0, 1000.0, 2500.0)
        assertEquals(1, h.alerts.size)
    }

    @Test fun announcementsAreSpacedAndTheNearestComesFirst() {
        val h = Harness({ allOn }, listOf(camera("far", 2600.0), camera("near", 2300.0))).also { it.feed.start() }
        drive(h, 90.0, 1500.0, 2800.0)
        assertEquals(listOf("near", "far"), h.alerts.map { it.second.target.id })
        assertTrue(h.alerts[1].first - h.alerts[0].first >= AlertWarner.MIN_GAP_MILLIS / 1000, "at least the minimum gap apart")
    }

    @Test fun aTargetAlreadyTooCloseIsPassedSilently() {
        val h = Harness({ allOn }, listOf(camera("c", 1030.0))).also { it.feed.start() }
        drive(h, 90.0, 1000.0, 1300.0)
        assertTrue(h.alerts.isEmpty())
    }

    @Test fun stoppingTheFeedStopsTheSource() {
        val h = Harness({ allOn }, emptyList())
        h.feed.start()
        assertTrue(h.source.isStarted)
        h.feed.stop()
        assertFalse(h.source.isStarted)
        assertFalse(h.feed.isRunning)
    }

    // ---- route-based ----

    private fun routeNorth(lengthMeters: Double = 6000.0) =
        RouteGeometry(listOf(LatLon(lat0, lon0), LatLon(lat0 + lengthMeters * perMeter, lon0)))

    @Test fun routeBasedWarnsForACameraOnTheRouteWithTheAlongRouteDistance() {
        val alerts = ArrayList<AlertEvent>()
        val w = AlertWarner(listOf(TargetGrid(listOf(camera("c", 3000.0)))), { allOn }) { alerts += it }
        val g = routeNorth()
        var along = 1500.0
        var t = 0L
        while (along < 3200) {
            w.onRouteFix(g, along, lat0 + along * perMeter, lon0, 25f, t)
            along += 25.0; t += 1000
        }
        assertEquals(1, alerts.size)
        assertTrue(alerts[0].distanceMeters in 700..750, "30 s x 25 m/s = 750 m, was ${alerts[0].distanceMeters}")
    }

    @Test fun routeBasedIgnoresACameraOnAParallelRoadAndOneBehind() {
        val alerts = ArrayList<AlertEvent>()
        val parallel = AlertTarget("p", "p", AlertCategory.FIXED_CAMERA, lat0 + 3000 * perMeter, lon0 + 150.0 * perMeter, 0, AxisSense.BOTH, 50, null)
        val behind = camera("b", 1000.0)
        val w = AlertWarner(listOf(TargetGrid(listOf(parallel, behind))), { allOn }) { alerts += it }
        val g = routeNorth()
        var along = 1500.0
        var t = 0L
        while (along < 3300) {
            w.onRouteFix(g, along, lat0 + along * perMeter, lon0, 25f, t)
            along += 25.0; t += 1000
        }
        assertTrue(alerts.isEmpty(), "150 m off the route is another road; behind is passed")
    }

    @Test fun routeBasedHonoursTheCameraDirection() {
        val alerts = ArrayList<AlertEvent>()
        val againstOnly = camera("c", 3000.0, axis = 0, sense = AxisSense.AGAINST)
        val w = AlertWarner(listOf(TargetGrid(listOf(againstOnly))), { allOn }) { alerts += it }
        val g = routeNorth()
        var along = 1500.0
        var t = 0L
        while (along < 3200) {
            w.onRouteFix(g, along, lat0 + along * perMeter, lon0, 25f, t)
            along += 25.0; t += 1000
        }
        assertTrue(alerts.isEmpty(), "this camera checks southbound traffic only")
    }

    @Test fun anAlertCarriesTheTargetForTheVoice() {
        val h = Harness({ allOn }, listOf(camera("c", 2000.0, limit = 70))).also { it.feed.start() }
        drive(h, 90.0, 1000.0, 2100.0)
        val e = h.alerts.first().second
        assertNotNull(e.speedKmh)
        assertEquals(70, e.limitKmh)
        assertEquals(AlertCategory.FIXED_CAMERA, e.target.category)
    }
}
