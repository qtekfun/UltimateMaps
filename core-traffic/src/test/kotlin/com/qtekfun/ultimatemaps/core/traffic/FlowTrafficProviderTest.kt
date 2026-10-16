package com.qtekfun.ultimatemaps.core.traffic

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlowTrafficProviderTest {
    private val tile = TileMath.tileOf(40.4168, -3.7038, RouteTraffic.TILE_ZOOM)
    private fun at(px: Int, py: Int) = TileMath.toLatLon(tile, px.toDouble(), py.toDouble(), 4096)

    // Nothing suspends in these tests, so the coroutine finishes before startCoroutine returns.
    private fun <T> run(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    private class Settings(override var enabled: Boolean, override var mapLayer: Boolean = true) : TrafficSettingsStore

    private class Keys(private var key: String?) : TrafficKeyStore {
        override fun read() = key
        override fun write(key: String) { this.key = key }
        override fun clear() { key = null }
    }

    // A road going north in the middle of the tile, 5 km, all of it at half the free-flow speed.
    private val route = listOf(at(2000, 3000), at(2000, 1500))
    private val jammed = MvtEncoder.tile(
        "Traffic flow", 4096,
        listOf(MvtEncoder.Feat(mapOf("relative_speed" to 0.5), listOf(MvtEncoder.Line(listOf(2000 to 3000, 2000 to 1500))))),
    )

    private var now = 0L
    private var calls = 0
    private fun provider(settings: Settings, keys: Keys, fetch: FlowTileFetch) =
        FlowTrafficProvider(settings, keys, fetch) { now }

    @Test fun `off or without a key it says so and never touches the network`() {
        val fetch = FlowTileFetch { _, _ -> calls++; jammed }
        assertEquals(
            TrafficOutcome.Unavailable(TrafficOutcome.Reason.DISABLED),
            run { provider(Settings(false), Keys("k"), fetch).estimate(route, 600.0) },
        )
        assertEquals(
            TrafficOutcome.Unavailable(TrafficOutcome.Reason.NO_KEY),
            run { provider(Settings(true), Keys(" "), fetch).estimate(route, 600.0) },
        )
        assertEquals(0, calls)
    }

    @Test fun `a jam doubles the time, the key is passed on and tiles are cached for three minutes`() {
        var seenKey: String? = null
        val p = provider(Settings(true), Keys("secret")) { _, k -> calls++; seenKey = k; jammed }
        val r = assertIs<TrafficOutcome.Ready>(run { p.estimate(route, 600.0) })
        assertEquals("secret", seenKey)
        assertEquals(1200.0, r.estimate.adjustedSeconds, 30.0)
        val first = calls
        now = 2 * 60_000L
        run { p.estimate(route, 600.0) }
        assertEquals(first, calls, "served from the cache")
        now = 4 * 60_000L
        run { p.estimate(route, 600.0) }
        assertTrue(calls > first, "expired after three minutes")
    }

    @Test fun `a bad key or the rate limit stop the requests and are reported`() {
        for ((failure, reason) in listOf(TileFailure.BAD_KEY to TrafficOutcome.Reason.BAD_KEY, TileFailure.RATE_LIMITED to TrafficOutcome.Reason.RATE_LIMITED)) {
            calls = 0
            val p = provider(Settings(true), Keys("k")) { _, _ -> calls++; throw TileFetchException(failure) }
            val far = listOf(LatLon(40.4168, -3.7038), LatLon(41.3874, 2.1686))
            assertEquals(TrafficOutcome.Unavailable(reason), run { p.estimate(far, 600.0) })
            assertEquals(1, calls, "no point in asking the other tiles")
        }
    }

    @Test fun `a tile that cannot be fetched does not hide the ones that can`() {
        val far = listOf(at(2000, 3000), LatLon(at(2000, 3000).lat + 0.9, at(2000, 3000).lon))
        val p = provider(Settings(true), Keys("k")) { t, _ -> if (t == tile) jammed else throw TileFetchException(TileFailure.NETWORK) }
        val r = assertIs<TrafficOutcome.Ready>(run { p.estimate(far, 3600.0) })
        assertTrue(r.estimate.coverage in 0.01..0.9, "only part of the route has data: ${r.estimate.coverage}")
    }
}

class FlowLinesTest {
    private val tile = TileMath.tileOf(40.4168, -3.7038, 10)

    @Test fun `levels follow the share of free flow and a closure wins`() {
        assertEquals(0, FlowLines.levelOf(0.9, false))
        assertEquals(1, FlowLines.levelOf(0.6, false))
        assertEquals(2, FlowLines.levelOf(0.2, false))
        assertEquals(3, FlowLines.levelOf(0.9, true))
    }

    @Test fun `lines come out worst last and no data is left out`() {
        val bytes = MvtEncoder.tile(
            "Traffic flow", 4096,
            listOf(
                MvtEncoder.Feat(mapOf("relative_speed" to 0.2), listOf(MvtEncoder.Line(listOf(100 to 100, 200 to 200)))),
                MvtEncoder.Feat(mapOf("relative_speed" to 1.0), listOf(MvtEncoder.Line(listOf(100 to 100, 200 to 200)))),
                MvtEncoder.Feat(mapOf("relative_speed" to 0.0), listOf(MvtEncoder.Line(listOf(300 to 300, 400 to 400)))),
            ),
        )
        assertEquals(listOf(0, 2), FlowLines.of(tile, Mvt.decode(bytes)).map { it.level })
    }

    @Test fun `the tiles of a view depend on the zoom and a view that is too wide gets none`() {
        val near = FlowLines.tilesFor(40.40, -3.72, 40.43, -3.69, 12.3)
        assertTrue(near.isNotEmpty() && near.all { it.z == 12 })
        assertTrue(FlowLines.tilesFor(40.0, -4.0, 41.0, -3.0, 8.0).isEmpty(), "too far out")
        assertTrue(FlowLines.tilesFor(30.0, -10.0, 50.0, 5.0, 9.0).isEmpty(), "too many tiles")
        assertEquals(14, FlowLines.tileZoom(17.8))
    }

    @Test fun `the provider gives the lines of the tiles it can read and caches them`() {
        var calls = 0
        val bytes = MvtEncoder.tile("Traffic flow", 4096, listOf(MvtEncoder.Feat(mapOf("relative_speed" to 0.3), listOf(MvtEncoder.Line(listOf(1 to 1, 9 to 9))))))
        val p = FlowTrafficProvider(object : TrafficSettingsStore { override var enabled = true; override var mapLayer = true }, object : TrafficKeyStore {
            override fun read() = "k"
            override fun write(key: String) {}
            override fun clear() {}
        }, FlowTileFetch { _, _ -> calls++; bytes }) { 0L }
        var out: List<FlowLine>? = null
        val r = kotlin.coroutines.Continuation<List<FlowLine>?>(kotlin.coroutines.EmptyCoroutineContext) { out = it.getOrThrow() }
        val run: suspend () -> List<FlowLine>? = { p.flowLines(listOf(tile)) }
        run.startCoroutine(r)
        assertEquals(1, out!!.size)
        run.startCoroutine(r)
        assertEquals(1, calls, "second ask served from the cache")
    }
}

class FlowLightweightTest {
    private val tile = TileId(12, 2000, 1500)

    private fun feat(category: String?, ratio: Double, pts: List<Pair<Int, Int>>): MvtEncoder.Feat {
        val props = buildMap<String, Any> { put("relative_speed", ratio); if (category != null) put("road_category", category) }
        return MvtEncoder.Feat(props, listOf(MvtEncoder.Line(pts)))
    }

    @Test fun `far out only the big roads, free flow only on them, and points a pixel apart are dropped`() {
        val bytes = MvtEncoder.tile(
            "Traffic flow", 4096,
            listOf(
                feat("motorway", 1.0, listOf(0 to 0, 2 to 1, 3 to 2, 100 to 100, 101 to 101, 400 to 400)), // free flow on a motorway: kept, simplified
                feat("secondary", 1.0, listOf(0 to 0, 500 to 500)), // free flow on a secondary: left out
                feat("secondary", 0.3, listOf(0 to 0, 500 to 500)), // a jam on a secondary at zoom 12: kept
                feat("tertiary", 0.3, listOf(0 to 0, 500 to 500)), // not drawn at zoom 12
                feat("service", 0.2, listOf(0 to 0, 500 to 500)), // never
                feat(null, 0.2, listOf(0 to 0, 500 to 500)), // no category: kept
            ),
        )
        val lines = FlowLines.of(tile, Mvt.decode(bytes))
        assertEquals(listOf(0, 2, 2), lines.map { it.level })
        assertEquals(3, lines.first().points.size, "0,0 .. 100,100 .. 400,400: the near points were dropped")
    }

    @Test fun `a view never carries more than the cap and keeps the worst`() {
        val many = List(FlowLines.MAX_LINES + 500) { FlowLine(if (it < 100) 3 else 0, listOf(LatLon(40.0, -3.0), LatLon(40.001, -3.001))) }
        val limited = FlowLines.limit(many)
        assertEquals(FlowLines.MAX_LINES, limited.size)
        assertEquals(100, limited.count { it.level == 3 })
        assertEquals(3, limited.last().level)
    }

    @Test fun `the geojson is valid and compact`() {
        assertEquals(FlowGeoJson.EMPTY, FlowGeoJson.of(emptyList()))
        val json = FlowGeoJson.of(listOf(FlowLine(2, listOf(LatLon(40.123456789, -3.987654321), LatLon(40.2, -3.9)))))
        assertEquals("""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"level":2},"geometry":{"type":"LineString","coordinates":[[-3.98765,40.12346],[-3.9,40.2]]}}]}""", json)
        val big = List(FlowLines.MAX_LINES) { FlowLine(it % 4, List(20) { i -> LatLon(40.0 + i * 1e-4, -3.0 + it * 1e-5) }) }
        assertTrue(FlowGeoJson.of(big).length > 100_000)
    }
}
