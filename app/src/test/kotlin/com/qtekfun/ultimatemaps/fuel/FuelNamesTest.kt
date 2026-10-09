package com.qtekfun.ultimatemaps.fuel

import com.qtekfun.ultimatemaps.core.fuel.FuelTypes
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FuelNamesTest {
    @Test
    fun everyFuelOfTheMinistryHasAStringResource() {
        for (fuel in FuelTypes.all) assertNotNull(FuelNames.resOf(fuel.id), "no string resource for fuel '${fuel.id}'")
    }

    @Test
    fun differentFuelsHaveDifferentResources() {
        val resources = FuelTypes.all.map { FuelNames.resOf(it.id) }
        assertEquals(resources.size, resources.toSet().size)
    }

    @Test
    fun anUnknownFuelHasNoResource() {
        assertNull(FuelNames.resOf("not-a-fuel"))
    }
}
