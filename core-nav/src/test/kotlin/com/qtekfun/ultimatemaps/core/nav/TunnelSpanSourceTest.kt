package com.qtekfun.ultimatemaps.core.nav

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.routing.TunnelRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TunnelSpanSourceTest {
    private val geometry = RouteGeometry(RouteBuilder().lineTo(0.0, 2000.0).points.toList())  // 20 m per point, 101 points

    @Test fun rangesBecomeMetresAlongTheRoute() {
        val spans = RouteTunnelSpanSource(listOf(TunnelRange(10, 30), TunnelRange(50, 60))).spansFor(geometry)
        assertEquals(2, spans.size)
        assertEquals(200.0, spans[0].startMeters, 1.0)
        assertEquals(600.0, spans[0].endMeters, 1.0)
        assertTrue(spans.all { it.source == TunnelSource.ROUTE_FLAG && it.confidence == 1f })
    }

    @Test fun invalidRangesAreDropped() {
        val spans = RouteTunnelSpanSource(listOf(TunnelRange(-1, 5), TunnelRange(5, 5), TunnelRange(9, 3), TunnelRange(90, 500), TunnelRange(20, 40))).spansFor(geometry)
        assertEquals(1, spans.size)
        assertEquals(400.0, spans[0].startMeters, 1.0)
    }

    @Test fun noRangesMeansNoSpans() {
        assertTrue(RouteTunnelSpanSource(emptyList()).spansFor(geometry).isEmpty())
    }

    @Test fun fallbackIsUsedWhenThePrimaryKnowsNothing() {
        val learned = TunnelSpan(100.0, 300.0, TunnelSource.LEARNED, 0.5f)
        val spans = PreferredTunnelSpanSource(RouteTunnelSpanSource(emptyList()), TunnelSpanSource { listOf(learned) }).spansFor(geometry)
        assertEquals(listOf(learned), spans)
    }

    @Test fun aFailingSourceCountsAsKnowingNothing() {
        val boom = TunnelSpanSource { error("boom") }
        assertTrue(PreferredTunnelSpanSource(boom, boom).spansFor(geometry).isEmpty())
        val ok = TunnelSpan(100.0, 300.0)
        assertEquals(listOf(ok), PreferredTunnelSpanSource(boom, TunnelSpanSource { listOf(ok) }).spansFor(geometry))
    }

    @Test fun observationsAreForwardedToALearningFallback() {
        val store = LearnedTunnelStore()
        PreferredTunnelSpanSource(RouteTunnelSpanSource(emptyList()), store).onTunnelObserved(LatLon(40.0, -3.0), LatLon(40.01, -3.0))
        assertEquals(1, store.size)
        // A non-learning fallback just ignores it.
        PreferredTunnelSpanSource(RouteTunnelSpanSource(emptyList()), null).onTunnelObserved(LatLon(40.0, -3.0), LatLon(40.01, -3.0))
    }
}
