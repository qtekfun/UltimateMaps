package com.qtekfun.ultimatemaps.core.map

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The direction the device is facing, from its orientation sensor, in degrees clockwise from TRUE north (0..360). Used
 * where the GPS course says nothing: standing still, walking slowly, before the first metres of a trip. Needs no permission.
 */
interface HeadingSource {
    /** Starts reporting; [onHeading] may be called from the main thread at a modest rate. False when the device has no usable sensor. */
    fun start(onHeading: (Float) -> Unit): Boolean

    fun stop()
}

/** No compass: the default (tests, devices without the sensor). */
object NoHeadingSource : HeadingSource {
    override fun start(onHeading: (Float) -> Unit) = false
    override fun stop() {}
}

/**
 * The maths of the compass, pure (the Android source only feeds it): rotation-vector sensor values to a heading, whichever way the
 * phone is held, and the smoothing of a circular quantity.
 *
 * World frame of Android's rotation vector: x east, y north, z up. A phone lying flat points along its own y axis (the top
 * edge); a phone upright in a car mount points along the back of the device, minus its z axis. Which of the two is used depends
 * on how much the screen faces the sky.
 */
object CompassMath {
    /** Above this share of the device z axis pointing up the phone counts as lying flat (about 45 degrees of tilt). */
    const val FLAT_THRESHOLD = 0.7

    /** The 3x3 rotation matrix (row-major, device to world) of a unit quaternion given as the rotation-vector sensor's x, y, z, w. */
    fun matrix(x: Double, y: Double, z: Double, w: Double): DoubleArray {
        val n = sqrt(x * x + y * y + z * z + w * w).takeIf { it > 1e-9 } ?: return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val qx = x / n
        val qy = y / n
        val qz = z / n
        val qw = w / n
        return doubleArrayOf(
            1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
            2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
            2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy),
        )
    }

    /** The unit quaternion of a rotation of [degrees] clockwise (seen from above) about the vertical axis, for tests and callers. */
    fun quaternionForAzimuth(degrees: Double): DoubleArray {
        // Android's orientation: a device with its y axis to the north has the identity rotation; turning clockwise to the east is a rotation about -z.
        val half = Math.toRadians(-degrees) / 2
        return doubleArrayOf(0.0, 0.0, sin(half), cos(half))
    }

    /** Heading in degrees clockwise from magnetic/sensor north (0..360) from a rotation matrix, or null when the phone is held so that no direction is defined. */
    fun azimuth(r: DoubleArray): Float? {
        val zUp = r[8] // world z component of the device z axis: 1 face up, 0 upright, -1 face down
        val east: Double
        val north: Double
        if (kotlin.math.abs(zUp) >= FLAT_THRESHOLD) {
            east = r[1] // device y axis (top edge) in the world frame
            north = r[4]
        } else {
            east = -r[2] // the back of the device: minus the z axis
            north = -r[5]
        }
        if (east * east + north * north < 1e-6) return null
        return normalize(Math.toDegrees(atan2(east, north)))
    }

    fun normalize(degrees: Double): Float = (((degrees % 360.0) + 360.0) % 360.0).toFloat()

    /** Heading plus the magnetic declination of the place (true north = magnetic north + declination). */
    fun trueHeading(magnetic: Float, declinationDegrees: Float): Float = normalize(magnetic.toDouble() + declinationDegrees)

    /** The shortest signed turn from [from] to [to], in -180..180. */
    fun delta(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /** One step of an exponential filter on a circle: [alpha] 1 follows the new value at once, near 0 hardly moves. */
    fun smooth(previous: Float?, next: Float, alpha: Float): Float =
        if (previous == null) next else normalize((previous + delta(previous, next) * alpha).toDouble())
}
