package com.qtekfun.ultimatemaps.nav

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.qtekfun.ultimatemaps.core.nav.MotionDetector
import com.qtekfun.ultimatemaps.core.nav.MotionDetectorConfig
import com.qtekfun.ultimatemaps.core.nav.MotionState
import com.qtekfun.ultimatemaps.core.nav.StopGoSignal

/** Where accelerometer samples come from. Started and stopped by [AndroidStopGoSignal]; a fake in tests. */
interface MotionSampleSource {
    fun interface Sink {
        fun onSample(x: Float, y: Float, z: Float)
    }

    /** Starts delivering samples at a low rate. Returns false when the device has no usable sensor. */
    fun start(sink: Sink): Boolean

    /** Stops delivering. Idempotent. */
    fun stop()
}

/** [MotionSampleSource] over [SensorManager]: linear acceleration when present, the raw accelerometer otherwise. */
class SensorManagerSampleSource(context: Context) : MotionSampleSource, SensorEventListener {
    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var sink: MotionSampleSource.Sink? = null

    override fun start(sink: MotionSampleSource.Sink): Boolean {
        val m = manager ?: return false
        val sensor = m.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        this.sink = sink
        // SENSOR_DELAY_NORMAL, no batching (maxReportLatency 0): the lowest rate and no extra hardware FIFO use.
        val ok = runCatching { m.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) }.getOrDefault(false)
        if (!ok) this.sink = null
        return ok
    }

    override fun stop() {
        sink = null
        runCatching { manager?.unregisterListener(this) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (v.size >= 3) sink?.onSample(v[0], v[1], v[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/**
 * [StopGoSignal] from the phone's accelerometer, active ONLY while the navigation is dead-reckoning through a known
 * tunnel: the tracker asks [motionState] only then, so the first question registers the sensor, and [release] (a fix
 * returned, or the navigation ended) unregisters it at once. The samples go into a small in-memory ring of a
 * [MotionDetector]; nothing is stored, logged or sent. With the setting off or no sensor the answer is UNKNOWN and the
 * estimator keeps its old behaviour.
 *
 * Thread-safe: the sensor thread feeds, the navigation thread asks and releases.
 */
class AndroidStopGoSignal(
    private val source: MotionSampleSource,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    config: MotionDetectorConfig = MotionDetectorConfig(),
) : StopGoSignal {
    private val lock = Any()
    private val detector = MotionDetector(config)
    private var registered = false
    private var unavailable = false
    private val sink = MotionSampleSource.Sink { x, y, z -> synchronized(lock) { if (registered) detector.onSample(clock(), x, y, z) } }

    /** True while the sensor is registered (for tests and diagnostics). */
    val isActive: Boolean get() = synchronized(lock) { registered }

    override fun motionState(nowMillis: Long): MotionState = synchronized(lock) {
        if (!enabled()) {
            stopLocked()
            return MotionState.UNKNOWN
        }
        if (!registered && !unavailable) {
            detector.reset()
            if (source.start(sink)) registered = true else unavailable = true
        }
        // The detector is stamped with the same clock as the navigation's (System.currentTimeMillis by default).
        return if (registered) detector.state(nowMillis) else MotionState.UNKNOWN
    }

    override fun release() {
        synchronized(lock) {
            stopLocked()
            unavailable = false // a later loss may try again
        }
    }

    private fun stopLocked() {
        if (registered) source.stop()
        registered = false
        detector.reset()
    }
}
