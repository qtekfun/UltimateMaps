package com.qtekfun.mapas.core.geo.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KmlImporterTest {

    private val sample = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Doc name</name>
  <Folder><name>Folder</name>
    <Placemark><name>Museo del Prado</name><description><![CDATA[<b>Arte</b> & más]]></description>
      <Point><coordinates>-3.6921,40.4138,667</coordinates></Point></Placemark>
    <Placemark><name>Sendero</name>
      <LineString><coordinates>
        -3.0,40.0,600 -3.001,40.001,601
        -3.002,40.002
      </coordinates></LineString></Placemark>
    <Placemark><name>Multi</name><MultiGeometry>
      <Point><coordinates>2.17,41.38</coordinates></Point>
      <LineString><coordinates>2.0,41.0 2.1,41.1</coordinates></LineString>
    </MultiGeometry></Placemark>
    <Placemark><name>Zona</name><Polygon><outerBoundaryIs><LinearRing>
      <coordinates>0,0 1,0 1,1 0,0</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
    <Placemark><name>Bad</name><Point><coordinates>abc,def</coordinates></Point></Placemark>
  </Folder></Document></kml>"""

    @Test fun parsesPointsAndLineStrings() {
        val doc = KmlImporter.parse(sample.stream())
        assertEquals(listOf("Museo del Prado", "Multi"), doc.places.map { it.name })
        val prado = doc.places[0]
        assertEquals(40.4138, prado.point!!.lat, 1e-9)
        assertEquals(-3.6921, prado.point.lon, 1e-9)
        assertEquals(667.0, prado.elevation)
        assertEquals("<b>Arte</b> & más", prado.description)

        assertEquals(listOf("Sendero", "Multi"), doc.tracks.map { it.name })
        assertEquals(3, doc.tracks[0].pointCount)
        assertEquals(2, doc.tracks[1].pointCount)
        assertEquals(1, doc.skipped)
    }

    @Test fun lonLatOrderIsRespected() {
        val doc = KmlImporter.parse(sample.stream())
        val first = doc.tracks[0].segments[0][0].point
        assertEquals(40.0, first.lat, 1e-9)
        assertEquals(-3.0, first.lon, 1e-9)
    }

    @Test fun kmzArchiveIsRead() {
        val bytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("files/icon.png")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
                z.putNextEntry(ZipEntry("doc.kml")); z.write(sample.toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val doc = KmlImporter.parseKmz(ByteArrayInputStream(bytes))
        assertEquals(2, doc.places.size)
    }

    @Test fun kmzWithoutKmlFails() {
        val bytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("a.txt")); z.write(1); z.closeEntry() }
        }.toByteArray()
        assertFailsWith<GeoImportException> { KmlImporter.parseKmz(ByteArrayInputStream(bytes)) }
    }

    @Test fun malformedKmlThrows() {
        assertFailsWith<GeoImportException> { KmlImporter.parse("<kml><Placemark>".stream()) }
    }

    @Test fun notAZipFailsAsKmz() {
        assertFailsWith<GeoImportException> { KmlImporter.parseKmz("plain text".stream()) }
    }
}
