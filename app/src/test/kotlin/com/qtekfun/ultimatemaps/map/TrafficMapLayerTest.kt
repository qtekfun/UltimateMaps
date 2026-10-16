package com.qtekfun.ultimatemaps.map

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.GeoBounds
import com.qtekfun.ultimatemaps.core.traffic.FlowGeoJson
import com.qtekfun.ultimatemaps.core.traffic.FlowLine
import com.qtekfun.ultimatemaps.core.traffic.TileId
import com.qtekfun.ultimatemaps.core.traffic.TrafficOutcome
import com.qtekfun.ultimatemaps.core.traffic.TrafficProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The map layer decisions in virtual time: nothing waits on a real clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficMapLayerTest {
    private val madrid = GeoBounds(40.40, -3.72, 40.43, -3.69)
    private var on = true
    private var asked = 0
    private var answer: List<FlowLine>? = listOf(FlowLine(2, listOf(LatLon(40.41, -3.70), LatLon(40.42, -3.71))))

    private val provider = object : TrafficProvider {
        override val enabled get() = on
        override suspend fun estimate(geometry: List<LatLon>, coreSeconds: Double, baseSpeedMps: ((Double) -> Double)?) =
            TrafficOutcome.Unavailable(TrafficOutcome.Reason.DISABLED)
        override suspend fun flowLines(tiles: List<TileId>): List<FlowLine>? { asked++; return answer }
    }

    private fun TestScope.layer(draws: MutableList<String>) =
        TrafficMapLayer(this, StandardTestDispatcher(testScheduler), provider, { on }, { draws += it }, debounceMs = 100L, refreshMs = 1_000L)

    @Test fun `a view is drawn after the debounce and an identical picture is not drawn twice`() = runTest {
        val draws = mutableListOf<String>()
        val layer = layer(draws)
        layer.onViewport(madrid, 12.0)
        advanceTimeBy(50); runCurrent()
        assertTrue(draws.isEmpty(), "still debouncing")
        advanceTimeBy(100); runCurrent()
        assertEquals(1, draws.size)
        assertTrue("\"level\":2" in draws[0])
        layer.onViewport(madrid, 12.0)
        advanceTimeBy(200); runCurrent()
        assertEquals(1, draws.size, "same picture")
    }

    @Test fun `off it asks nothing and draws nothing`() = runTest {
        on = false
        val draws = mutableListOf<String>()
        val layer = layer(draws)
        layer.onViewport(madrid, 12.0)
        advanceTimeBy(500); runCurrent()
        assertEquals(0, asked)
        assertTrue(draws.isEmpty())
    }

    @Test fun `too far out clears the drawing, and a failed read keeps it`() = runTest {
        val draws = mutableListOf<String>()
        val layer = layer(draws)
        layer.onViewport(madrid, 12.0)
        advanceTimeBy(200); runCurrent()
        assertEquals(1, draws.size)
        answer = null // offline for a moment: the picture stays
        layer.onViewport(madrid, 12.5)
        advanceTimeBy(200); runCurrent()
        assertEquals(1, draws.size)
        layer.onViewport(GeoBounds(36.0, -9.0, 44.0, 3.0), 6.0) // the whole country
        advanceTimeBy(200); runCurrent()
        assertEquals(2, draws.size)
        assertEquals(FlowGeoJson.EMPTY, draws.last())
    }

    @Test fun `switching it off clears the picture on the next look and the ticker refreshes while on`() = runTest {
        val draws = mutableListOf<String>()
        val layer = layer(draws)
        layer.start()
        layer.onViewport(madrid, 12.0)
        advanceTimeBy(200); runCurrent()
        assertEquals(1, asked)
        advanceTimeBy(1_100); runCurrent()
        assertTrue(asked >= 2, "looked again on the ticker")
        on = false
        advanceTimeBy(1_100); runCurrent()
        assertEquals(FlowGeoJson.EMPTY, draws.last(), "cleared once off")
        layer.stop()
    }
}
