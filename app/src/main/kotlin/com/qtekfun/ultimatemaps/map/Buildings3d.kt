package com.qtekfun.ultimatemaps.map

/**
 * The 3D buildings of the navigation's 3D view, as plain data (pure, JVM-testable); [MapLibreEngine] turns each
 * [Spec] into a `fill-extrusion` layer while navigating in 3D and removes them afterwards. The normal style and its
 * layer count never change.
 *
 * Tiles: the Protomaps `buildings` layer (the same `source-layer` the flat `buildings` fill of the style uses),
 * with `kind` building or building_part and, when OSM has them, numeric `height` and `min_height` in metres.
 * Where `height` is missing the building gets [FALLBACK_HEIGHT_METERS] (about three storeys), and `min_height`
 * defaults to 0. Whether real tiles carry these properties for Spanish regions has not been checked on a device.
 *
 * One layer per vector source ([MultiRegionStyle] makes one source per installed region), ids unique per source.
 * They go right below the first symbol layer of the style: above every road and fill (so a building hides the road
 * behind it), below labels, icons and the app's own overlays (so the route line stays on top of the buildings).
 */
object Buildings3d {
    const val LAYER_PREFIX = "mapas-3d-buildings"
    const val SOURCE_LAYER = "buildings"

    /** Below this zoom the extrusions are not drawn: from far away they only cost. */
    const val MIN_ZOOM = 15f
    const val FALLBACK_HEIGHT_METERS = 9f
    const val OPACITY = 0.92f

    /** Soft, low-contrast colours (a little darker than the flat buildings of the style, no vertical gradient). */
    const val COLOR_LIGHT = 0xFFD8D4CC.toInt()
    const val COLOR_DARK = 0xFF3B3E45.toInt()

    /** Kinds drawn: the same as the flat `buildings` layer. */
    val KINDS = listOf("building", "building_part")

    /** One extrusion layer. */
    data class Spec(
        val id: String,
        val sourceId: String,
        val minZoom: Float,
        val fallbackHeightMeters: Float,
        val color: Int,
        val opacity: Float,
    )

    /** A layer of the loaded style, as far as the anchor choice cares. */
    data class LayerInfo(val id: String, val isSymbol: Boolean)

    fun layerId(sourceId: String) = "$LAYER_PREFIX-$sourceId"

    fun isOurs(layerId: String) = layerId.startsWith(LAYER_PREFIX)

    /** The vector sources the style generated for the installed regions, in order. */
    fun regionSources(allSourceIds: List<String>) = allSourceIds.filter { it.startsWith(MultiRegionStyle.SOURCE_PREFIX) }.distinct()

    /** One spec per region source (none when there is no region). */
    fun specs(allSourceIds: List<String>, dark: Boolean): List<Spec> =
        regionSources(allSourceIds).map { Spec(layerId(it), it, MIN_ZOOM, FALLBACK_HEIGHT_METERS, if (dark) COLOR_DARK else COLOR_LIGHT, OPACITY) }

    /** The layer the extrusions go below: the first symbol layer of the style; null (add on top) when there is none. */
    fun anchorLayerId(layers: List<LayerInfo>): String? = layers.firstOrNull { it.isSymbol && !isOurs(it.id) }?.id
}
