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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 3D building layers as data: one per region source, unique ids, below the labels, and the style itself untouched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Buildings3dTest {
    private fun template(theme: MapTheme) = File("src/main/assets", StyleTemplate.assetName(theme)).readText()

    private fun style(n: Int, theme: MapTheme = MapTheme.LIGHT) =
        MultiRegionStyle.build(template(theme), "/data/files/map", (0 until n).map { "/data/files/regions/r$it/r$it.pmtiles" })

    private fun sourceIds(r: MultiRegionStyle.Result) = JSONObject(r.json).getJSONObject("sources").keys().asSequence().toList()

    private fun layerInfos(r: MultiRegionStyle.Result): List<Buildings3d.LayerInfo> {
        val layers = JSONObject(r.json).getJSONArray("layers")
        return (0 until layers.length()).map { layers.getJSONObject(it).let { l -> Buildings3d.LayerInfo(l.getString("id"), l.getString("type") == "symbol") } }
    }

    @Test fun `one extrusion layer per region source with unique ids`() {
        for (n in listOf(1, 2, 5, MultiRegionStyle.MAX_SOURCES)) {
            val r = style(n)
            val specs = Buildings3d.specs(sourceIds(r), dark = false)
            assertEquals(n, specs.size)
            assertEquals(n, specs.map { it.id }.toSet().size, "ids must be unique")
            assertEquals(sourceIds(r).toSet(), specs.map { it.sourceId }.toSet())
            val styleIds = layerInfos(r).map { it.id }.toSet()
            assertTrue(specs.none { it.id in styleIds }, "must not collide with a style layer")
        }
    }

    @Test fun `no region means no extrusion layers`() {
        assertTrue(Buildings3d.specs(sourceIds(style(0)), dark = true).isEmpty())
        assertTrue(Buildings3d.specs(emptyList(), dark = false).isEmpty())
    }

    @Test fun `only the region sources get a layer, not the app's own sources`() {
        val specs = Buildings3d.specs(listOf("protomaps-0", "mapas-route-src", "mapas-user-src", "protomaps-1", "protomaps-0"), dark = false)
        assertEquals(listOf("protomaps-0", "protomaps-1"), specs.map { it.sourceId })
    }

    @Test fun `layer ids are derived from the source and recognised as ours`() {
        assertEquals("mapas-3d-buildings-protomaps-3", Buildings3d.layerId("protomaps-3"))
        assertTrue(Buildings3d.isOurs(Buildings3d.layerId("protomaps-3")))
        assertFalse(Buildings3d.isOurs("buildings"))
        assertFalse(Buildings3d.isOurs("buildings@2"))
    }

    @Test fun `from zoom 15, a sensible fallback height, soft opaque colours for both themes`() {
        val light = Buildings3d.specs(listOf("protomaps-0"), dark = false).single()
        val dark = Buildings3d.specs(listOf("protomaps-0"), dark = true).single()
        assertEquals(15f, light.minZoom)
        assertTrue(light.fallbackHeightMeters in 6f..12f)
        assertNotEquals(light.color, dark.color)
        assertEquals(Buildings3d.COLOR_LIGHT, light.color)
        assertEquals(Buildings3d.COLOR_DARK, dark.color)
        assertTrue(light.opacity in 0.8f..1f)
    }

    @Test fun `the layer reads the buildings source layer of the style`() {
        // The flat buildings layer of the generated style and the extrusion must point at the same tiles.
        for (theme in MapTheme.entries) {
            val layers = JSONObject(template(theme)).getJSONArray("layers")
            val flat = (0 until layers.length()).map { layers.getJSONObject(it) }.single { it.getString("id") == "buildings" }
            assertEquals(Buildings3d.SOURCE_LAYER, flat.getString("source-layer"))
            val kinds = flat.getJSONArray("filter").let { f -> (2 until f.length()).map { f.getString(it) } }
            assertEquals(kinds, Buildings3d.KINDS)
        }
    }

    @Test fun `the anchor is the first symbol layer of the style, so roads are below and labels and the route above`() {
        for (n in listOf(1, 3)) for (theme in MapTheme.entries) {
            val infos = layerInfos(style(n, theme))
            val anchor = Buildings3d.anchorLayerId(infos)
            assertTrue(anchor != null)
            val at = infos.indexOfFirst { it.id == anchor }
            assertTrue(infos[at].isSymbol)
            assertTrue(infos.take(at).none { it.isSymbol })
            val roads = infos.withIndex().filter { it.value.id.startsWith("roads_") && !it.value.isSymbol }
            assertTrue(roads.isNotEmpty())
            assertTrue(at > infos.indexOfFirst { it.id == "buildings" }, "extrusions go above the flat buildings")
        }
    }

    @Test fun `the anchor ignores our own layers and is null without symbols`() {
        assertNull(Buildings3d.anchorLayerId(emptyList()))
        assertNull(Buildings3d.anchorLayerId(listOf(Buildings3d.LayerInfo("a", false))))
        assertEquals("labels", Buildings3d.anchorLayerId(listOf(Buildings3d.LayerInfo("a", false), Buildings3d.LayerInfo("labels", true), Buildings3d.LayerInfo("more", true))))
        assertEquals("labels", Buildings3d.anchorLayerId(listOf(Buildings3d.LayerInfo(Buildings3d.layerId("protomaps-0"), true), Buildings3d.LayerInfo("labels", true))))
    }

    @Test fun `adding and removing the extrusions leaves the style layer count and ids alone`() {
        // The engine adds the specs to a loaded style and removes everything with our prefix: the template stays as is.
        val r = style(3)
        val before = layerInfos(r).map { it.id }
        val specs = Buildings3d.specs(sourceIds(r), dark = false)
        val withExtrusions = before + specs.map { it.id }
        assertEquals(before.size + 3, withExtrusions.size)
        assertEquals(withExtrusions.size, withExtrusions.toSet().size)
        assertEquals(before, withExtrusions.filterNot(Buildings3d::isOurs))
    }
}
