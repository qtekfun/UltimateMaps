package com.qtekfun.ultimatemaps.core.transit.rt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The body is not the JSON the parser expects. */
class RtParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Parser for the JSON form of the GTFS-RT feeds Renfe publishes (`trip_updates.json`, `vehicle_positions.json`,
 * `alerts.json`): the protobuf messages rendered as JSON with camelCase names, 64-bit numbers as strings. Tolerant: unknown
 * fields are ignored and a malformed entity is skipped, but a body that is not a feed at all throws [RtParseException].
 */
object GtfsRtJson {
    private val json = Json { isLenient = true }

    class Parsed<T>(val timestampSec: Long?, val entities: List<T>)

    fun parseTripUpdates(body: String): Parsed<RtTripUpdate> = parse(body) { e ->
        val tu = e.obj("tripUpdate") ?: return@parse null
        val trip = tu.obj("trip") ?: return@parse null
        val tripId = trip.str("tripId") ?: return@parse null
        val stops = (tu["stopTimeUpdate"] as? JsonArray).orEmpty().mapNotNull { su ->
            val o = su as? JsonObject ?: return@mapNotNull null
            val stopId = o.str("stopId") ?: return@mapNotNull null
            val arr = o.obj("arrival")
            val dep = o.obj("departure")
            RtStopUpdate(
                stopId, arr?.long("time"), arr?.int("delay"), dep?.long("time"), dep?.int("delay"),
                skipped = o.str("scheduleRelationship") == "SKIPPED",
            )
        }
        RtTripUpdate(tripId, trip.str("scheduleRelationship") == "CANCELED", tu.int("delay"), stops)
    }

    fun parseVehicles(body: String): Parsed<RtVehicle> = parse(body) { e ->
        val v = e.obj("vehicle") ?: return@parse null
        val pos = v.obj("position")
        RtVehicle(v.obj("trip")?.str("tripId"), v.str("stopId"), pos?.double("latitude"), pos?.double("longitude"), v.long("timestamp"))
    }

    fun parseAlerts(body: String): Parsed<RtAlert> = parse(body) { e ->
        val a = e.obj("alert") ?: return@parse null
        val informed = (a["informedEntity"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val period = (a["activePeriod"] as? JsonArray).orEmpty().firstOrNull() as? JsonObject
        val text = textOf(a.obj("descriptionText")) ?: textOf(a.obj("headerText")) ?: return@parse null
        RtAlert(
            id = e.str("id").orEmpty(),
            routeIds = informed.mapNotNull { it.str("routeId") },
            stopIds = informed.mapNotNull { it.str("stopId") },
            tripIds = informed.mapNotNull { it.obj("trip")?.str("tripId") },
            startSec = period?.long("start"),
            endSec = period?.long("end"),
            text = text,
        )
    }

    private fun <T> parse(body: String, entity: (JsonObject) -> T?): Parsed<T> {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            throw RtParseException("not JSON", e)
        } ?: throw RtParseException("not a feed object")
        val entities = root["entity"]
        if (root["header"] == null && entities == null) throw RtParseException("no header and no entities")
        val list = (entities as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            try { entity(o) } catch (_: RuntimeException) { null }
        }
        return Parsed(root.obj("header")?.long("timestamp"), list)
    }

    /** Spanish when offered (that is what Renfe writes), otherwise the first translation. */
    private fun textOf(ts: JsonObject?): String? {
        val tr = (ts?.get("translation") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val pick = tr.firstOrNull { it.str("language") == "es" } ?: tr.firstOrNull()
        return pick?.str("text")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.str(k: String): String? = prim(k)?.content
    private fun JsonObject.long(k: String): Long? = prim(k)?.content?.toLongOrNull()
    private fun JsonObject.int(k: String): Int? = long(k)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    private fun JsonObject.double(k: String): Double? = prim(k)?.content?.toDoubleOrNull()
}
