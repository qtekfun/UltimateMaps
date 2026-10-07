package com.qtekfun.ultimatemaps.core.geo.io

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.IOException
import java.io.InputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

internal fun newXmlParser(input: InputStream): XmlPullParser {
    val factory = XmlPullParserFactory.newInstance()
    factory.isNamespaceAware = false
    // DOCTYPE processing stays off (the default), so external entities are never resolved.
    return factory.newPullParser().apply { setInput(input, null) }
}

/** kXML is lenient with truncated files: reject a document that ends with elements still open. */
internal fun requireComplete(p: XmlPullParser) {
    if (p.depth > 0) throw XmlPullParserException("unexpected end of document", p, null)
}

internal fun localName(qName: String): String = qName.substringAfter(':')

/** GPX 1.0/1.1 importer (wpt, rte, trk) using the platform's XmlPullParser, streaming. */
object GpxImporter : GeoStreamImporter {

    private class PointAcc(val point: LatLon?) {
        var name: String? = null
        var desc: String? = null
        var ele: Double? = null
        var time: Long? = null
    }

    override fun read(input: InputStream, handler: GeoImportHandler): Int {
        var skipped = 0
        try {
            val p = newXmlParser(input)
            val stack = ArrayDeque<String>()
            var extDepth = 0
            var acc: PointAcc? = null
            var pathName: String? = null
            var pathOpen = false

            fun ensurePathStarted(kind: PathKind) {
                if (!pathOpen) {
                    handler.onPathStart(kind, pathName)
                    pathOpen = true
                    if (kind == PathKind.ROUTE) handler.onSegmentStart()
                }
            }

            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val n = localName(p.name)
                        if (extDepth > 0 || n == "extensions") {
                            // Vendor extensions are skipped wholesale.
                            if (extDepth == 0) extDepth = 1 else extDepth++
                        } else when (n) {
                            "wpt", "rtept", "trkpt" -> {
                                val lat = p.getAttributeValue(null, "lat")?.trim()?.toDoubleOrNull()
                                val lon = p.getAttributeValue(null, "lon")?.trim()?.toDoubleOrNull()
                                acc = PointAcc(if (lat != null && lon != null) LatLon.ofOrNull(lat, lon) else null)
                                if (n == "rtept") ensurePathStarted(PathKind.ROUTE)
                                if (n == "trkpt") ensurePathStarted(PathKind.TRACK)
                                stack.addLast(n)
                            }
                            "rte", "trk" -> {
                                pathName = null
                                pathOpen = false
                                stack.addLast(n)
                            }
                            "trkseg" -> {
                                ensurePathStarted(PathKind.TRACK)
                                handler.onSegmentStart()
                                stack.addLast(n)
                            }
                            "name", "desc", "ele", "time" -> {
                                val parent = stack.lastOrNull()
                                val text = p.nextText() // consumes the matching END_TAG
                                val a = acc
                                if (a != null && parent in POINT_TAGS) {
                                    when (n) {
                                        "name" -> a.name = text.cleanText()
                                        "desc" -> a.desc = text.cleanText()
                                        "ele" -> a.ele = text.trim().toDoubleOrNull()
                                        "time" -> a.time = parseTimeMillis(text)
                                    }
                                } else if (n == "name" && (parent == "rte" || parent == "trk")) {
                                    pathName = text.cleanText()
                                }
                            }
                            else -> stack.addLast(n)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (extDepth > 0) {
                            extDepth--
                        } else {
                            when (val n = localName(p.name)) {
                                "wpt" -> {
                                    val a = acc
                                    if (a?.point == null) skipped++ else handler.onPlace(
                                        ImportedPlace(a.point, a.name, a.desc, a.ele, a.time),
                                    )
                                    acc = null
                                }
                                "rtept", "trkpt" -> {
                                    val a = acc
                                    if (a?.point == null) skipped++ else handler.onPoint(TrackPoint(a.point, a.ele, a.time))
                                    acc = null
                                }
                                "rte", "trk" -> {
                                    ensurePathStarted(if (n == "rte") PathKind.ROUTE else PathKind.TRACK)
                                    handler.onPathEnd()
                                    pathOpen = false
                                }
                            }
                            stack.removeLastOrNull()
                        }
                    }
                }
                ev = p.next()
            }
            requireComplete(p)
        } catch (e: XmlPullParserException) {
            throw GeoImportException("Invalid GPX: ${e.message}", e)
        } catch (e: IOException) {
            throw GeoImportException("Cannot read GPX: ${e.message}", e)
        }
        return skipped
    }

    private val POINT_TAGS = setOf("wpt", "rtept", "trkpt")
}
