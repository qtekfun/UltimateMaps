package com.qtekfun.ultimatemaps.core.geo.io

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.InputStream
import java.time.Instant

/** A saved place. [point] is null when the source carries no coordinates (e.g. Takeout CSV lists). */
data class ImportedPlace(
    val point: LatLon?,
    val name: String? = null,
    val description: String? = null,
    val elevation: Double? = null,
    val timeMillis: Long? = null,
    /** Source link, when the file provides one; can be resolved/searched later. */
    val url: String? = null,
)

data class TrackPoint(val point: LatLon, val elevation: Double? = null, val timeMillis: Long? = null)

enum class PathKind { ROUTE, TRACK }

/**
 * Streaming sink for importers: places and path points are pushed as they are read, so memory use
 * does not depend on file size unless the handler keeps them (see [CollectingHandler]).
 */
interface GeoImportHandler {
    fun onPlace(place: ImportedPlace) {}
    fun onPathStart(kind: PathKind, name: String?) {}
    fun onSegmentStart() {}
    fun onPoint(point: TrackPoint) {}
    fun onPathEnd() {}
}

class ImportedPath(val kind: PathKind, val name: String?, val segments: List<List<TrackPoint>>) {
    val pointCount: Int get() = segments.sumOf { it.size }
}

/** [skipped] counts entries dropped for invalid or missing coordinates. */
class GeoDocument(val places: List<ImportedPlace>, val paths: List<ImportedPath>, val skipped: Int) {
    val routes: List<ImportedPath> get() = paths.filter { it.kind == PathKind.ROUTE }
    val tracks: List<ImportedPath> get() = paths.filter { it.kind == PathKind.TRACK }
}

class GeoImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Handler that keeps everything in memory. */
class CollectingHandler : GeoImportHandler {
    private val places = ArrayList<ImportedPlace>()
    private val paths = ArrayList<ImportedPath>()
    private var kind = PathKind.TRACK
    private var name: String? = null
    private var segments = ArrayList<List<TrackPoint>>()
    private var segment: ArrayList<TrackPoint>? = null

    override fun onPlace(place: ImportedPlace) {
        places += place
    }

    override fun onPathStart(kind: PathKind, name: String?) {
        this.kind = kind
        this.name = name
        segments = ArrayList()
        segment = null
    }

    override fun onSegmentStart() {
        closeSegment()
        segment = ArrayList()
    }

    override fun onPoint(point: TrackPoint) {
        val s = segment ?: ArrayList<TrackPoint>().also { segment = it }
        s += point
    }

    override fun onPathEnd() {
        closeSegment()
        paths += ImportedPath(kind, name, segments)
    }

    private fun closeSegment() {
        segment?.let { if (it.isNotEmpty()) segments += it }
        segment = null
    }

    fun toDocument(skipped: Int) = GeoDocument(places.toList(), paths.toList(), skipped)
}

/** Format-independent entry points implemented by [GpxImporter] and [KmlImporter]. */
interface GeoStreamImporter {
    /** Streams [input] into [handler]; returns the number of skipped (invalid) entries. */
    fun read(input: InputStream, handler: GeoImportHandler): Int

    fun parse(input: InputStream): GeoDocument {
        val h = CollectingHandler()
        val skipped = read(input, h)
        return h.toDocument(skipped)
    }
}

internal fun parseTimeMillis(s: String?): Long? =
    s?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

internal fun String?.cleanText(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
