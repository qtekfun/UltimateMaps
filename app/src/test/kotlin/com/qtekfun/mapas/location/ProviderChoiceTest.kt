package com.qtekfun.mapas.location

import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderChoiceTest {
    private fun choose(sdk: Int, vararg present: String) = ProviderChoice.choose(sdk) { it in present }

    @Test
    fun api31UsesFusedOnlyWhenPresent() {
        assertEquals(listOf("fused"), choose(31, "fused", "gps", "network"))
        assertEquals(listOf("gps", "network"), choose(34, "gps", "network"))
    }

    @Test
    fun olderApisNeverUseFused() {
        assertEquals(listOf("gps", "network"), choose(30, "fused", "gps", "network"))
        assertEquals(listOf("gps"), choose(26, "gps"))
    }

    @Test
    fun noProviderGivesEmptyList() {
        assertEquals(emptyList(), choose(35))
    }
}
