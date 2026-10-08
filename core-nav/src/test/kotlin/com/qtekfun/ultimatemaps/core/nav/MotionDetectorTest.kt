package com.qtekfun.ultimatemaps.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Synthetic accelerometer streams at 5 Hz (the SENSOR_DELAY_NORMAL order of magnitude). No real clock, fixed seed. */
class MotionDetectorTest {
    private val t0 = 5_000_000L
    private val periodMs = 200L

    private class Rng(private var s: Long = 12345L) {
        /** Uniform in [-1, 1]. */
        fun next(): Float {
            s = s * 6364136223846793005L + 1442695040888963407L
            return (((s ushr 33).toInt() and 0xFFFF) / 32767.5f) - 1f
        }
    }

    private class Stream(val d: MotionDetector, val t0: Long, val period: Long) {
        val rng = Rng()
        var t = t0
        val states = ArrayList<MotionState>()

        /** [seconds] of gravity (along [axis]) plus uniform noise of +-[amp] on every axis. */
        fun feed(seconds: Int, amp: Float, axis: Int = 2) {
            repeat((seconds * 1000 / period).toInt()) {
                val v = floatArrayOf(rng.next() * amp, rng.next() * amp, rng.next() * amp)
                v[axis] += 9.81f
                d.onSample(t, v[0], v[1], v[2])
                states += d.state(t)
                t += period
            }
        }

        fun last() = states.last()
    }

    private fun stream() = Stream(MotionDetector(), t0, periodMs)

    @Test fun anIdlingCarWithEngineVibrationBecomesStoppedAfterTheDwell() {
        val s = stream()
        s.feed(3, amp = 0.08f)
        assertEquals(MotionState.UNKNOWN, s.last(), "not enough yet")
        s.feed(10, amp = 0.08f)
        assertEquals(MotionState.STOPPED, s.last())
    }

    @Test fun steadyCruisingIsMovingAndNeverStopped() {
        val s = stream()
        s.feed(60, amp = 0.5f)
        assertEquals(MotionState.MOVING, s.last())
        assertFalse(MotionState.STOPPED in s.states)
    }

    @Test fun stopAndGoFollowsTheCar() {
        val s = stream()
        s.feed(20, amp = 0.5f)
        assertEquals(MotionState.MOVING, s.last())
        s.feed(30, amp = 0.05f) // queue
        assertEquals(MotionState.STOPPED, s.last())
        s.feed(10, amp = 0.5f) // away again
        assertEquals(MotionState.MOVING, s.last())
    }

    @Test fun theOrientationOfThePhoneDoesNotMatter() {
        for (axis in 0..2) {
            val s = stream()
            s.feed(15, amp = 0.05f, axis = axis)
            assertEquals(MotionState.STOPPED, s.last(), "axis $axis")
        }
    }

    @Test fun aPhoneHandledByTheUserNeverReadsStopped() {
        val s = stream()
        s.feed(20, amp = 3.0f) // picked up, turned, shaken
        assertEquals(MotionState.MOVING, s.last())
        assertFalse(MotionState.STOPPED in s.states)
    }

    @Test fun aShortQuietSpellWhileDrivingIsNotAStop() {
        val s = stream()
        s.feed(20, amp = 0.5f)
        s.feed(4, amp = 0.05f) // smooth road for 4 s: less than the window plus the dwell
        s.feed(10, amp = 0.5f)
        assertFalse(MotionState.STOPPED in s.states)
    }

    @Test fun theHysteresisBandKeepsTheCurrentState() {
        // amp 0.17 -> variance of |a| ~ 0.01, between the two thresholds.
        val stopped = stream()
        stopped.feed(15, amp = 0.05f)
        stopped.feed(30, amp = 0.17f)
        assertEquals(MotionState.STOPPED, stopped.last())
        val moving = stream()
        moving.feed(15, amp = 0.5f)
        moving.feed(30, amp = 0.17f)
        assertEquals(MotionState.MOVING, moving.last())
    }

    @Test fun staleSamplesMeanUnknown() {
        val s = stream()
        s.feed(15, amp = 0.05f)
        assertEquals(MotionState.STOPPED, s.d.state(s.t))
        assertEquals(MotionState.UNKNOWN, s.d.state(s.t + 10_000L))
    }

    @Test fun resetForgetsEverything() {
        val s = stream()
        s.feed(15, amp = 0.05f)
        s.d.reset()
        assertEquals(MotionState.UNKNOWN, s.d.state(s.t))
        s.feed(3, amp = 0.05f)
        assertEquals(MotionState.UNKNOWN, s.last(), "a fresh window needs the full dwell again")
    }

    @Test fun aClockJumpingBackStartsOver() {
        val s = stream()
        s.feed(15, amp = 0.05f)
        s.d.onSample(t0 - 1_000_000L, 0f, 0f, 9.81f)
        assertEquals(MotionState.UNKNOWN, s.d.state(t0 - 1_000_000L))
    }

    @Test fun aFastSensorDoesNotOverflowTheRing() {
        val d = MotionDetector()
        var t = t0
        repeat(2_000) {
            d.onSample(t, 0f, 0f, 9.81f)
            t += 20L // 50 Hz, far above what is requested
        }
        assertEquals(MotionState.STOPPED, d.state(t))
    }
}
