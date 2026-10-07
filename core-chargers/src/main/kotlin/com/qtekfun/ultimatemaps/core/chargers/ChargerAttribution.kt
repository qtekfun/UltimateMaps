package com.qtekfun.ultimatemaps.core.chargers

/**
 * Credit for the charger layer. The data is OpenStreetMap, ODbL 1.0: the derived database keeps attribution and
 * share-alike (the file in the data release is that derived database).
 */
object ChargerAttribution {
    const val OSM_EN = "EV charging stations from OpenStreetMap, © OpenStreetMap contributors (ODbL)."
    const val OSM_ES = "Puntos de carga de OpenStreetMap, © colaboradores de OpenStreetMap (ODbL)."

    fun text(english: Boolean): String = if (english) OSM_EN else OSM_ES
}
