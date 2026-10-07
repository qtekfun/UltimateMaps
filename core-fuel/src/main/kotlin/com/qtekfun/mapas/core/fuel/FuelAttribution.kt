package com.qtekfun.mapas.core.fuel

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Attribution of the price source, to show next to prices (station card, Settings). Conditions we follow: cite the
 * source, show when the data was downloaded, do not alter the meaning of the data. Never call the price "official":
 * it is the price the Ministry publishes. The reuse licence itself is NOT verified (docs/phase7/fuel-implementation.md).
 */
object FuelAttribution {
    const val SOURCE_ES = "Datos: Ministerio para la Transición Ecológica y el Reto Demográfico (Geoportal de Hidrocarburos), " +
        "reutilizados conforme a la Ley 37/2007. Información no oficial; comprueba el precio en el surtidor."
    const val SOURCE_EN = "Data: Ministerio para la Transición Ecológica y el Reto Demográfico (Geoportal de Hidrocarburos), " +
        "reused under Law 37/2007. Unofficial information; check the price at the pump."

    /** The fixed text plus the download date ("Descargado: 07/10/2026 12:30"), or "not downloaded yet" when [lastUpdateMillis] is null. */
    fun text(lastUpdateMillis: Long?, english: Boolean = false, zone: ZoneId = ZoneId.systemDefault()): String {
        val base = if (english) SOURCE_EN else SOURCE_ES
        val date = lastUpdateMillis?.let {
            Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern(if (english) "yyyy-MM-dd HH:mm" else "dd/MM/yyyy HH:mm"))
        }
        val tail = when {
            english -> if (date != null) "Downloaded: $date." else "Not downloaded yet."
            else -> if (date != null) "Descargado: $date." else "Aún sin descargar."
        }
        return "$base $tail"
    }
}
