package com.qtekfun.ultimatemaps.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.CompassMath
import com.qtekfun.ultimatemaps.core.map.HeadingSource
import kotlin.math.abs

/**
 * [HeadingSource] over the rotation-vector sensor (the phone's own fusion of magnetometer, accelerometer and gyroscope): no
 * permission, no Google service. The heading is the direction the phone points (top edge when flat, the back when upright in a
 * mount), corrected from magnetic to true north with the declination of the last known place, smoothed on the circle and
 * reported at most every [minIntervalMillis] and only when it moved by at least [minChangeDegrees]. Main thread.
 */
class AndroidHeadingSource(
    context: Context,
    /** The last known position, for the declination; null: no correction (about 1 degree in Spain). */
    private val place: () -> LatLon?,
    private val minIntervalMillis: Long = 100L,
    private val minChangeDegrees: Float = 1.5f,
    private val alpha: Float = 0.2f,
) : HeadingSource {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotation: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var listener: SensorEventListener? = null
    private var smoothed: Float? = null
    private var lastReported: Float? = null
    private var lastReportedAt = 0L
    private var declination = 0f
    private var declinationAt = 0L

    override fun start(onHeading: (Float) -> Unit): Boolean {
        val manager = sensors ?: return false
        val sensor = rotation ?: return false
        stop()
        val l = object : SensorEventListener {
            private val q = FloatArray(4)

            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values
                // The rotation vector is (x, y, z[, w]); the fourth value is optional on some devices.
                val x = v[0].toDouble()
                val y = v[1].toDouble()
                val z = v[2].toDouble()
                val w = if (v.size > 3) v[3].toDouble() else kotlin.math.sqrt((1.0 - x * x - y * y - z * z).coerceAtLeast(0.0))
                val magnetic = CompassMath.azimuth(CompassMath.matrix(x, y, z, w)) ?: return
                val trueHeading = CompassMath.trueHeading(magnetic, declination())
                val s = CompassMath.smooth(smoothed, trueHeading, alpha)
                smoothed = s
                val now = SystemClock.elapsedRealtime()
                val last = lastReported
                if (last != null && (now - lastReportedAt < minIntervalMillis || abs(CompassMath.delta(last, s)) < minChangeDegrees)) return
                lastReported = s
                lastReportedAt = now
                onHeading(s)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = l
        return manager.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    override fun stop() {
        listener?.let { sensors?.unregisterListener(it) }
        listener = null
        smoothed = null
        lastReported = null
    }

    /** The magnetic declination, recomputed at most every ten minutes (it hardly changes as one drives). */
    private fun declination(): Float {
        val now = SystemClock.elapsedRealtime()
        if (now - declinationAt > 10 * 60_000L) {
            declinationAt = now
            place()?.let {
                declination = runCatching { GeomagneticField(it.lat.toFloat(), it.lon.toFloat(), 0f, System.currentTimeMillis()).declination }.getOrDefault(0f)
            }
        }
        return declination
    }
}
