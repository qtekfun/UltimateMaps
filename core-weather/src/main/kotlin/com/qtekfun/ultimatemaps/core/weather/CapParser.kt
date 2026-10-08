package com.qtekfun.ultimatemaps.core.weather

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

class CapParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One `<area>` of a CAP `<info>`. */
class CapArea(val description: String, val polygons: List<WarningPolygon>, val zoneCode: String?)

/** One `<info>` block: the message in one language. */
class CapInfo(
    val language: String,
    val event: String,
    val level: AlertLevel?,
    val onsetMillis: Long?,
    val effectiveMillis: Long?,
    val expiresMillis: Long?,
    val headline: String,
    val description: String,
    val instruction: String,
    val areas: List<CapArea>,
)

/** A CAP 1.2 `<alert>` as AEMET publishes it (one file per warning zone, one `<info>` per language). */
class CapAlert(
    val identifier: String,
    val status: String,
    val msgType: String,
    val sentMillis: Long?,
    val infos: List<CapInfo>,
)

/**
 * Parser of AEMET's CAP 1.2 weather warnings (`avisos_cap`). Pure JVM/Android (SAX); a document type declaration is refused
 * (no entity expansion) and every text field is capped. Unknown elements are ignored.
 *
 * What it reads (structure confirmed on AEMET's published CAP files, `aemet.es/documentos_d/eltiempo/prediccion/avisos/cap/`):
 * `info/parameter` named `AEMET-Meteoalerta nivel` carries the colour (`amarillo`, `naranja`, `rojo`, possibly with a number
 * before a `;`); `area/polygon` is a list of `lat,lon` pairs separated by spaces; `area/geocode` named
 * `AEMET-Meteoalerta zona` is AEMET's own zone code (kept, not needed for matching because the polygon is given).
 */
object CapParser {
    const val MAX_TEXT = 8_000
    private const val MAX_POLYGON_POINTS = 20_000
    private const val MAX_AREAS = 200

    fun parse(xml: ByteArray): CapAlert = parse(InputSource(ByteArrayInputStream(xml)))

    fun parse(xml: String): CapAlert = parse(InputSource(StringReader(xml)))

