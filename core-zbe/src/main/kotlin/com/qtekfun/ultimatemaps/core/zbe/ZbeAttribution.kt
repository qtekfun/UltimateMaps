package com.qtekfun.ultimatemaps.core.zbe

/**
 * Credit of the low-emission-zone layer. The data is OpenStreetMap, ODbL 1.0: the derived database keeps attribution and
 * share-alike (the file in the data release is that derived database). The app has no vehicle label or sticker logic: it
 * only knows "zone", never whether a given vehicle is allowed or banned.
 */
object ZbeAttribution {
    const val OSM_EN = "Low-emission zones from OpenStreetMap, © OpenStreetMap contributors (ODbL)."
    const val OSM_ES = "Zonas de bajas emisiones de OpenStreetMap, © colaboradores de OpenStreetMap (ODbL)."

    fun text(english: Boolean): String = if (english) OSM_EN else OSM_ES
}
