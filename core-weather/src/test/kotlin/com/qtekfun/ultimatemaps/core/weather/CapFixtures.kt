package com.qtekfun.ultimatemaps.core.weather

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * CAP 1.2 documents written by hand in the structure AEMET publishes (one `<alert>` per warning zone, one `<info>` per
 * language, level in the `AEMET-Meteoalerta nivel` parameter, zone in `AEMET-Meteoalerta zona`, polygon as `lat,lon` pairs).
 * The coordinates are a made-up square around Valencia city (39.4..39.6 N, -0.5..-0.3 E).
 */
object CapFixtures {
    const val SQUARE = "39.40,-0.50 39.40,-0.30 39.60,-0.30 39.60,-0.50 39.40,-0.50"

    fun info(
        language: String = "es-ES",
        event: String = "Vientos",
        level: String = "naranja",
        severity: String = "Severe",
        onset: String = "2026-10-08T12:00:00+02:00",
        expires: String = "2026-10-08T18:00:00+02:00",
        area: String = "Litoral norte de Valencia",
        polygon: String? = SQUARE,
        zone: String = "774601",
    ) = """
  <info>
    <language>$language</language>
    <category>Met</category>
    <event>$event</event>
    <responseType>Prepare</responseType>
    <urgency>Immediate</urgency>
    <severity>$severity</severity>
    <certainty>Likely</certainty>
    <effective>2026-10-08T09:00:00+02:00</effective>
    <onset>$onset</onset>
    <expires>$expires</expires>
    <senderName>AEMET - Agencia Estatal de Meteorologia</senderName>
    <headline>Aviso de $event nivel $level</headline>
    <description>Rachas de viento de 80 km/h &amp; mar gruesa.</description>
    <instruction>Evite zonas expuestas.</instruction>
    <web>https://www.aemet.es/es/eltiempo/prediccion/avisos</web>
    <contact>AEMET</contact>
    <parameter><valueName>AEMET-Meteoalerta nivel</valueName><value>$level</value></parameter>
    <parameter><valueName>AEMET-Meteoalerta fenomeno</valueName><value>VI;Vientos</value></parameter>
    <parameter><valueName>AEMET-Meteoalerta parametro</valueName><value>VI;Rachas;80km/h</value></parameter>
    <area>
      <areaDesc>$area</areaDesc>
      ${if (polygon != null) "<polygon>$polygon</polygon>" else ""}
      <geocode><valueName>AEMET-Meteoalerta zona</valueName><value>$zone</value></geocode>
    </area>
  </info>"""

    /** One CAP alert document with the given `<info>` blocks. */
    fun doc(
        id: String = "2.49.0.1.724.0.2026100809000.774601",
        msgType: String = "Alert",
        status: String = "Actual",
        infos: List<String> = listOf(info(), info(language = "en-GB", event = "Wind")),
    ): String = """<?xml version="1.0" encoding="UTF-8"?>
<alert xmlns="urn:oasis:names:tc:emergency:cap:1.2">
  <identifier>$id</identifier>
  <sender>webmaster@aemet.es</sender>
  <sent>2026-10-08T09:00:00+02:00</sent>
  <status>$status</status>
  <msgType>$msgType</msgType>
  <scope>Public</scope>
${infos.joinToString("\n")}
</alert>"""

    /** A ustar archive of [files] (name to content). */
    fun tar(files: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, data) in files) {
            val header = ByteArray(512)
            name.toByteArray().copyInto(header)
            "0000644".toByteArray().copyInto(header, 100)
            "0000000".toByteArray().copyInto(header, 108)
            "0000000".toByteArray().copyInto(header, 116)
            String.format("%011o", data.size).toByteArray().copyInto(header, 124)
            "00000000000".toByteArray().copyInto(header, 136)
            header[156] = '0'.code.toByte()
            "ustar".toByteArray().copyInto(header, 257)
            "00".toByteArray().copyInto(header, 263)
            out.write(header)
            out.write(data)
            out.write(ByteArray((512 - data.size % 512) % 512))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }
}
