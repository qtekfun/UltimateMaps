package com.qtekfun.mapas.core.geo.io

import java.io.OutputStream
import java.time.Instant

/** Format-independent writer, symmetric to [GeoStreamImporter]. */
interface GeoExporter {
    fun write(doc: GeoDocument, out: OutputStream)

    fun toBytes(doc: GeoDocument): ByteArray =
        java.io.ByteArrayOutputStream().also { write(doc, it) }.toByteArray()
}

/** Escapes XML text/attribute content and drops characters that are illegal in XML 1.0. */
internal fun xmlEscape(s: String): String {
    val sb = StringBuilder(s.length + 8)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        i += Character.charCount(cp)
        when {
            cp == '&'.code -> sb.append("&amp;")
            cp == '<'.code -> sb.append("&lt;")
            cp == '>'.code -> sb.append("&gt;")
            cp == '"'.code -> sb.append("&quot;")
            cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF ->
                sb.appendCodePoint(cp)
            else -> Unit
        }
    }
    return sb.toString()
}

/** Shortest round-trippable decimal; never uses scientific notation (invalid in GPX/KML numbers). */
internal fun num(d: Double): String = java.math.BigDecimal(d.toString()).toPlainString()

/** GPX 1.1 exporter (wpt, rte, trk/trkseg). Extensions are not written. */
object GpxExporter : GeoExporter {
    override fun write(doc: GeoDocument, out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        w.write("<gpx version=\"1.1\" creator=\"UltimateMaps\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (p in doc.places) {
            val pt = p.point ?: continue
            w.write("  <wpt lat=\"${num(pt.lat)}\" lon=\"${num(pt.lon)}\">")
            p.elevation?.let { w.write("<ele>${num(it)}</ele>") }
            p.timeMillis?.let { w.write("<time>${Instant.ofEpochMilli(it)}</time>") }
            p.name?.let { w.write("<name>${xmlEscape(it)}</name>") }
            p.description?.let { w.write("<desc>${xmlEscape(it)}</desc>") }
            p.url?.let { w.write("<link href=\"${xmlEscape(it)}\"/>") }
            w.write("</wpt>\n")
        }
        for (path in doc.paths) {
            if (path.kind == PathKind.ROUTE) {
                w.write("  <rte>")
                path.name?.let { w.write("<name>${xmlEscape(it)}</name>") }
                w.write("\n")
                for (pt in path.segments.flatten()) writePoint(w, "rtept", pt)
                w.write("  </rte>\n")
            } else {
                w.write("  <trk>")
                path.name?.let { w.write("<name>${xmlEscape(it)}</name>") }
                w.write("\n")
                for (seg in path.segments) {
                    w.write("    <trkseg>\n")
                    for (pt in seg) writePoint(w, "trkpt", pt)
                    w.write("    </trkseg>\n")
                }
                w.write("  </trk>\n")
            }
        }
        w.write("</gpx>\n")
        w.flush()
    }

    private fun writePoint(w: java.io.Writer, tag: String, pt: TrackPoint) {
        w.write("      <$tag lat=\"${num(pt.point.lat)}\" lon=\"${num(pt.point.lon)}\">")
        pt.elevation?.let { w.write("<ele>${num(it)}</ele>") }
        pt.timeMillis?.let { w.write("<time>${Instant.ofEpochMilli(it)}</time>") }
        w.write("</$tag>\n")
    }
}

/**
 * KML 2.2 exporter: places as Point Placemarks, paths as LineString Placemarks (several segments become a
 * MultiGeometry). KML has no route/track distinction, so routes are re-imported as tracks; timestamps are
 * not written (the importer ignores them too).
 */
object KmlExporter : GeoExporter {
    override fun write(doc: GeoDocument, out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        w.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        for (p in doc.places) {
            val pt = p.point ?: continue
            w.write("  <Placemark>")
            p.name?.let { w.write("<name>${xmlEscape(it)}</name>") }
            p.description?.let { w.write("<description>${xmlEscape(it)}</description>") }
            w.write("<Point><coordinates>${coord(pt.lon, pt.lat, p.elevation)}</coordinates></Point></Placemark>\n")
        }
        for (path in doc.paths) {
            val segments = path.segments.filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue
            w.write("  <Placemark>")
            path.name?.let { w.write("<name>${xmlEscape(it)}</name>") }
            if (segments.size > 1) w.write("<MultiGeometry>")
            for (seg in segments) {
                w.write("<LineString><coordinates>")
                w.write(seg.joinToString(" ") { coord(it.point.lon, it.point.lat, it.elevation) })
                w.write("</coordinates></LineString>")
            }
            if (segments.size > 1) w.write("</MultiGeometry>")
            w.write("</Placemark>\n")
        }
        w.write("</Document>\n</kml>\n")
        w.flush()
    }

    private fun coord(lon: Double, lat: Double, alt: Double?) =
        if (alt != null) "${num(lon)},${num(lat)},${num(alt)}" else "${num(lon)},${num(lat)}"
}
