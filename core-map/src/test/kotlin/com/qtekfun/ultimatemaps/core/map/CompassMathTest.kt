package com.qtekfun.ultimatemaps.core.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompassMathTest {
    private fun flat(azimuthDeg: Double): Float {
        val q = CompassMath.quaternionForAzimuth(azimuthDeg)
        return assertNotNull(CompassMath.azimuth(CompassMath.matrix(q[0], q[1], q[2], q[3])))
    }

    private fun near(expected: Double, actual: Float, tol: Double = 0.5) =
        assertTrue(abs(CompassMath.delta(expected.toFloat(), actual)) <= tol, "expected $expected but was $actual")

    @Test fun `a phone lying flat reads the direction of its top edge`() {
        for (az in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 359.0)) near(az, flat(az))
    }

    @Test fun `an upright phone in a mount reads the direction its back faces`() {
        // Upright: rotate the flat device 90 degrees about its x axis (top edge goes up, back faces the horizon), then turn it about the vertical.
        for (az in listOf(0.0, 90.0, 200.0, 315.0)) {
            val flatQ = CompassMath.quaternionForAzimuth(az)
            val s = Math.sin(Math.toRadians(45.0))
            val c = Math.cos(Math.toRadians(45.0))
            // q = qAz * qX(90 degrees): quaternion product (x, y, z, w)
            val (ax, ay, az2, aw) = flatQ.toList()
            val bx = s; val by = 0.0; val bz = 0.0; val bw = c
            val x = aw * bx + ax * bw + ay * bz - az2 * by
            val y = aw * by - ax * bz + ay * bw + az2 * bx
            val z = aw * bz + ax * by - ay * bx + az2 * bw
            val w = aw * bw - ax * bx - ay * by - az2 * bz
            val h = assertNotNull(CompassMath.azimuth(CompassMath.matrix(x, y, z, w)))
            // the back of an upright phone faces the way the user looks: the opposite of what the screen faces
            near(az, h, 1.0)
        }
    }

    @Test fun `declination turns magnetic into true north`() {
        assertEquals(5f, CompassMath.trueHeading(355f, 10f))
        assertEquals(350f, CompassMath.trueHeading(0f, -10f))
    }

    @Test fun `smoothing goes the short way round the circle`() {
        assertEquals(359f, CompassMath.smooth(358f, 0f, 0.5f), 0.01f) // 358 -> 359, not through 180
        assertEquals(1f, CompassMath.smooth(2f, 0f, 0.5f), 0.01f)
        assertEquals(10f, CompassMath.smooth(null, 10f, 0.2f))
        assertEquals(350f, CompassMath.smooth(350f, 355f, 0f), 0.01f)
    }

    @Test fun `delta is the signed shortest turn`() {
        assertEquals(20f, CompassMath.delta(350f, 10f), 0.01f)
        assertEquals(-20f, CompassMath.delta(10f, 350f), 0.01f)
        assertEquals(180f, abs(CompassMath.delta(0f, 180f)), 0.01f)
    }
}
