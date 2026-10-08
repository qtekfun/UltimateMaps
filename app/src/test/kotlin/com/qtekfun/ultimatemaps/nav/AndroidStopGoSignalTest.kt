package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.nav.MotionState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeSource(var present: Boolean = true) : MotionSampleSource {
    var starts = 0
    var stops = 0
    var sink: MotionSampleSource.Sink? = null
    val registered get() = sink != null

    override fun start(sink: MotionSampleSource.Sink): Boolean {
        if (!present) return false
        starts++
        this.sink = sink
        return true
    }

    override fun stop() {
        if (sink != null) stops++
        sink = null
    }
}

/** The lifecycle of the sensor around a signal loss, with a fake source and a virtual clock. */
class AndroidStopGoSignalTest {
    private var now = 1_000_000L
    private val source = FakeSource()
    private var enabled = true
    private val signal = AndroidStopGoSignal(source, { enabled }, { now })

    /** Delivers a still phone (gravity only, tiny noise) for [seconds] at 5 Hz, asking the state every second. */
    private fun still(seconds: Int): MotionState {
        var last = MotionState.UNKNOWN
        repeat(seconds * 5) { i ->
            source.sink?.onSample(0f, 0f, 9.81f + if (i % 2 == 0) 0.01f else -0.01f)
            now += 200
            if (i % 5 == 4) last = signal.motionState(now)
        }
        return last
    }

    @Test fun theSensorIsNotRegisteredUntilALossAsksAndNotBeforeRelease() {
        assertFalse(source.registered)
        assertEquals(0, source.starts)
        signal.motionState(now)
        assertTrue(source.registered)
        assertEquals(1, source.starts)
        signal.motionState(now + 1000)
        assertEquals(1, source.starts, "registered once per loss, not per tick")
    }

    @Test fun aFixReturningUnregistersAtOnceAndTheNextLossRegistersAgain() {
        signal.motionState(now)
        signal.release()
        assertFalse(source.registered)
        assertEquals(1, source.stops)
        assertFalse(signal.isActive)
        signal.release()
        assertEquals(1, source.stops, "idempotent")
        signal.motionState(now)
        assertEquals(2, source.starts)
        assertTrue(source.registered)
    }

    @Test fun aStillPhoneReportsStoppedDuringTheLossAndNothingCarriesOverToTheNextOne() {
        signal.motionState(now) // the loss starts: registers
        assertEquals(MotionState.STOPPED, still(15))
        signal.release()
        signal.motionState(now)
        assertEquals(MotionState.UNKNOWN, signal.motionState(now), "a new loss starts with an empty window")
    }

    @Test fun samplesArrivingAfterReleaseAreIgnored() {
        signal.motionState(now)
        val sink = source.sink!!
        signal.release()
        sink.onSample(1f, 2f, 3f) // a late callback from the sensor thread
        assertEquals(MotionState.UNKNOWN, signal.motionState(now))
    }

    @Test fun withTheSettingOffTheSensorIsNeverRegisteredAndTurningItOffMidLossReleasesIt() {
        enabled = false
        assertEquals(MotionState.UNKNOWN, signal.motionState(now))
        assertEquals(0, source.starts)
        enabled = true
        signal.motionState(now)
        assertTrue(source.registered)
        enabled = false
        assertEquals(MotionState.UNKNOWN, signal.motionState(now))
        assertFalse(source.registered)
    }

    @Test fun aMissingSensorMeansUnknownAndIsNotRetriedEveryTickWithinTheLoss() {
        source.present = false
        repeat(5) { assertEquals(MotionState.UNKNOWN, signal.motionState(now)) }
        assertEquals(0, source.starts)
        assertFalse(signal.isActive)
        source.present = true
        signal.release()
        signal.motionState(now)
        assertEquals(1, source.starts, "a later loss tries again")
    }
}
