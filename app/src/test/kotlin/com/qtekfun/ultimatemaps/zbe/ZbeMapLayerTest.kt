package com.qtekfun.ultimatemaps.zbe

import com.qtekfun.ultimatemaps.core.map.ZoneShape
import com.qtekfun.ultimatemaps.core.zbe.InMemoryZbeSettingsStore
import com.qtekfun.ultimatemaps.core.zbe.ZbeDataset
import com.qtekfun.ultimatemaps.core.zbe.ZbePolygon
import com.qtekfun.ultimatemaps.core.zbe.ZbeRepository
import com.qtekfun.ultimatemaps.core.zbe.ZbeRing
import com.qtekfun.ultimatemaps.core.zbe.ZbeSettings
import com.qtekfun.ultimatemaps.core.zbe.ZbeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ZbeMapLayerTest {
    private val outer = ZbeRing(doubleArrayOf(40.0, -3.0, 40.0, -2.9, 40.1, -2.9, 40.1, -3.0))
    private val hole = ZbeRing(doubleArrayOf(40.04, -2.96, 40.04, -2.94, 40.06, -2.94, 40.06, -2.96))
    private val zones = listOf(
        ZbeZone("a", "A", "Madrid", "", listOf(ZbePolygon(listOf(outer, hole)))),
        ZbeZone("b", "B", "Madrid", "", listOf(ZbePolygon(listOf(outer)), ZbePolygon(listOf(hole)))),
    )

    @Test fun shapesAreClosedRingsWithTheHolesAndOnePerPolygon() {
        val shapes = ZbeMapLayer.shapesOf(zones)
        assertEquals(3, shapes.size)
        assertEquals(2, shapes[0].rings.size)
        shapes.flatMap { it.rings }.forEach { assertEquals(it.first(), it.last(), "closed") }
        assertEquals(5, shapes[0].rings[0].size)
    }

    @Test fun drawsOnlyWhileTheSwitchAndTheMapToggleAreOnAndFollowsTheData() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryZbeSettingsStore(ZbeSettings())
        val repo = ZbeRepository()
        val drawn = ArrayList<List<ZoneShape>>()
        ZbeMapLayer(backgroundScope, repo, store.settings) { drawn += it }.start()
        assertTrue(drawn.last().isEmpty(), "off by default: nothing drawn")
        repo.install(ZbeDataset(1L, 1, zones))
        assertTrue(drawn.last().isEmpty(), "data without the switch is not drawn")
        store.update { it.copy(enabled = true) }
        assertEquals(3, drawn.last().size)
        store.update { it.copy(showOnMap = false) }
        assertTrue(drawn.last().isEmpty(), "the map toggle hides the layer")
        store.update { it.copy(showOnMap = true) }
        assertEquals(3, drawn.last().size)
        repo.install(null)
        assertTrue(drawn.last().isEmpty(), "switching the data off removes it")
        val count = drawn.size
        store.update { it.copy(promptMode = com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode.SILENT) }
        assertEquals(count, drawn.size, "an unrelated setting does not redraw")
    }
}
