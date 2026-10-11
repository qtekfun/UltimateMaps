package com.qtekfun.ultimatemaps.nav

import kotlin.test.Test
import kotlin.test.assertEquals

class PowerSavePolicyTest {
    @Test fun `the interval follows the chosen rate and the battery saver caps it at 30`() {
        assertEquals(14L, PowerSavePolicy.minIntervalMillis(60, saver = false))
        assertEquals(20L, PowerSavePolicy.minIntervalMillis(45, saver = false))
        assertEquals(31L, PowerSavePolicy.minIntervalMillis(30, saver = false))
        assertEquals(31L, PowerSavePolicy.minIntervalMillis(60, saver = true), "saver: at most 30")
        assertEquals(31L, PowerSavePolicy.minIntervalMillis(30, saver = true))
    }

    @Test fun `a nonsense rate cannot make a zero or huge interval`() {
        assertEquals(998L, PowerSavePolicy.minIntervalMillis(0, saver = false)) // clamped to 1 per second
        assertEquals(998L, PowerSavePolicy.minIntervalMillis(1, saver = true))
        assertEquals(6L, PowerSavePolicy.minIntervalMillis(1000, saver = false)) // clamped to 120
    }
}
