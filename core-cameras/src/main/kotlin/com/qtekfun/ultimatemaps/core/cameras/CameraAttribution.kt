package com.qtekfun.ultimatemaps.core.cameras

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Attribution to show next to the data (Settings, cards). The DGT publishes its datasets under Creative Commons
 * Attribution (the NAP dataset pages say "Creative Commons Attribution"; the exact version and the DGT legal notice at
 * https://www.dgt.es/contenido/aviso-legal/ were NOT verified). OpenStreetMap data is ODbL 1.0 and needs the
 * "(c) OpenStreetMap contributors" credit the app already shows on the map.
 */
object CameraAttribution {
    const val DGT_EN = "Data: Dirección General de Tráfico (nap.dgt.es), CC BY."
    const val OSM_EN = "Speed cameras also from OpenStreetMap, © OpenStreetMap contributors (ODbL)."
    const val DGT_ES = "Datos: Dirección General de Tráfico (nap.dgt.es), CC BY."
    const val OSM_ES = "Radares también de OpenStreetMap, © colaboradores de OpenStreetMap (ODbL)."

    /** Credit for a camera file whose [sourceFlags] say which sources it holds. */
    fun forCameras(sourceFlags: Int, english: Boolean): String = buildList {
        if (sourceFlags and CameraSources.DGT != 0) add(if (english) DGT_EN else DGT_ES)
        if (sourceFlags and CameraSources.OSM != 0) add(if (english) OSM_EN else OSM_ES)
    }.joinToString(" ")

    /** Credit for the live incident feed. */
    fun forIncidents(english: Boolean): String = if (english) DGT_EN else DGT_ES

    /**
     * The date of [millis] in the style of [locale]: "07/10/2026 12:30" for Spanish, "2026-10-07 12:30" for English (the
     * formats the app always used), the locale's own short date and time for any other language.
     */
    fun dateText(millis: Long, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String {
        val moment = Instant.ofEpochMilli(millis).atZone(zone)
        return when (locale.language) {
            "es" -> dateText(millis, english = false, zone = zone)
            "en" -> dateText(millis, english = true, zone = zone)
            else -> moment.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale))
        }
    }

    /** "2026-10-07 12:30" / "07/10/2026 12:30" of an epoch-millis time. */
    fun dateText(millis: Long, english: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern(if (english) "yyyy-MM-dd HH:mm" else "dd/MM/yyyy HH:mm"))
}
