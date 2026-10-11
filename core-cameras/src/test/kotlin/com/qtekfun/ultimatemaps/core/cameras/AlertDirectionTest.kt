package com.qtekfun.ultimatemaps.core.cameras

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which cameras apply to the driver's direction of travel: same direction, the other carriageway 30 m away, a
 * crossing road, unknown direction, "both", the 0/360 wrap and a roundabout. Routes are simulated polylines driven at
 * one fix per virtual second (no clock, no threads).
 */
class AlertDirectionTest {
    private val lat0 = 40.0
    private val lon0 = -3.7
    private val mLat = 1.0 / TargetGrid.METERS_PER_DEGREE
    private val mLon = 1.0 / (TargetGrid.METERS_PER_DEGREE * cos(Math.toRadians(lat0)))
    private val on = CameraSettings(fixedEnabled = true, acknowledged = true)

    /** A point [east] and [north] metres from the origin. */
    private fun pt(east: Double, north: Double) = LatLon(lat0 + north * mLat, lon0 + east * mLon)

    private fun cam(east: Double, north: Double, axis: Int?, sense: AxisSense) =
        AlertTarget("c", "c", AlertCategory.FIXED_CAMERA, lat0 + north * mLat, lon0 + east * mLon, axis, sense, 50, null)

    /** Drives the whole [route] at 25 m/s (90 km/h) and returns how many alerts were raised. */
    private fun drive(route: List<LatLon>, target: AlertTarget, accuracy: Float? = null): Int {
        val g = RouteGeometry(route)
        var n = 0
        val w = AlertWarner(listOf(TargetGrid(listOf(target))), { on }) { n++ }
        var along = 0.0
        var t = 0L
        while (along < g.totalMeters) {
            val p = g.pointAt(along)
            w.onRouteFix(g, along, p.lat, p.lon, 25f, t * 1000L, accuracy)
            along += 25.0
            t++
        }
        return n
    }

    private val north = listOf(pt(0.0, 0.0), pt(0.0, 3000.0))
    private val south = listOf(pt(0.0, 3000.0), pt(0.0, 0.0))

    @Test fun cameraOfTheSameDirectionAlerts() {
        assertEquals(1, drive(north, cam(0.0, 1500.0, 0, AxisSense.ALONG)))
        assertEquals(1, drive(south, cam(0.0, 1500.0, 0, AxisSense.AGAINST)))
    }

    @Test fun cameraOfTheOppositeSenseOnTheSameRoadDoesNotAlert() {
        assertEquals(0, drive(north, cam(0.0, 1500.0, 0, AxisSense.AGAINST)))
        assertEquals(0, drive(south, cam(0.0, 1500.0, 0, AxisSense.ALONG)))
    }

    @Test fun oppositeCarriagewayThirtyMetresAwayIsIgnoredWhateverItsSense() {
        // Northbound driver in right-hand traffic: the other carriageway is on the left (west), 30 m away.
        assertEquals(0, drive(north, cam(-30.0, 1500.0, 0, AxisSense.AGAINST)), "known opposite sense")
        assertEquals(0, drive(north, cam(-30.0, 1500.0, 0, AxisSense.BOTH)), "unknown direction (axis only), offset decides")
        assertEquals(0, drive(north, cam(-30.0, 1500.0, null, AxisSense.BOTH)), "no axis at all, offset decides")
        // And the mirror: the southbound driver sees the same camera on the right, 30 m away (beyond 28 m).
        assertEquals(0, drive(south, cam(-30.0, 1500.0, null, AxisSense.BOTH)))
    }

    @Test fun cameraOnTheDriversOwnCarriagewayAlertsEvenWithUnknownDirection() {
        assertEquals(1, drive(north, cam(4.0, 1500.0, null, AxisSense.BOTH)))
        assertEquals(1, drive(north, cam(-8.0, 1500.0, 0, AxisSense.BOTH)))
        assertEquals(1, drive(north, cam(20.0, 1500.0, null, AxisSense.BOTH)), "right side: up to 28 m")
    }

    @Test fun leftSideIsTighterThanRightForUnknownDirection() {
        assertEquals(0, drive(north, cam(-22.0, 1500.0, null, AxisSense.BOTH)))
        assertEquals(1, drive(north, cam(22.0, 1500.0, null, AxisSense.BOTH)))
    }

    @Test fun gpsAccuracyWidensTheAllowanceByAPartOfIt() {
        assertEquals(0, drive(north, cam(-22.0, 1500.0, null, AxisSense.BOTH), accuracy = null))
        assertEquals(1, drive(north, cam(-22.0, 1500.0, null, AxisSense.BOTH), accuracy = 10f))
        assertEquals(0, drive(north, cam(-30.0, 1500.0, null, AxisSense.BOTH), accuracy = 100f), "slack is capped")
    }

    @Test fun knownDirectionKeepsTheWideAllowanceForDataPositionError() {
        assertEquals(1, drive(north, cam(-30.0, 1500.0, 0, AxisSense.ALONG)))
        assertEquals(0, drive(north, cam(-50.0, 1500.0, 0, AxisSense.ALONG)))
    }

