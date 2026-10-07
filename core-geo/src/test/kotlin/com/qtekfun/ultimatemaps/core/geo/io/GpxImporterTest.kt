package com.qtekfun.ultimatemaps.core.geo.io

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal fun String.stream(): InputStream = ByteArrayInputStream(toByteArray(Charsets.UTF_8))

class GpxImporterTest {

    private val sample = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
  <metadata><name>Ruta de prueba</name><time>2024-05-01T08:00:00Z</time></metadata>
  <wpt lat="40.4168" lon="-3.7038"><ele>650.5</ele><time>2024-05-01T08:30:00Z</time>
    <name>Puerta del Sol</name><desc>Centro &amp; kilómetro cero</desc>
    <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>80</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>
  </wpt>
  <wpt lat="41.3851" lon="2.1734"><name>Barcelona</name></wpt>
  <wpt lat="95.0" lon="2.0"><name>Invalid latitude</name></wpt>
  <rte><name>Madrid - Toledo</name>
    <rtept lat="40.4168" lon="-3.7038"/><rtept lat="39.8628" lon="-4.0273"><name>Toledo</name></rtept>
  </rte>
  <trk><name>Paseo</name>
    <trkseg>
      <trkpt lat="40.0" lon="-3.0"><ele>600</ele><time>2024-05-01T09:00:00Z</time>
        <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>90</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>
      </trkpt>
      <trkpt lat="40.001" lon="-3.001"/>
    </trkseg>
    <trkseg><trkpt lat="40.002" lon="-3.002"/></trkseg>
  </trk>
</gpx>"""

    @Test fun parsesWaypointsRoutesAndTracks() {
        val doc = GpxImporter.parse(sample.stream())
        assertEquals(2, doc.places.size)
        assertEquals(1, doc.skipped)
        val sol = doc.places[0]
        assertEquals("Puerta del Sol", sol.name)
        assertEquals("Centro & kilómetro cero", sol.description)
        assertEquals(650.5, sol.elevation)
        assertEquals(1714552200000L, sol.timeMillis)
        assertEquals(40.4168, sol.point!!.lat, 1e-9)

        val route = doc.routes.single()
        assertEquals("Madrid - Toledo", route.name)
        assertEquals(2, route.pointCount)

        val track = doc.tracks.single()
        assertEquals("Paseo", track.name)
        assertEquals(listOf(2, 1), track.segments.map { it.size })
        assertEquals(600.0, track.segments[0][0].elevation)
        assertNull(track.segments[0][1].elevation)
    }

    @Test fun metadataNameIsNotUsedAsPathOrPlaceName() {
        val doc = GpxImporter.parse(sample.stream())
        assertEquals(listOf("Puerta del Sol", "Barcelona"), doc.places.map { it.name })
    }

    @Test fun extensionsAreIgnored() {
        val doc = GpxImporter.parse(sample.stream())
        assertEquals(2, doc.tracks.single().segments[0].size)
    }

    @Test fun gpx10WithoutNamespaceIsAccepted() {
        val doc = GpxImporter.parse("""<gpx version="1.0"><wpt lat="1" lon="2"><name>A</name></wpt></gpx>""".stream())
        assertEquals("A", doc.places.single().name)
    }

    @Test fun tenThousandPointTrackStreams() {
        val n = 10_000
        val xml = buildString {
            append("""<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Big</name><trkseg>""")
            for (i in 0 until n) {
                append("""<trkpt lat="${40.0 + i * 0.00001}" lon="${-3.0 - i * 0.00001}"><ele>${600 + i % 50}</ele></trkpt>""")
            }
            append("</trkseg></trk></gpx>")
        }
        var points = 0
        var paths = 0
        var last: TrackPoint? = null
        val skipped = GpxImporter.read(
            xml.stream(),
            object : GeoImportHandler {
                override fun onPathStart(kind: PathKind, name: String?) {
                    paths++
                    assertEquals("Big", name)
                }
                override fun onPoint(point: TrackPoint) {
                    points++
                    last = point
                }
            },
        )
        assertEquals(n, points)
        assertEquals(1, paths)
        assertEquals(0, skipped)
        assertEquals(40.0 + (n - 1) * 0.00001, last!!.point.lat, 1e-9)

        val doc = GpxImporter.parse(xml.stream())
        assertEquals(n, doc.tracks.single().pointCount)
    }

    @Test fun malformedXmlThrowsImportException() {
        assertFailsWith<GeoImportException> { GpxImporter.parse("<gpx><wpt lat=\"1\" lon=\"2\">".stream()) }
    }

    @Test fun externalEntitiesAreNotResolved() {
        val xml = """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <gpx><wpt lat="1" lon="2"><name>&x;</name></wpt></gpx>"""
        // Either rejected or the entity left unexpanded; it must never contain file contents.
        val result = runCatching { GpxImporter.parse(xml.stream()) }
        result.getOrNull()?.places?.forEach { assert(it.name?.contains("root:") != true) }
    }

    @Test fun emptyDocumentGivesEmptyResult() {
        val doc = GpxImporter.parse("<gpx/>".stream())
        assertEquals(0, doc.places.size + doc.paths.size)
    }
}
