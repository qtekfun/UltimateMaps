package com.qtekfun.ultimatemaps.core.nav

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Thresholds of the signal-loss estimator. See `docs/phase2/tunnel-positioning.md`. */
data class TunnelConfig(
    /** A loss that starts this far before a span's start (or inside it) is an expected tunnel loss. */
    val entryMarginMeters: Double = 150.0,
    /** Hard limits of any estimate inside a known span, whatever the span says. */
    val hardMaxMillis: Long = 600_000L,
    val hardMaxMeters: Double = 10_000.0,
    /** Extra room beyond the exit of a LEARNED span (its exit is only a past observation): fraction of its length, plus metres. */
    val learnedSlackFraction: Double = 0.15,
    val learnedSlackMeters: Double = 50.0,
    // Speed model
    /** Acceleration seen before the loss is extrapolated for this long, then the speed is held. */
    val trendHorizonSeconds: Double = 3.0,
    val trendMinAccel: Double = 0.3,
    val maxDecel: Double = 4.0,
    val maxAccel: Double = 1.5,
    /** Smoothing of the acceleration seen between fixes (weight of the newest sample). */
    val accelAlpha: Double = 0.5,
    /** Smoothing of the speed seen at the fixes (weight of the newest sample). */
    val speedAlpha: Double = 0.6,
    /** After a stop is signalled, the speed assumed when it moves again is at most this. */
    val resumeSpeedMps: Double = 5.0,
    // Error model: accuracy at the last fix + perMeter * travelled + perSecond * time lost
    val errorPerMeter: Double = 0.05,
    val errorPerSecond: Double = 1.0,
    // Exit hand-over
    /** A returning fix this poor, near the span's exit, is replaced by the exit itself. */
    val snapAccuracyMeters: Double = 30.0,
    val snapMarginMeters: Double = 60.0,
    // Learning (no data source): a loss at least this long and this far is remembered as a tunnel
    val learn: Boolean = true,
    val learnMinMillis: Long = 8_000L,
    val learnMinMeters: Double = 120.0,
)

/**
 * Dead reckoning along the route while there is no fix. Fed with the good fixes ([onFix]); after the signal is
 * lost, [advance] integrates a speed model forward in small steps. Compared with "the last speed held": the
 * speed is smoothed over the last fixes, an acceleration or braking trend seen just before the loss is carried
 * for a few seconds, and an optional [StopGoSignal] freezes the estimate while the vehicle is stopped.
 *
 * Deterministic (time only comes from the arguments), allocation-free, not thread-safe.
 */
class TunnelEstimator(private val cfg: TunnelConfig = TunnelConfig()) {
    private var hasFix = false
    private var fixTime = 0L
    private var fixSpeed = Double.NaN
    private var smoothSpeed = 0.0
    private var accel = 0.0
    private var accuracy = 0.0

    // The loss in progress; times are seconds since the last good fix.
    private var losing = false
    private var simTau = 0.0
    private var lossProgress = 0.0
    private var lossStartProgress = 0.0
    private var v = 0.0
    private var v0 = 0.0
    private var a0 = 0.0
    private var stopSeen = false

    /** Speed assumed now, metres per second (meaningful during a loss). */
    val speedMps: Double get() = v

    /** Smoothed speed at the last good fix. */
    val lastSpeedMps: Double get() = smoothSpeed

    /** A good fix arrived: the loss, if any, is over. */
    fun onFix(timeMillis: Long, progress: Double, fixSpeedMps: Double, fallbackSpeedMps: Double, accuracyMeters: Double) {
        val s = if (fixSpeedMps.isNaN()) fallbackSpeedMps else fixSpeedMps
        accel = if (hasFix && !losing && !fixSpeed.isNaN() && !fixSpeedMps.isNaN()) {
            val dt = (timeMillis - fixTime) / 1000.0
            if (dt > 0.0 && dt <= 5.0) (1 - cfg.accelAlpha) * accel + cfg.accelAlpha * (fixSpeedMps - fixSpeed) / dt else 0.0
        } else {
            0.0
        }
        smoothSpeed = if (hasFix && !losing) (1 - cfg.speedAlpha) * smoothSpeed + cfg.speedAlpha * s else s
        fixSpeed = fixSpeedMps
        fixTime = timeMillis
        accuracy = accuracyMeters
        hasFix = true
        losing = false
        lossProgress = progress
    }

    private fun begin(anchorProgress: Double) {
        losing = true
        simTau = 0.0
        lossProgress = anchorProgress
        lossStartProgress = anchorProgress
        v0 = max(0.0, smoothSpeed)
        v = v0
        a0 = if (abs(accel) < cfg.trendMinAccel) 0.0 else accel.coerceIn(-cfg.maxDecel, cfg.maxAccel)
        stopSeen = false
    }

    /**
     * Moves the estimate forward to [nowMillis] and returns the progress, never past [limitMeters] and never
     * backwards. [anchorProgress] is where the last good fix put the vehicle (used to start the loss).
     */
    fun advance(nowMillis: Long, anchorProgress: Double, motion: MotionState, limitMeters: Double): Double {
        if (!hasFix) return anchorProgress
        if (!losing) begin(anchorProgress)
        val endTau = min(nowMillis - fixTime, cfg.hardMaxMillis) / 1000.0
        if (endTau > simTau) {
            val h = max(STEP_SECONDS, (endTau - simTau) / MAX_STEPS)
            val cap = min(limitMeters, lossStartProgress + cfg.hardMaxMeters)
            while (simTau < endTau - 1e-9 && lossProgress < cap) {
                val dt = min(h, endTau - simTau)
                val before = v
                v = nextSpeed(v, simTau + dt / 2, dt, motion)
                lossProgress = min(cap, lossProgress + 0.5 * (before + v) * dt)
                simTau += dt
            }
            // Out of room (end of the span, or the hard cap): the vehicle is not assumed to keep moving.
            if (lossProgress >= cap) v = 0.0
            simTau = max(simTau, endTau)
        }
        return lossProgress
    }

    private fun nextSpeed(current: Double, tau: Double, dt: Double, motion: MotionState): Double {
        if (motion == MotionState.STOPPED) {
            stopSeen = true
            return 0.0
        }
        var target = max(0.0, v0 + a0 * min(tau, cfg.trendHorizonSeconds))
        if (stopSeen) target = min(target, cfg.resumeSpeedMps)
        return if (target > current) min(target, current + cfg.maxAccel * dt) else max(target, current - cfg.maxDecel * dt)
    }

    /**
     * Estimated error radius for a loss that has lasted [seconds] and moved the estimate [travelledMeters],
     * never more than [capMeters] (the span length, when the estimate is clamped inside one).
     */
    fun errorMeters(seconds: Double, travelledMeters: Double, capMeters: Double = Double.MAX_VALUE): Double =
        min(accuracy + cfg.errorPerMeter * max(0.0, travelledMeters) + cfg.errorPerSecond * max(0.0, seconds), max(capMeters, accuracy))

    private companion object {
        const val STEP_SECONDS = 0.25
        const val MAX_STEPS = 400.0
    }
}
