package com.qtekfun.ultimatemaps.core.nav

import kotlin.math.sqrt

/**
 * Thresholds of [MotionDetector]. NOT tuned on a drive yet: they are first guesses from typical in-car vibration
 * levels and must be measured on a device (see `docs/decisions.md`, "tunnel motion sensors").
 */
data class MotionDetectorConfig(
    /** Length of the sliding window the variance is computed over. */
    val windowMillis: Long = 3_000L,
    /** Fewest samples in the window before any decision (a handful at the lowest sensor rate). */
    val minSamples: Int = 6,
    /** The newest sample must be this recent, else the answer is UNKNOWN (sensor stalled). */
    val maxSampleAgeMillis: Long = 2_000L,
    /** Variance of |a| (m/s^2)^2 at or below this looks like a stopped car (dead still, or only engine idle). */
    val stopVariance: Double = 0.004,
    /** Variance of |a| at or above this looks like a moving car (road noise, bumps, braking, a handled phone). */
    val moveVariance: Double = 0.02,
    /** The low variance must hold this long before STOPPED is announced (a false STOPPED freezes the estimate). */
    val stopDwellMillis: Long = 5_000L,
    /** The high variance must hold this long before MOVING is announced. */
    val moveDwellMillis: Long = 1_500L,
    /** Ring size; must cover windowMillis at the highest rate the sensor may deliver. */
    val capacity: Int = 256,
)

/**
 * Stop/go from the accelerometer alone: variance of the acceleration magnitude over a sliding window, with a
 * hysteresis band between [MotionDetectorConfig.stopVariance] and [MotionDetectorConfig.moveVariance] and a minimum
 * dwell time before either state is announced. The magnitude makes it independent of the phone's orientation and of
 * whether gravity is included (a constant offset does not change a variance).
 *
 * Only a relative signal: a smooth, steady cruise has little vibration, so low variance alone is a weak reason to
 * believe "stopped"; that is why STOPPED needs a long dwell and why the state starts UNKNOWN. A phone being handled
 * reads as MOVING, which is the safe side (the estimator then keeps its old behaviour).
 *
 * Deterministic (time only comes from the samples), allocation-free after construction, not thread-safe.
 */
class MotionDetector(private val cfg: MotionDetectorConfig = MotionDetectorConfig()) {
    private val times = LongArray(cfg.capacity)
    private val mags = DoubleArray(cfg.capacity)
    private var head = 0 // index of the oldest sample
    private var size = 0
    private var state = MotionState.UNKNOWN
    private var lowSince = -1L
    private var highSince = -1L
    private var lastSampleTime = Long.MIN_VALUE

    /** Forgets everything (a new loss starts, or the sensor was off). */
    fun reset() {
        head = 0
        size = 0
        state = MotionState.UNKNOWN
        lowSince = -1L
        highSince = -1L
        lastSampleTime = Long.MIN_VALUE
    }

    /** Feeds one accelerometer sample (any orientation, with or without gravity), stamped by the caller's clock. */
    fun onSample(timeMillis: Long, x: Float, y: Float, z: Float) {
        if (timeMillis < lastSampleTime) { // the clock jumped back: the window is meaningless
            reset()
        }
        lastSampleTime = timeMillis
        while (size > 0 && timeMillis - times[head] > cfg.windowMillis) {
            head = (head + 1) % cfg.capacity
            size--
        }
        if (size == cfg.capacity) {
            head = (head + 1) % cfg.capacity
            size--
        }
        val tail = (head + size) % cfg.capacity
        times[tail] = timeMillis
        mags[tail] = sqrt((x * x + y * y + z * z).toDouble())
        size++
        update(timeMillis)
    }

    /** Current answer at [nowMillis]; UNKNOWN until a state was decided, or when the samples went stale. */
    fun state(nowMillis: Long): MotionState =
        if (size == 0 || nowMillis - lastSampleTime > cfg.maxSampleAgeMillis) MotionState.UNKNOWN else state

    private fun update(now: Long) {
        // Need enough samples that span most of the window; otherwise no decision yet.
        if (size < cfg.minSamples || now - times[head] < cfg.windowMillis * 2 / 3) return
        var sum = 0.0
        for (i in 0 until size) sum += mags[(head + i) % cfg.capacity]
        val mean = sum / size
        var sq = 0.0
        for (i in 0 until size) {
            val d = mags[(head + i) % cfg.capacity] - mean
            sq += d * d
        }
        val variance = sq / size
        when {
            variance <= cfg.stopVariance -> {
                highSince = -1L
                if (lowSince < 0) lowSince = now
                if (now - lowSince >= cfg.stopDwellMillis) state = MotionState.STOPPED
            }
            variance >= cfg.moveVariance -> {
                lowSince = -1L
                if (highSince < 0) highSince = now
                if (now - highSince >= cfg.moveDwellMillis) state = MotionState.MOVING
            }
            else -> { // the hysteresis band: keep the state, restart both dwell timers
                lowSince = -1L
                highSince = -1L
            }
        }
    }
}
