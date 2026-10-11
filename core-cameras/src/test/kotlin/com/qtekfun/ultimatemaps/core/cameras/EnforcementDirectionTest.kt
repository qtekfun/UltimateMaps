package com.qtekfun.ultimatemaps.core.cameras

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The experimental "use the enforced direction from OpenStreetMap" switch. A camera whose data carries a direction
 * derived from an enforcement relation alerts only for that direction when the switch is on (with the wide lateral
 * allowance of a known direction); with it off, such a camera behaves exactly as it did without the relation data.
 * Routes are simulated polylines driven at one fix per virtual second (no clock, no threads).
 */
class EnforcementDirectionTest {
    private val lat0 = 40.0
    private val lon0 = -3.7
    private val mLat = 1.0 / TargetGrid.METERS_PER_DEGREE
    private val mLon = 1.0 / (TargetGrid.METERS_PER_DEGREE * cos(Math.toRadians(lat0)))
    private val base = CameraSettings(fixedEnabled = true, acknowledged = true)
    private val enabled = base.copy(useEnforcementDirection = true)

    private fun pt(east: Double, north: Double) = LatLon(lat0 + north * mLat, lon0 + east * mLon)

    /** A fixed camera as the data file reader would produce it. */
    private fun cam(
        east: Double, north: Double, axis: Int?, sense: AxisSense,
        relation: Boolean = false, axisOnly: Boolean = false,
    ): SpeedCamera = SpeedCamera(
        "c", CameraKind.FIXED, pt(east, north), null, "", null, axis, sense, CameraSources.OSM,
        directionFromRelation = relation, axisFromRelationOnly = axisOnly,
    )

    private fun targetsOf(c: SpeedCamera) = CameraTargets.of(CameraDataset(0L, CameraSources.OSM, listOf(c), emptyList(), emptyList()), base)

    private fun drive(route: List<LatLon>, c: SpeedCamera, settings: () -> CameraSettings): Int {
        val g = RouteGeometry(route)
        var n = 0
        val w = AlertWarner(listOf(TargetGrid(targetsOf(c))), settings) { n++ }
        var along = 0.0
        var t = 0L
        while (along < g.totalMeters) {
            val p = g.pointAt(along)
            w.onRouteFix(g, along, p.lat, p.lon, 25f, t * 1000L)
            along += 25.0
            t++
        }
        return n
    }

    private val north = listOf(pt(0.0, 0.0), pt(0.0, 3000.0))
    private val south = listOf(pt(0.0, 3000.0), pt(0.0, 0.0))
    private val east = listOf(pt(-1500.0, 1500.0), pt(1500.0, 1500.0))

    @Test fun switchIsOffByDefault() {
        assertFalse(CameraSettings().useEnforcementDirection)
    }

    @Test fun relationDirectionIsUsedOnlyWhenTheSwitchIsOn() {
        val northbound = cam(0.0, 1500.0, 0, AxisSense.ALONG, relation = true)
        assertEquals(1, drive(north, northbound) { enabled }, "enforced direction, travelling it")
        assertEquals(0, drive(south, northbound) { enabled }, "the other direction is not enforced by this device")
        // Off: exactly what the camera was without the relation (both directions of the axis).
        assertEquals(1, drive(north, northbound) { base })
        assertEquals(1, drive(south, northbound) { base })
    }

    @Test fun oppositeRelationSenseAlertsTheOtherWay() {
        val southbound = cam(0.0, 1500.0, 0, AxisSense.AGAINST, relation = true)
        assertEquals(0, drive(north, southbound) { enabled })
        assertEquals(1, drive(south, southbound) { enabled })
        assertEquals(1, drive(north, southbound) { base })
    }

    @Test fun relationSayingBothDirectionsAlertsBoth() {
        val both = cam(0.0, 1500.0, 0, AxisSense.BOTH, relation = true)
        assertEquals(1, drive(north, both) { enabled })
        assertEquals(1, drive(south, both) { enabled })
    }

    @Test fun cameraWithoutRelationIsUnchangedWhateverTheSwitch() {
        for (c in listOf(cam(0.0, 1500.0, 0, AxisSense.BOTH), cam(0.0, 1500.0, null, AxisSense.BOTH), cam(0.0, 1500.0, 0, AxisSense.ALONG))) {
            for (route in listOf(north, south)) {
                assertEquals(drive(route, c) { base }, drive(route, c) { enabled })
            }
        }
    }

    @Test fun relationDirectionNeedsNoLateralTightening() {
        // 30 m to the left of a northbound driver: an unknown-direction camera there is the other carriageway's (18 m
        // limit on the left). One with an enforced direction we travel is accepted (35 m allowance for position error).
        val left = cam(-30.0, 1500.0, 0, AxisSense.ALONG, relation = true)
        assertEquals(1, drive(north, left) { enabled })
        assertEquals(0, drive(north, left) { base }, "off: today's tight allowance for a camera without a known direction")
        // And the camera of the other carriageway, enforced southbound, is ignored by a northbound driver.
        val otherCarriageway = cam(-30.0, 1500.0, 0, AxisSense.AGAINST, relation = true)
        assertEquals(0, drive(north, otherCarriageway) { enabled })
    }

    @Test fun axisThatCameOnlyFromTheRelationIsIgnoredWhenTheSwitchIsOff() {
        // The camera had no axis of its own: before the relation it counted on any road it was on.
        val c = cam(0.0, 1500.0, 0, AxisSense.ALONG, relation = true, axisOnly = true)
        assertEquals(1, drive(east, c) { base }, "off: no axis, so the crossing route counts as before")
        assertEquals(0, drive(east, c) { enabled }, "on: the enforced direction is north, an east-west road is not it")
        assertEquals(1, drive(north, c) { enabled })
    }

    @Test fun switchIsReadAtEveryFixSoItAppliesLive() {
        val southbound = cam(0.0, 1500.0, 0, AxisSense.AGAINST, relation = true)
        var on = false
        val g = RouteGeometry(north)
        var n = 0
        val w = AlertWarner(listOf(TargetGrid(targetsOf(southbound))), { if (on) enabled else base }) { n++ }
        fun at(along: Double, t: Long) {
            val p = g.pointAt(along)
            w.onRouteFix(g, along, p.lat, p.lon, 25f, t * 1000L)
        }
        at(800.0, 0)
        assertEquals(1, n, "off: alerts, as before")
        w.reset()
        n = 0
        on = true
        at(800.0, 100)
        assertEquals(0, n, "on: a northbound driver is not who this camera watches")
    }

    @Test fun targetModelKeepsThePreRelationValuesAndTheRelationOnesApart() {
        val t = targetsOf(cam(0.0, 1500.0, 0, AxisSense.AGAINST, relation = true, axisOnly = true)).single()
        assertEquals(null, t.axisDeg)
        assertEquals(AxisSense.BOTH, t.sense)
        assertEquals(0, t.relationAxisDeg)
        assertEquals(AxisSense.AGAINST, t.relationSense)
        val plain = targetsOf(cam(0.0, 1500.0, 90, AxisSense.ALONG)).single()
        assertEquals(null, plain.relationSense)
        assertTrue(plain.axisDeg == 90 && plain.sense == AxisSense.ALONG)
    }
}