    private fun parse(source: InputSource): CapAlert {
        val handler = Handler()
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isValidating = false
            // Best effort: not every platform parser knows every feature; none of them loads external entities by default.
            for ((feature, value) in listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
            )) {
                try { factory.setFeature(feature, value) } catch (_: Exception) { /* unsupported here */ }
            }
            val parser = factory.newSAXParser()
            parser.parse(source, handler)
        } catch (e: SAXException) {
            throw CapParseException("not a CAP document: ${e.message}", e)
        } catch (e: java.io.IOException) {
            throw CapParseException("unreadable CAP document", e)
        }
        return handler.result() ?: throw CapParseException("no <alert> element")
    }

    /** `lat,lon lat,lon ...`; the closing vertex (a repeat of the first) is dropped. Null when it is not a usable ring. */
    internal fun polygon(text: String): WarningPolygon? {
        val out = ArrayList<Double>()
        for (pair in text.trim().split(Regex("\\s+"))) {
            if (pair.isEmpty()) continue
            val parts = pair.split(',')
            if (parts.size < 2) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lon = parts[1].toDoubleOrNull() ?: return null
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            out += lat; out += lon
            if (out.size > 2 * MAX_POLYGON_POINTS) return null
        }
        if (out.size >= 8 && out[0] == out[out.size - 2] && out[1] == out[out.size - 1]) {
            out.removeAt(out.size - 1); out.removeAt(out.size - 1)
        }
        return if (out.size >= 6) WarningPolygon(out.toDoubleArray()) else null
    }

    /** The level from the AEMET parameter text or, failing that, from the CAP severity. Green and unknown give null. */
    internal fun level(parameter: String?, severity: String?): AlertLevel? {
        if (parameter != null) {
            for (token in parameter.lowercase(Locale.ROOT).split(';', ',', ' ')) {
                when (token.trim()) {
                    "amarillo", "yellow" -> return AlertLevel.YELLOW
                    "naranja", "orange" -> return AlertLevel.ORANGE
                    "rojo", "red" -> return AlertLevel.RED
                    "verde", "green" -> return null
                }
            }
        }
        return when (severity?.trim()?.lowercase(Locale.ROOT)) {
            "moderate" -> AlertLevel.YELLOW
            "severe" -> AlertLevel.ORANGE
            "extreme" -> AlertLevel.RED
            else -> null
        }
    }

    internal fun time(text: String?): Long? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        return try { OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (_: DateTimeParseException) { null }
    }

    private class InfoAcc {
        var language = ""; var event = ""; var severity: String? = null; var levelParam: String? = null
        var onset: Long? = null; var effective: Long? = null; var expires: Long? = null
        var headline = ""; var description = ""; var instruction = ""
        val areas = ArrayList<CapArea>()
        fun build() = CapInfo(language, event, level(levelParam, severity), onset, effective, expires, headline, description, instruction, areas)
    }

    private class AreaAcc {
        var description = ""; var zone: String? = null
        val polygons = ArrayList<WarningPolygon>()
        fun build() = CapArea(description, polygons, zone)
    }

    private class Handler : DefaultHandler() {
        private val path = ArrayList<String>()
        private val text = StringBuilder()
        private var seenAlert = false
        private var identifier = ""; private var status = ""; private var msgType = ""; private var sent: Long? = null
        private val infos = ArrayList<CapInfo>()
        private var info: InfoAcc? = null
        private var area: AreaAcc? = null
        private var paramName = ""; private var paramValue = ""; private var geoName = ""; private var geoValue = ""

        fun result(): CapAlert? = if (seenAlert) CapAlert(identifier, status, msgType, sent, infos) else null

        private fun local(qName: String) = qName.substringAfter(':')

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
            val name = local(qName)
            if (path.isEmpty() && name != "alert") throw SAXException("root element is $name, not alert")
            path += name
            text.setLength(0)
            when (name) {
                "alert" -> seenAlert = true
                "info" -> if (path.size == 2) info = InfoAcc()
                "area" -> if (info != null && (info?.areas?.size ?: 0) < MAX_AREAS) area = AreaAcc()
                "parameter" -> { paramName = ""; paramValue = "" }
                "geocode" -> { geoName = ""; geoValue = "" }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (text.length < MAX_TEXT) text.append(ch, start, minOf(length, MAX_TEXT - text.length))
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val name = local(qName)
            val value = text.toString().trim()
            val inInfo = info
            val inArea = area
            when (name) {
                "identifier" -> if (path.size == 2) identifier = value
                "status" -> if (path.size == 2) status = value
                "msgType" -> if (path.size == 2) msgType = value
                "sent" -> if (path.size == 2) sent = time(value)
                "language" -> inInfo?.language = value
                "event" -> inInfo?.event = value
                "severity" -> inInfo?.severity = value
                "onset" -> inInfo?.onset = time(value)
                "effective" -> inInfo?.effective = time(value)
                "expires" -> inInfo?.expires = time(value)
                "headline" -> inInfo?.headline = value
                "description" -> inInfo?.description = value
                "instruction" -> inInfo?.instruction = value
                "valueName" -> if (path.getOrNull(path.size - 2) == "parameter") paramName = value else geoName = value
                "value" -> if (path.getOrNull(path.size - 2) == "parameter") paramValue = value else geoValue = value
                "parameter" -> if (inInfo != null && paramName.equals("AEMET-Meteoalerta nivel", ignoreCase = true)) inInfo.levelParam = paramValue
                "geocode" -> if (inArea != null && geoName.equals("AEMET-Meteoalerta zona", ignoreCase = true)) inArea.zone = geoValue
                "areaDesc" -> inArea?.description = value
                "polygon" -> if (inArea != null) polygon(value)?.let { inArea.polygons += it }
                "area" -> if (inInfo != null && inArea != null) { inInfo.areas += inArea.build(); area = null }
                "info" -> if (path.size == 2 && inInfo != null) { infos += inInfo.build(); info = null }
            }
            if (path.isNotEmpty()) path.removeAt(path.size - 1)
            text.setLength(0)
        }
    }

    /**
     * The warnings of [alerts] to show, one per (alert, area), each in the best available language: [language] if the alert has
     * it, else Spanish, else the first `<info>`. Cancelled, test and exercise messages and level-less (green) infos are left
     * out. Warnings whose [CapInfo.expiresMillis] is before [nowMillis] are dropped.
     */
    fun toWarnings(alerts: List<CapAlert>, language: String, nowMillis: Long): List<WeatherWarning> {
        val out = ArrayList<WeatherWarning>()
        for (alert in alerts) {
            if (alert.msgType.equals("Cancel", ignoreCase = true)) continue
            if (alert.status.isNotEmpty() && !alert.status.equals("Actual", ignoreCase = true)) continue
            val info = pick(alert.infos, language) ?: continue
            val level = info.level ?: continue
            if (info.expiresMillis != null && info.expiresMillis <= nowMillis) continue
            val onset = info.onsetMillis ?: info.effectiveMillis
            info.areas.forEachIndexed { i, area ->
                out += WeatherWarning(
                    id = "${alert.identifier}#${info.language}#$i",
                    event = info.event, level = level, onsetMillis = onset, expiresMillis = info.expiresMillis,
                    sentMillis = alert.sentMillis, areaDesc = area.description, headline = info.headline,
                    description = info.description, instruction = info.instruction, polygons = area.polygons,
                )
            }
        }
        return out
    }

    private fun pick(infos: List<CapInfo>, language: String): CapInfo? {
        val want = language.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        fun lang(i: CapInfo) = i.language.lowercase(Locale.ROOT).substringBefore('-')
        return infos.firstOrNull { lang(it) == want } ?: infos.firstOrNull { lang(it) == "es" } ?: infos.firstOrNull()
    }
}
