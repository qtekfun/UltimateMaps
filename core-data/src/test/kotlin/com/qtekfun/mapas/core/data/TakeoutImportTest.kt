package com.qtekfun.mapas.core.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Synthetic fixtures only; the layouts are assumptions about Takeout (not verified against a real export). */
class TakeoutImportTest {
    private val repo = SqlitePlacesRepository(BundledSQLiteDriver(), ":memory:")
    private val importer = TakeoutImport(repo)

    @AfterTest fun tearDown() = repo.close()

    private val csvFavs = "Title,Note,URL,Tags,Comment\r\n" +
        "Alpha,first note,\"https://www.google.com/maps/place/Alpha/@40.41,-3.70,17z\",,\r\n" +
        "Beta,,https://www.google.com/maps/place/Beta/data=!4m2!3m1!1s0x1,,\r\n" +
        "Gamma,,\"https://maps.google.com/?q=41.38,2.17\",,\r\n"

    private val savedJson = """{"type":"FeatureCollection","features":[
      {"type":"Feature","geometry":{"type":"Point","coordinates":[2.17,41.38]},
       "properties":{"date":"2020-01-01T00:00:00Z","google_maps_url":"http://maps.google.com/?cid=1",
         "location":{"address":"1 Test St","country_code":"ES","name":"Delta"}}},
      {"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},
       "properties":{"google_maps_url":"http://maps.google.com/?cid=2","location":{"name":"Epsilon"}}},
      {"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},
       "properties":{"Title":"Zeta","Location":{"Business Name":"Zeta","Geo Coordinates":{"Latitude":"37.38","Longitude":"-5.98"}}}}
    ]}"""

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun zipWithListsAndGeoJson() {
        val bytes = zip(
            "Takeout/Saved/Favs.csv" to csvFavs,
            "Takeout/Maps (your places)/Saved Places.json" to savedJson,
            "Takeout/Maps (your places)/Reviews.json" to savedJson,
            "Takeout/Maps (your places)/notes.txt" to "x",
            "Takeout/archive_browser.html" to "<html/>",
            "Takeout/Saved/broken.json" to "not json",
        )
        val s = importer.importZip(bytes.inputStream())
        assertEquals(listOf("Favs", "Saved Places"), s.lists.map { it.listName })
        // CSV: Alpha and Gamma have coordinates in their link, Beta does not.
        assertEquals(TakeoutListResult("Favs", 2, 0, 1), s.lists[0])
        // JSON: Delta by geometry, Zeta by geo coordinates (alternate key spelling), Epsilon has none.
        assertEquals(TakeoutListResult("Saved Places", 2, 0, 1), s.lists[1])
        assertEquals(4, s.placesAdded)
        assertEquals(2, s.noCoordinates)
        assertEquals(3, s.filesIgnored) // Reviews.json, notes.txt, archive_browser.html
        assertEquals(1, s.filesFailed) // broken.json
        assertEquals(4, repo.places().size)
        assertEquals(2, repo.lists().first { it.name == "Favs" }.placeCount)
    }

    @Test fun reimportIsIdempotent() {
        val bytes = zip("Saved/Favs.csv" to csvFavs)
        importer.importZip(bytes.inputStream())
        val again = importer.importZip(bytes.inputStream())
        assertEquals(TakeoutListResult("Favs", 0, 2, 1), again.lists.single())
        assertEquals(1, repo.lists().count { it.name == "Favs" })
        assertEquals(2, repo.places().size)
    }

    @Test fun singleFiles() {
        val csv = importer.importFile(csvFavs.byteInputStream(), "Want to go.csv")
        assertEquals("Want to go", csv.lists.single().listName)
        assertEquals(2, csv.placesAdded)
        val json = importer.importFile(savedJson.byteInputStream(), "Saved Places.json")
        assertEquals(2, json.placesAdded)
        assertEquals(1, json.noCoordinates)
    }

    @Test fun unreadableInputs() {
        val bad = importer.importFile("<html/>".byteInputStream(), "page.json")
        assertTrue(bad.isEmpty)
        assertEquals(1, bad.filesFailed)
        assertTrue(importer.importZip("not a zip".byteInputStream()).isEmpty)
        val empty = importer.importZip(zip("a/readme.txt" to "hi").inputStream())
        assertTrue(empty.isEmpty)
        assertEquals(1, empty.filesIgnored)
        assertEquals(0, repo.lists().size)
    }
}
