package com.qtekfun.mapas.core.geo.io

import com.qtekfun.mapas.core.geo.LatLon
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * KML importer: Placemarks with Point (places) or LineString (tracks), also inside MultiGeometry.
 * Polygons and other geometries are ignored. KMZ is supported through [readKmz].
 */
object KmlImporter : GeoStreamImporter {

    /** Uncompressed size cap for a KMZ entry (zip-bomb guard). */
    private const val MAX_KMZ_ENTRY_BYTES = 256L * 1024 * 1024

    override fun read(input: InputStream, handler: GeoImportHandler): Int {
        var skipped = 0
        try {
            val p = newXmlParser(input)
            val stack = ArrayDeque<String>()
            var inPlacemark = false
            var name: String? = null
            var desc: String? = null
            val points = ArrayList<LatLonAlt>()
            val lines = ArrayList<List<LatLonAlt>>()

            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val n = localName(p.name)
                        val parent = stack.lastOrNull()
                        when {
                            n == "Placemark" -> {
                                inPlacemark = true
                                name = null
                                desc = null
                                points.clear()
                                lines.clear()
                                stack.addLast(n)
                            }
                            inPlacemark && n == "name" && parent == "Placemark" -> name = p.nextText().cleanText()
                            inPlacemark && n == "description" && parent == "Placemark" ->
                                desc = p.nextText().cleanText()
                            inPlacemark && n == "coordinates" && (parent == "Point" || parent == "LineString") -> {
                                val (parsed, bad) = parseCoordinates(p.nextText())
                                skipped += bad
                                if (parent == "Point") points += parsed else lines += parsed
                            }
                            else -> stack.addLast(n)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (localName(p.name) == "Placemark" && inPlacemark) {
                            for (pt in points) {
                                handler.onPlace(ImportedPlace(pt.point, name, desc, pt.alt))
                            }
                            if (lines.isNotEmpty()) {
                                handler.onPathStart(PathKind.TRACK, name)
                                for (line in lines) {
                                    handler.onSegmentStart()
                                    for (pt in line) handler.onPoint(TrackPoint(pt.point, pt.alt))
                                }
                                handler.onPathEnd()
                            }
                            inPlacemark = false
                        }
                        stack.removeLastOrNull()
                    }
                }
                ev = p.next()
            }
            requireComplete(p)
        } catch (e: XmlPullParserException) {
            throw GeoImportException("Invalid KML: ${e.message}", e)
        } catch (e: IOException) {
            throw GeoImportException("Cannot read KML: ${e.message}", e)
        }
        return skipped
    }

    /** Reads the first `.kml` entry of a KMZ archive (preferring `doc.kml`). */
    fun readKmz(input: InputStream, handler: GeoImportHandler): Int {
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: throw GeoImportException("KMZ contains no .kml file")
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".kml")) {
                        return read(LimitedInputStream(zip, MAX_KMZ_ENTRY_BYTES), handler)
                    }
                }
            }
        } catch (e: IOException) {
            throw GeoImportException("Cannot read KMZ: ${e.message}", e)
        }
    }

    fun parseKmz(input: InputStream): GeoDocument {
        val h = CollectingHandler()
        val skipped = readKmz(input, h)
        return h.toDocument(skipped)
    }

    private class LatLonAlt(val point: LatLon, val alt: Double?)

    /** KML order is lon,lat[,alt], tuples separated by whitespace. */
    private fun parseCoordinates(text: String): Pair<List<LatLonAlt>, Int> {
        val out = ArrayList<LatLonAlt>()
        var bad = 0
        for (tuple in text.trim().split(Regex("\\s+"))) {
            if (tuple.isEmpty()) continue
            val parts = tuple.split(',')
            val lon = parts.getOrNull(0)?.toDoubleOrNull()
            val lat = parts.getOrNull(1)?.toDoubleOrNull()
            val point = if (lat != null && lon != null) LatLon.ofOrNull(lat, lon) else null
            if (point == null) bad++ else out += LatLonAlt(point, parts.getOrNull(2)?.toDoubleOrNull())
        }
        return out to bad
    }

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0 && ++count > limit) throw IOException("entry too large")
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                count += n
                if (count > limit) throw IOException("entry too large")
            }
            return n
        }
    }
}
