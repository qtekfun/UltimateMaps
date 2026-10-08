package com.qtekfun.ultimatemaps.core.transit.build

import com.qtekfun.ultimatemaps.core.transit.FeedOptions
import com.qtekfun.ultimatemaps.core.transit.GtfsReadOptions
import com.qtekfun.ultimatemaps.core.transit.GtfsReader
import com.qtekfun.ultimatemaps.core.transit.TransitIndex
import com.qtekfun.ultimatemaps.core.transit.TransitIndexBuilder
import com.qtekfun.ultimatemaps.core.transit.TransitIndexIo
import com.qtekfun.ultimatemaps.core.transit.ZipGtfsSource
import com.qtekfun.ultimatemaps.core.transit.calendarWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/** One feed of a city manifest. */
class FeedSpec(
    val label: String,
    val file: String,
    val namespace: String,
    val attribution: String,
    /** Keep only stops inside this box (south, west, north, east), for national feeds. */
    val bbox: DoubleArray?,
)

/** Describes one city: which GTFS zips make its index and what must be said about them. */
class CityManifest(
    val id: String,
    val city: String,
    val timezone: String,
    val bounds: DoubleArray,
    val feeds: List<FeedSpec>,
) {
    companion object {
        fun parse(text: String): CityManifest {
            val o = Json.parseToJsonElement(text).jsonObject
            fun box(e: JsonElement?): DoubleArray? = (e as? JsonArray)?.map { it.jsonPrimitive.double }?.toDoubleArray()?.also { require(it.size == 4) { "a box has 4 numbers" } }
            return CityManifest(
                id = o.getValue("id").jsonPrimitive.content,
                city = o.getValue("city").jsonPrimitive.content,
                timezone = o.getValue("timezone").jsonPrimitive.content,
                bounds = box(o["bounds"]) ?: error("bounds missing"),
                feeds = o.getValue("feeds").jsonArray.map {
                    val f = it.jsonObject
                    FeedSpec(
                        f.getValue("label").jsonPrimitive.content, f.getValue("file").jsonPrimitive.content,
                        f.getValue("namespace").jsonPrimitive.content, f.getValue("attribution").jsonPrimitive.content, box(f["bbox"]),
                    )
                },
            )
        }
    }
}

/** Outcome of one feed of a build. */
class FeedReport(val label: String, val validFrom: LocalDate?, val validTo: LocalDate?, val included: Boolean, val reason: String)

class BuildResult(val index: TransitIndex, val meta: JsonObject, val feeds: List<FeedReport>)

/**
 * Turns the GTFS zips of one [CityManifest] into a [TransitIndex] and its metadata. Nothing is downloaded: the zips are
 * already in the input directory. A feed whose calendar ended before [today] is left out (and reported), unless
 * [allowExpired] is set; a feed file that is missing is left out too. It is an error when no feed is left.
 */
/** Feeds in this stop namespace (Renfe) keep their stop and trip ids so a GTFS-RT feed can be matched. */
const val REALTIME_NAMESPACE = "renfe"

