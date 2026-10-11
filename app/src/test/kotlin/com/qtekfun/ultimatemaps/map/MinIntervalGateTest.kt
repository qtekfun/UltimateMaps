package com.qtekfun.ultimatemaps.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinIntervalGateTest {
    @Test fun `the first one always passes`() {
        assertTrue(MinIntervalGate(30).ready(0))
    }

    @Test fun `one every second display frame at 60 Hz, never faster than the interval`() {
        val gate = MinIntervalGate(30)
        var passed = 0
        var t = 1_000L
        repeat(60) {
            if (gate.ready(t)) {
                gate.pass(t)
                passed++
            }
            t += 17
        }
        assertEquals(30, passed)
    }

    @Test fun `it says how long to wait`() {
        val gate = MinIntervalGate(30)
        gate.pass(100)
        assertFalse(gate.ready(110))
        assertEquals(20, gate.waitMillis(110))
        assertTrue(gate.ready(130))
        assertEquals(0, gate.waitMillis(130))
    }

    @Test fun `a clock that went backwards does not block it for ever`() {
        val gate = MinIntervalGate(30)
        gate.pass(10_000)
        assertTrue(gate.ready(5))
    }
}
