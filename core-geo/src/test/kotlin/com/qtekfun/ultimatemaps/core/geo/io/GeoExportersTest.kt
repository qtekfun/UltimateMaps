package com.qtekfun.ultimatemaps.core.geo.io

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeoExportersTest {
    private fun doc() = GeoDocument(
        places = listOf(
            ImportedPlace(LatLon(40.4168, -3.7038), "Sol <&> \"Km 0\"", "Centro & más", 650.5, 1714552200000, "https://example.org/a?x=1&y=2"),
            ImportedPlace(LatLon(-0.000001, 0.00015), "Sin nada"),
            ImportedPlace(null, "sin coordenadas"),
        ),
        paths = listOf(
            ImportedPath(PathKind.ROUTE, "Ruta", listOf(listOf(TrackPoint(LatLon(1.0, 2.0)), TrackPoint(LatLon(1.5, 2.5), 10.0)))),
            ImportedPath(
                PathKind.TRACK, "Paseo",
                listOf(
                    listOf(TrackPoint(LatLon(40.0, -3.0), 600.0, 1714554000000), TrackPoint(LatLon(40.001, -3.001))),
                    listOf(TrackPoint(LatLon(40.002, -3.002))),
                ),
            ),
        ),
        skipped = 0,
    )

    @Test fun gpxRoundTrip() {
        val back = GpxImporter.parse(GpxExporter.toBytes(doc()).inputStream())
        assertEquals(2, back.places.size) // the place without coordinates is not written
        assertEquals(doc().places[0].copy(url = null), back.places[0].copy(url = null))
        assertEquals(LatLon(-0.000001, 0.00015), back.places[1].point)
        assertEquals(PathKind.ROUTE, back.paths[0].kind)
        assertEquals(doc().paths[0].segments, back.paths[0].segments)
        assertEquals(doc().paths[1].segments, back.paths[1].segments)
        assertEquals("Paseo", back.paths[1].name)
    }

    @Test fun kmlRoundTrip() {
        val back = KmlImporter.parse(KmlExporter.toBytes(doc()).inputStream())
        assertEquals(listOf("Sol <&> \"Km 0\"", "Sin nada"), back.places.map { it.name })
        assertEquals("Centro & más", back.places[0].description)
        assertEquals(650.5, back.places[0].elevation)
        assertEquals(LatLon(-0.000001, 0.00015), back.places[1].point)
        assertEquals(2, back.paths.size)
        // Routes come back as tracks in KML; geometry (without timestamps) is preserved.
        assertTrue(back.paths.all { it.kind == PathKind.TRACK })
        assertEquals(doc().paths[0].segments, back.paths[0].segments)
        assertEquals(
            doc().paths[1].segments.map { s -> s.map { it.copy(timeMillis = null) } },
            back.paths[1].segments,
        )
    }

    @Test fun illegalXmlCharactersAreDropped() {
        val d = GeoDocument(listOf(ImportedPlace(LatLon(1.0, 1.0), "a\u0000b\u0001c")), emptyList(), 0)
        val text = String(GpxExporter.toBytes(d))
        assertFalse(text.contains('\u0000'))
        assertEquals("abc", GpxImporter.parse(text.byteInputStream()).places[0].name)
    }

    @Test fun largeTrackRoundTrips() {
        val pts = (0 until 10_000).map { TrackPoint(LatLon(40 + it * 1e-5, -3 + it * 1e-5), 600.0 + it % 7, 1_700_000_000_000 + it * 1000L) }
        val d = GeoDocument(emptyList(), listOf(ImportedPath(PathKind.TRACK, "big", listOf(pts))), 0)
        assertEquals(pts, GpxImporter.parse(GpxExporter.toBytes(d).inputStream()).paths[0].segments[0])
    }
}