object TransitBuild {
    fun build(manifest: CityManifest, input: File, today: LocalDate, allowExpired: Boolean, generated: String, log: (String) -> Unit = {}): BuildResult {
        val builder = TransitIndexBuilder()
        val reports = ArrayList<FeedReport>()
        for (spec in manifest.feeds) {
            val zip = File(input, spec.file)
            if (!zip.isFile) {
                log("SKIP ${spec.label}: ${spec.file} not found")
                reports += FeedReport(spec.label, null, null, false, "missing file")
                continue
            }
            val bbox = spec.bbox
            val filter: ((Double, Double) -> Boolean)? = bbox?.let { b -> { lat, lon -> lat in b[0]..b[2] && lon in b[1]..b[3] } }
            val feed = GtfsReader.read(ZipGtfsSource(zip), GtfsReadOptions(stopFilter = filter))
            val window = calendarWindow(feed)
            val from = window?.first?.takeIf { it != Int.MIN_VALUE }?.let { LocalDate.ofEpochDay(it.toLong()) }
            val to = window?.last?.takeIf { it != Int.MAX_VALUE }?.let { LocalDate.ofEpochDay(it.toLong()) }
            val expired = to != null && to.isBefore(today)
            if (expired && !allowExpired) {
                log("SKIP ${spec.label}: expired (calendar ended $to, build date $today)")
                reports += FeedReport(spec.label, from, to, false, "expired")
                continue
            }
            builder.addFeed(feed, FeedOptions(spec.label, spec.namespace, spec.attribution, dropNonPositiveDuration = true, keepIds = spec.namespace == REALTIME_NAMESPACE), ignoredCalendarRange = false)
            reports += FeedReport(spec.label, from, to, true, if (expired) "included although expired" else "ok")
            log("OK   ${spec.label}: $from .. $to")
        }
        require(reports.any { it.included }) { "no usable feed for ${manifest.id}" }
        val index = builder.build()
        val validity = index.validity() ?: error("the feeds declare no calendar range; refusing to publish an index without validity")
        val validFrom = LocalDate.ofEpochDay(validity.firstDay.toLong())
        val validTo = LocalDate.ofEpochDay(validity.lastDay.toLong())
        require(allowExpired || !validTo.isBefore(today)) { "the index is already expired ($validTo)" }
        val attribution = index.sources.map { it.attribution }.filter { it.isNotBlank() }.distinct()
        val meta = buildJsonObject {
            put("id", manifest.id)
            put("city", manifest.city)
            put("timezone", manifest.timezone)
            put("bounds", buildJsonArray { manifest.bounds.forEach { add(JsonPrimitive(it)) } })
            put("validFrom", validFrom.toString())
            put("validTo", validTo.toString())
            put("attribution", buildJsonArray { attribution.forEach { add(JsonPrimitive(it)) } })
            put("generated", generated)
            put("droppedTrips", builder.droppedTrips)
            put("feeds", buildJsonArray {
                reports.forEach { r ->
                    add(buildJsonObject {
                        put("label", r.label)
                        put("validFrom", r.validFrom?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("validTo", r.validTo?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("included", r.included)
                        put("status", r.reason)
                    })
                }
            })
        }
        return BuildResult(index, meta, reports)
    }

    /** Writes `transit-<id>.umti` and `transit-<id>.json` into [out]; returns the two files. */
    fun write(result: BuildResult, id: String, out: File): Pair<File, File> {
        out.mkdirs()
        val umti = File(out, "transit-$id.umti")
        umti.outputStream().buffered().use { TransitIndexIo.write(result.index, it) }
        val meta = File(out, "transit-$id.json")
        val sha = MessageDigest.getInstance("SHA-256").digest(umti.readBytes()).joinToString("") { "%02x".format(it) }
        val withFile = JsonObject(result.meta + mapOf("file" to JsonPrimitive(umti.name), "size" to JsonPrimitive(umti.length()), "sha256" to JsonPrimitive(sha)))
        meta.writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), withFile) + "\n")
        return umti to meta
    }
}

fun main(args: Array<String>) {
    fun arg(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val manifestPath = arg("--manifest").orEmpty()
    val input = arg("--input-dir").orEmpty()
    val output = arg("--output-dir").orEmpty()
    if (manifestPath.isEmpty() || input.isEmpty() || output.isEmpty()) {
        System.err.println("usage: --manifest FILE --input-dir DIR --output-dir DIR [--today YYYY-MM-DD] [--allow-expired]")
        kotlin.system.exitProcess(2)
    }
    val today = arg("--today")?.let(LocalDate::parse) ?: LocalDate.now()
    val manifest = CityManifest.parse(File(manifestPath).readText())
    val t0 = System.nanoTime()
    val result = TransitBuild.build(manifest, File(input), today, args.contains("--allow-expired"), java.time.Instant.now().toString()) { println(it) }
    val (umti, meta) = TransitBuild.write(result, manifest.id, File(output))
    println("wrote ${umti.path} (${umti.length()} bytes) and ${meta.path} in ${(System.nanoTime() - t0) / 1_000_000} ms")
    println(result.index.describe())
    println("valid ${result.meta["validFrom"]?.jsonPrimitive?.content} .. ${result.meta["validTo"]?.jsonPrimitive?.content}; dropped trips ${result.meta["droppedTrips"]}")
}
