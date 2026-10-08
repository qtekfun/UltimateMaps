package com.qtekfun.ultimatemaps.core.routes

/**
 * Credit for the trail overlay. The routes are OpenStreetMap route relations (ODbL 1.0): the simplified file in the data
 * release is a derived database and keeps attribution and share-alike. Waymarked Trails is only mentioned as inspiration
 * in the docs; none of its code or rendering is used.
 */
object RouteAttribution {
    const val OSM_EN = "Hiking and cycling routes from OpenStreetMap route relations, © OpenStreetMap contributors (ODbL), simplified."
    const val OSM_ES = "Rutas de senderismo y ciclismo de relaciones de ruta de OpenStreetMap, © colaboradores de OpenStreetMap (ODbL), simplificadas."

    fun text(english: Boolean): String = if (english) OSM_EN else OSM_ES
}
