package com.qtekfun.mapas.core.cameras

import com.qtekfun.mapas.core.geo.LatLon
import java.io.IOException
import java.io.InputStream
import java.time.OffsetDateTime
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

class IncidentParseException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Streaming reader of the DGT "Incidencias DGT DATEX2 v3.7" publication (`SituationPublication`). It keeps only what the
 * app shows: kind, road, position(s), direction, place names and validity. It never keeps free text or anything that
 * identifies a person. Verified against the real feed of 2026-10-07 (1437 records, 6.4 MB, 203 KB gzipped).
 *
 * Namespaces are ignored (local names only), DOCTYPE processing stays off, and [maxRecords] bounds memory. A document
 * cut short, or with elements still open at the end, is rejected whole.
 */
object DatexIncidentParser {
    const val MAX_RECORDS = 50_000

    private class Rec {
        var id = ""
        var type: String? = null
        var reference: String? = null
        var cause: String? = null
        val details = HashSet<String>()
        var road: String? = null
        var direction: String? = null
        var start: String? = null
        var end: String? = null
        var status: String? = null
        val points = ArrayList<DoubleArray>()
        var km: Double? = null
        var municipality: String? = null
        var province: String? = null
        var curLat: Double? = null
        var curLon: Double? = null
    }

    fun parse(input: InputStream, nowMillis: Long? = null): IncidentFeed {
        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val p = factory.newPullParser().apply { setInput(input, null) }
            val out = ArrayList<TrafficIncident>()
            var skipped = 0
            var published: Long? = null
            var rec: Rec? = null
            var inDetail = 0
            var inPoint = 0 // inside to / from / point of the location reference
            var sawRoot = false
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = local(p.name)
                        sawRoot = true
                        val r = rec
                        when {
                            name == "situationRecord" -> rec = Rec().also {
                                it.id = p.getAttributeValue(null, "id").orEmpty()
                                it.type = p.getAttributeValue(null, "xsi:type")?.let(::local)
                            }
                            r == null -> if (name == "publicationTime" && published == null) published = time(text(p))
                            name == "detailedCauseType" -> inDetail++
                            name in POINT_HOLDERS -> inPoint++
                            name == "situationRecordCreationReference" -> r.reference = text(p)
                            name == "causeType" -> r.cause = text(p)
                            name == "validityStatus" -> r.status = text(p)
                            name == "overallStartTime" -> r.start = text(p)
                            name == "overallEndTime" -> r.end = text(p)
                            name == "roadName" && r.road == null -> r.road = text(p)
                            name == "tpegDirection" && r.direction == null -> r.direction = text(p)
                            name == "roadOrCarriagewayOrLaneManagementType" -> r.details += text(p).orEmpty()
                            inDetail > 0 && name.endsWith("Type") -> r.details += text(p).orEmpty()
                            name == "latitude" && inPoint > 0 -> r.curLat = text(p)?.toDoubleOrNull()
                            name == "longitude" && inPoint > 0 -> {
                                r.curLon = text(p)?.toDoubleOrNull()
                                val la = r.curLat
                                val lo = r.curLon
                                if (la != null && lo != null && LatLon.ofOrNull(la, lo) != null && !(la == 0.0 && lo == 0.0)) {
                                    r.points += doubleArrayOf(la, lo)
                                }
                            }
                            name == "kilometerPoint" && r.km == null -> r.km = text(p)?.toDoubleOrNull()
                            name == "municipality" && r.municipality == null -> r.municipality = text(p)
                            name == "province" && r.province == null -> r.province = text(p)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = local(p.name)
                        when {
                            name == "detailedCauseType" -> inDetail = (inDetail - 1).coerceAtLeast(0)
                            name in POINT_HOLDERS -> inPoint = (inPoint - 1).coerceAtLeast(0)
                            name == "situationRecord" -> {
                                val r = rec
                                rec = null
                                if (r != null) {
                                    val incident = finish(r, nowMillis)
                                    if (incident != null) out += incident else skipped++
                                    if (out.size + skipped > MAX_RECORDS) throw IncidentParseException("more than $MAX_RECORDS records")
                                }
                            }
                        }
                    }
                }
                event = p.next()
            }
            if (!sawRoot || p.depth > 0) throw IncidentParseException("unexpected end of document")
            return IncidentFeed(published, out, skipped)
        } catch (e: IncidentParseException) {
            throw e
        } catch (e: XmlPullParserException) {
            throw IncidentParseException("malformed XML: ${e.message}", e)
        }
    }

    private val POINT_HOLDERS = setOf("to", "from", "point")

    private fun local(qName: String): String = qName.substringAfter(':')

    /** Text of the current element, leaving the parser on its END_TAG. */
    private fun text(p: XmlPullParser): String? {
        val s = try { p.nextText() } catch (e: XmlPullParserException) { return null }
        return s.trim().ifEmpty { null }
    }

    private fun time(s: String?): Long? = s?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    private fun finish(r: Rec, nowMillis: Long?): TrafficIncident? {
        if (r.status != null && r.status != "active") return null
        val kind = classify(r.reference, r.cause, r.details, r.type) ?: return null
        val first = r.points.firstOrNull() ?: return null
        val last = r.points.lastOrNull()
        val endMillis = time(r.end)
        if (nowMillis != null && endMillis != null && endMillis < nowMillis) return null
        val a = LatLon(first[0], first[1])
        val b = last?.takeIf { it !== first && (it[0] != first[0] || it[1] != first[1]) }?.let { LatLon(it[0], it[1]) }
        return TrafficIncident(
            id = r.id.ifEmpty { "i${a.lat},${a.lon}" }, kind = kind, road = r.road.orEmpty(), location = a, end = b,
            directionDeg = bearingOf(r.direction), municipality = r.municipality, province = r.province, kmPoint = r.km,
            startMillis = time(r.start), endMillis = endMillis,
        )
    }

    /**
     * The kind of a record, or null when it is not one we show. The V16 beacon records are told apart by the prefix
     * `V16_` of their `situationRecordCreationReference` (99 of them in the feed of 2026-10-07, all with source `DGT3.0`
     * and cause `vehicleObstruction/vehicleStuck`): an inference from the data, the DGT does not document it (not verified).
     */
    fun classify(reference: String?, cause: String?, details: Set<String>, recordType: String? = null): IncidentKind? = when {
        reference != null && reference.startsWith("V16_") -> IncidentKind.V16
        recordType == "AbnormalTraffic" || cause == "abnormalTraffic" -> IncidentKind.CONGESTION // slow traffic, whatever caused it
        details.any { it == "roadClosed" || it == "carriagewayClosures" } -> IncidentKind.CLOSURE
        cause == "accident" -> IncidentKind.ACCIDENT
        cause == "roadMaintenance" -> IncidentKind.ROADWORKS
        cause == "vehicleObstruction" || cause == "obstruction" || cause == "environmentalObstruction" ||
            cause == "infrastructureDamageObstruction" -> IncidentKind.OBSTACLE
        cause == "poorEnvironment" || cause == "nonWeatherRelatedRoadConditions" -> IncidentKind.WEATHER
        else -> null
    }

    /** `northBound` -> 0, `southWestBound` -> 225; anything else (`unknown`, `inbound`, `clockwise`...) -> null. */
    fun bearingOf(direction: String?): Int? = when (direction) {
        "northBound" -> 0
        "northEastBound" -> 45
        "eastBound" -> 90
        "southEastBound" -> 135
        "southBound" -> 180
        "southWestBound" -> 225
        "westBound" -> 270
        "northWestBound" -> 315
        else -> null
    }
}
