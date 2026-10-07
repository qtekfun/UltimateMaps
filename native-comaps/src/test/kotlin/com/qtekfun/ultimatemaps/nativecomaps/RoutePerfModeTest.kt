package com.qtekfun.ultimatemaps.nativecomaps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoutePerfModeTest {
    @Test fun `empty and default mean stock behaviour`() {
        assertEquals(0, RoutePerfMode.parse(null))
        assertEquals(0, RoutePerfMode.parse(""))
        assertEquals(0, RoutePerfMode.parse("default"))
        assertEquals("default", RoutePerfMode.describe(0))
    }

    @Test fun `single tokens map to the bits shared with the C++ side`() {
        assertEquals(1, RoutePerfMode.parse("quiet"))
        assertEquals(2, RoutePerfMode.parse("prune"))
        assertEquals(4, RoutePerfMode.parse("cache"))
        assertEquals(1 shl 3, RoutePerfMode.parse("cand8"))
        assertEquals(2 shl 3, RoutePerfMode.parse("cand5"))
        assertEquals(3 shl 3, RoutePerfMode.parse("cand3"))
        assertEquals(1 shl 5, RoutePerfMode.parse("tmo10"))
        assertEquals(2 shl 5, RoutePerfMode.parse("tmo5"))
        assertEquals(3 shl 5, RoutePerfMode.parse("tmo2"))
    }

    @Test fun `tokens combine and ignore case and spaces`() {
        assertEquals(1 or 2, RoutePerfMode.parse(" Quiet , PRUNE "))
    }

    @Test fun `presets expand`() {
        assertEquals(RoutePerfMode.parse("quiet,prune,cache"), RoutePerfMode.parse("safe"))
        assertEquals(RoutePerfMode.parse("safe,cand5,tmo5"), RoutePerfMode.parse("fast"))
    }

    @Test fun `the safe preset never touches the fields that change routes`() {
        val safe = RoutePerfMode.parse("safe")
        assertEquals(0, safe and (3 shl RoutePerfMode.CAND_SHIFT))
        assertEquals(0, safe and (3 shl RoutePerfMode.TIMEOUT_SHIFT))
    }

    @Test fun `unknown token is rejected`() {
        assertFailsWith<IllegalArgumentException> { RoutePerfMode.parse("quiet,turbo") }
    }

    @Test fun `two values of the same field conflict`() {
        assertFailsWith<IllegalArgumentException> { RoutePerfMode.parse("cand8,cand5") }
        assertFailsWith<IllegalArgumentException> { RoutePerfMode.parse("tmo5,tmo2") }
        assertEquals(RoutePerfMode.parse("cand5"), RoutePerfMode.parse("cand5,cand5"))
        assertEquals(RoutePerfMode.parse("fast"), RoutePerfMode.parse("fast,cand5"))
    }

    @Test fun `describe round trips`() {
        for (text in listOf("quiet", "prune,cache", "quiet,prune,cache,cand5,tmo5", "cand3,tmo2")) {
            val flags = RoutePerfMode.parse(text)
            assertEquals(flags, RoutePerfMode.parse(RoutePerfMode.describe(flags)))
        }
        assertEquals("quiet,prune,cache,cand5,tmo5", RoutePerfMode.describe(RoutePerfMode.parse("fast")))
    }
}
