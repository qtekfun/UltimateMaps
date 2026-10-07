package com.qtekfun.mapas.map

import com.qtekfun.mapas.core.map.MapTheme
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MultiRegionStyleTest {
    private fun template(theme: MapTheme) = File("src/main/assets", StyleTemplate.assetName(theme)).readText()

    private fun build(n: Int, theme: MapTheme = MapTheme.LIGHT) =
        MultiRegionStyle.build(template(theme), "/data/files/map", (0 until n).map { "/data/files/regions/r$it/r$it.pmtiles" })

    private fun check(r: MultiRegionStyle.Result) {
        val style = JSONObject(r.json)
        val sources = style.getJSONObject("sources")
        val layers = style.getJSONArray("layers")
        val ids = (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }
        assertEquals(ids.size, ids.toSet().size, "layer ids must be unique")
        val used = mutableSetOf<String>()
        for (i in ids.indices) {
            val l = layers.getJSONObject(i)
            if (l.has("source")) {
                assertTrue(sources.has(l.getString("source")), "dangling source in ${l.getString("id")}")
                used += l.getString("source")
            }
        }
        assertEquals(used, sources.keys().asSequence().toSet(), "every source must be used")
        assertFalse(r.json.contains("@MAPDIR@") || r.json.contains("@PMTILES@"))
        assertFalse(r.json.contains("http://") || r.json.contains("https://protomaps"))
    }

    @Test
    fun noRegionsGivesBackgroundOnlyWithoutSources() {
        val r = build(0)
        assertEquals(0, r.sources)
        assertEquals(1, r.layers)
        assertEquals(0, JSONObject(r.json).getJSONObject("sources").length())
        assertFalse(r.json.contains("pmtiles://"))
    }

    @Test
    fun oneRegionKeepsTheOriginalLayers() {
        for (theme in MapTheme.entries) {
            val r = build(1, theme)
            check(r)
            assertEquals(r.templateLayers, r.layers)
            val first = JSONObject(r.json).getJSONArray("layers").getJSONObject(1)
            assertFalse(first.getString("id").contains("@"))
            assertEquals("protomaps-0", first.getString("source"))
            assertEquals("pmtiles://file:///data/files/regions/r0/r0.pmtiles", JSONObject(r.json).getJSONObject("sources").getJSONObject("protomaps-0").getString("url"))
        }
    }

    @Test
    fun manyRegionsDuplicateLayersLayerMajorWithUniqueIds() {
        val r = build(5)
        check(r)
        assertEquals(1 + (r.templateLayers - 1) * 5, r.layers)
        assertEquals(5, r.sources)
        val layers = JSONObject(r.json).getJSONArray("layers")
        // layer-major: the five copies of the first sourced layer are adjacent, in region order
        assertEquals((0 until 5).map { "protomaps-$it" }, (1..5).map { layers.getJSONObject(it).getString("source") })
        assertEquals("earth@3", layers.getJSONObject(4).getString("id"))
    }

    @Test
    fun capsSourcesReportsSkippedAndIgnoresDuplicatePaths() {
        val paths = (0 until MultiRegionStyle.MAX_SOURCES + 3).map { "/m/r$it.pmtiles" }
        val r = MultiRegionStyle.build(template(MapTheme.DARK), "/m", paths + paths[0])
        check(r)
        assertEquals(MultiRegionStyle.MAX_SOURCES, r.sources)
        assertEquals(3, r.skipped)
    }

    @Test
    fun pathsAreEscapedAndLayerCountScalesLinearly() {
        val r = MultiRegionStyle.build(template(MapTheme.LIGHT), "/m", listOf("/a\"b\\c.pmtiles"))
        val url = JSONObject(r.json).getJSONObject("sources").getJSONObject("protomaps-0").getString("url")
        assertEquals("pmtiles://file:///a\"b\\c.pmtiles", url)
        assertEquals(1 + 70 * 25, build(25).layers)
    }
}
