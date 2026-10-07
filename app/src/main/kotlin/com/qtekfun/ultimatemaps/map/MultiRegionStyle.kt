package com.qtekfun.ultimatemaps.map

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the style for every installed region from the packaged single-source template (pure, JVM-testable).
 *
 * One PMTiles per region, clipped with CoMaps polygons, so neighbours overlap at the border. Each region gets its
 * own vector source (`protomaps-<i>`) and a copy of every layer pointing to it. Copies are placed layer-major
 * (layer 1 of every region, then layer 2 ...) so z-order across regions stays correct (casings under roads of the
 * neighbour). Copy 0 keeps the original layer id; the others get `<id>@<i>`. Layers without a source
 * (`background`) are emitted once. With no region the style has no sources at all (background only): it never
 * references a file that does not exist.
 *
 * Border overlap: opaque fills and lines repaint identical pixels (harmless). Translucent layers (`buildings`
 * 0.5, `landuse_urban_green` 0.7, `roads_rail` 0.5) look denser in the overlap strip. Duplicate labels collide
 * with each other (same text, same place, no allow-overlap) so only one is placed.
 */
object MultiRegionStyle {
    /** Hard cap on sources (Spain has 25 regions); extra regions are not drawn and the result says so. */
    const val MAX_SOURCES = 25
    const val SOURCE_PREFIX = "protomaps-"
    private const val TEMPLATE_SOURCE = "protomaps"

    data class Result(
        val json: String,
        val sources: Int,
        val layers: Int,
        val skipped: Int,
        val templateLayers: Int,
    )

    fun sourceId(index: Int) = "$SOURCE_PREFIX$index"

    fun layerId(original: String, index: Int) = if (index == 0) original else "$original@$index"

    fun build(template: String, mapDir: String, pmtilesPaths: List<String>): Result {
        val style = JSONObject(template.replace(StyleTemplate.DIR_MARKER, jsonEscape(mapDir)))
        val proto = style.getJSONObject("sources").getJSONObject(TEMPLATE_SOURCE)
        val distinct = pmtilesPaths.distinct()
        val paths = distinct.take(MAX_SOURCES)
        val sources = JSONObject()
        paths.forEachIndexed { i, path ->
            val s = JSONObject(proto.toString())
            s.put("url", "pmtiles://file://$path")
            sources.put(sourceId(i), s)
        }
        val templateLayers = style.getJSONArray("layers")
        val layers = JSONArray()
        for (n in 0 until templateLayers.length()) {
            val layer = templateLayers.getJSONObject(n)
            if (!layer.has("source")) {
                layers.put(layer)
                continue
            }
            for (i in paths.indices) {
                val copy = JSONObject(layer.toString())
                copy.put("id", layerId(layer.getString("id"), i))
                copy.put("source", sourceId(i))
                layers.put(copy)
            }
        }
        style.put("sources", sources)
        style.put("layers", layers)
        return Result(style.toString(), paths.size, layers.length(), distinct.size - paths.size, templateLayers.length())
    }

    private fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