    @Test fun crossingRoadDoesNotAlertEvenWhenOnTheRoute() {
        assertEquals(0, drive(north, cam(0.0, 1500.0, 90, AxisSense.ALONG)))
        assertEquals(0, drive(north, cam(0.0, 1500.0, 90, AxisSense.BOTH)), "an east-west road only")
        assertEquals(0, drive(north, cam(0.0, 1500.0, 90, AxisSense.AGAINST)))
    }

    @Test fun bothDirectionsAlwaysApplyOnTheAxis() {
        assertEquals(1, drive(north, cam(0.0, 1500.0, 0, AxisSense.BOTH)))
        assertEquals(1, drive(south, cam(0.0, 1500.0, 0, AxisSense.BOTH)))
        assertEquals(1, drive(north, cam(0.0, 1500.0, 180, AxisSense.BOTH)), "an axis is a line, 0 and 180 are the same road")
    }

    @Test fun unknownDirectionAlertsOnlyNearTheRouteLine() {
        assertEquals(1, drive(north, cam(0.0, 1500.0, null, AxisSense.BOTH)))
        assertEquals(1, drive(south, cam(0.0, 1500.0, null, AxisSense.BOTH)))
        assertEquals(0, drive(north, cam(70.0, 1500.0, null, AxisSense.BOTH)), "a parallel road 70 m away")
    }

    @Test fun bearingWrapAroundNorth() {
        // Route bearing about 355 degrees (slightly west of north), axis 5 and 350.
        val route = listOf(pt(0.0, 0.0), pt(-260.0, 3000.0))
        assertEquals(1, drive(route, cam(-130.0, 1500.0, 5, AxisSense.ALONG)))
        assertEquals(1, drive(route, cam(-130.0, 1500.0, 350, AxisSense.ALONG)))
        assertEquals(0, drive(route, cam(-130.0, 1500.0, 5, AxisSense.AGAINST)))
        assertEquals(1, drive(route, cam(-130.0, 1500.0, 175, AxisSense.AGAINST)), "against 175 is travelling at 355")
        assertEquals(1, drive(route, cam(-130.0, 1500.0, 358, AxisSense.ALONG)))
    }

    @Test fun roundaboutOnTheRoute() {
        val r = 30.0
        val route = ArrayList<LatLon>()
        route += pt(0.0, 0.0)
        // Clockwise from the west point of the circle (centre 30 m east of the entry road) to its north point.
        for (i in 0..8) {
            val a = Math.toRadians(180.0 - i * 90.0 / 8)
            route += pt(r + r * cos(a), 1000.0 + r * sin(a))
        }
        route += pt(300.0, 1000.0 + r)
        val entry = 700.0
        assertEquals(1, drive(route, cam(0.0, entry, 0, AxisSense.ALONG)), "entry road, travelling north")
        assertEquals(0, drive(route, cam(0.0, entry, 0, AxisSense.AGAINST)))
        assertEquals(1, drive(route, cam(250.0, 1000.0 + r, 90, AxisSense.ALONG)), "exit road, travelling east")
        assertEquals(0, drive(route, cam(250.0, 1000.0 + r, 90, AxisSense.AGAINST)))
        assertEquals(0, drive(route, cam(250.0, 1000.0 + r, 0, AxisSense.ALONG)), "a north-south road meeting the exit")
    }

    @Test fun freeDrivingHeadingDecidesAndSideRoadIsIgnored() {
        fun free(heading: Float, c: AlertTarget, acc: Float? = null): Int {
            var n = 0
            val w = AlertWarner(listOf(TargetGrid(listOf(c))), { on }) { n++ }
            // Drive towards the camera's latitude along the camera's own line, 25 m/s.
            val start = if (heading == 0f) 800.0 else 2200.0
            val step = if (heading == 0f) 25.0 else -25.0
            var m = start
            var t = 0L
            while (m in 700.0..2300.0) {
                val p = pt(0.0, m)
                w.onFreeFix(p.lat, p.lon, heading, 25f, t * 1000L, acc)
                m += step
                t++
            }
            return n
        }
        assertEquals(1, free(0f, cam(0.0, 1500.0, 0, AxisSense.ALONG)))
        assertEquals(0, free(0f, cam(0.0, 1500.0, 0, AxisSense.AGAINST)))
        assertEquals(1, free(180f, cam(0.0, 1500.0, 0, AxisSense.AGAINST)))
        assertEquals(0, free(0f, cam(-30.0, 1500.0, null, AxisSense.BOTH)), "other carriageway, unknown direction")
        assertEquals(0, free(0f, cam(0.0, 1500.0, 90, AxisSense.BOTH)), "crossing road")
        assertEquals(1, free(0f, cam(3.0, 1500.0, null, AxisSense.BOTH)))
        assertTrue(free(0f, cam(200.0, 1000.0, null, AxisSense.BOTH)) == 0, "a camera 200 m to the side is not ahead")
    }
}
