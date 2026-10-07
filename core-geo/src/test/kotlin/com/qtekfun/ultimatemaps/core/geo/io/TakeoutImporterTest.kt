package com.qtekfun.ultimatemaps.core.geo.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TakeoutImporterTest {

    private val geoJson = """{
  "type": "FeatureCollection",
  "features": [
    {"type":"Feature","geometry":{"type":"Point","coordinates":[-3.7038,40.4168]},
     "properties":{"date":"2021-03-04T10:00:00Z","google_maps_url":"http://maps.google.com/?cid=123",
       "Title":"Casa","Location":{"address":"Calle Mayor 1, Madrid","country_code":"ES"}}},
    {"type":"Feature","geometry":{"type":"Point","coordinates":[0.0,0.0]},
     "properties":{"google_maps_url":"http://maps.google.com/?q=40.41,-3.70&ftid=0x1","Title":"Por URL"}},
    {"type":"Feature","geometry":{"type":"Point","coordinates":[0.0,0.0]},
     "properties":{"Title":"Por geo_coordinates","Location":{"business_name":"Bar","geo_coordinates":{"latitude":"41.38","longitude":"2.17"}}}},
    {"type":"Feature","geometry":{"type":"Point","coordinates":[0.0,0.0]},
     "properties":{"google_maps_url":"http://maps.google.com/?cid=999","Title":"Sin coordenadas"}},
    {"type":"Feature","geometry":{"type":"LineString","coordinates":[[0,0],[1,1]]},"properties":{"Title":"Línea"}},
    {"type":"Feature","geometry":null,"properties":{}}
  ]
}"""

    @Test fun savedPlacesGeoJson() {
        val doc = TakeoutImporter.parseSavedPlacesGeoJson(geoJson.stream())
        assertEquals(listOf("Casa", "Por URL", "Por geo_coordinates", "Sin coordenadas"), doc.places.map { it.name })
        assertEquals(2, doc.skipped)

        val casa = doc.places[0]
        assertEquals(40.4168, casa.point!!.lat, 1e-9)
        assertEquals(-3.7038, casa.point.lon, 1e-9)
        assertEquals("Calle Mayor 1, Madrid", casa.description)

        assertEquals(40.41, doc.places[1].point!!.lat, 1e-9)
        assertEquals(41.38, doc.places[2].point!!.lat, 1e-9)
        assertNull(doc.places[3].point)
        assertEquals("http://maps.google.com/?cid=999", doc.places[3].url)
    }

    @Test fun geoJsonWithBomAndWrongShape() {
        assertEquals(0, TakeoutImporter.parseSavedPlacesGeoJson("﻿{\"features\":[]}".stream()).places.size)
        assertFailsWith<GeoImportException> { TakeoutImporter.parseSavedPlacesGeoJson("[1,2]".stream()) }
        assertFailsWith<GeoImportException> { TakeoutImporter.parseSavedPlacesGeoJson("not json".stream()) }
    }

    @Test fun csvListWithoutCoordinates() {
        val csv = "﻿Title,Note,URL,Comment\r\n" +
            "Casa,,https://www.google.com/maps/place/Casa/data=!4m2!3m1!1s0x1,\r\n" +
            "\"Bar, el \"\"mejor\"\"\",\"Nota con\nsalto\",https://maps.app.goo.gl/abc123,Genial\r\n" +
            ",,,\r\n" +
            "Sin url,,,\r\n" +
            "\r\n"
        val doc = TakeoutImporter.parseListCsv(csv.stream())
        assertEquals(listOf("Casa", "Bar, el \"mejor\"", "Sin url"), doc.places.map { it.name })
        assertEquals("Nota con\nsalto\nGenial", doc.places[1].description)
        assertEquals("https://maps.app.goo.gl/abc123", doc.places[1].url)
        assertNull(doc.places[0].point)
        assertNull(doc.places[2].url)
    }

    @Test fun csvUrlWithCoordinatesGivesPoint() {
        val csv = "Title,Note,URL,Comment\nPlaza,,\"https://www.google.com/maps/@40.4,-3.7,17z\",\n"
        val p = TakeoutImporter.parseListCsv(csv.stream()).places.single()
        assertEquals(40.4, p.point!!.lat, 1e-9)
    }

    @Test fun csvSpanishHeaders() {
        val csv = "Título,Nota,URL,Comentario\nCafé,Bueno,https://maps.app.goo.gl/x,Volver\n"
        val p = TakeoutImporter.parseListCsv(csv.stream()).places.single()
        assertEquals("Café", p.name)
        assertEquals("Bueno\nVolver", p.description)
    }

    @Test fun csvWithoutHeaderFails() {
        assertFailsWith<GeoImportException> { TakeoutImporter.parseListCsv("a,b,c\n1,2,3\n".stream()) }
    }
}
