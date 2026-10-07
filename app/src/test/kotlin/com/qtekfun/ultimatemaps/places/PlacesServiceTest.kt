package com.qtekfun.ultimatemaps.places

import androidx.sqlite.driver.AndroidSQLiteDriver
import com.qtekfun.ultimatemaps.core.data.SqlitePlacesRepository
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.link.LinkHandler
import com.qtekfun.ultimatemaps.link.LinkOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M3 on the JVM: the real repository on the Android SQLite driver (framework) under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlacesServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class MemorySetting : LongSetting {
        var value: Long? = null
        override fun get() = value
        override fun set(value: Long) { this.value = value }
    }

    private val setting = MemorySetting()
    private val dbFile by lazy { tmp.root.resolve("places.db").path }

    private fun repo() = SqlitePlacesRepository(AndroidSQLiteDriver(), dbFile)
    private fun service(r: SqlitePlacesRepository = repo()) = PlacesService(r, DefaultList(r, setting) { "Favorites" })

    private val sol = PlaceInfo("Puerta del Sol", LatLon(40.41689, -3.70351), "Plaza Puerta del Sol, Madrid", "Square")
    private val atocha = PlaceInfo("Estación de Atocha", LatLon(40.40654, -3.68927), null, "Station")
    private val retiro = PlaceInfo("Parque del Retiro", LatLon(40.41531, -3.68442), null, "Park")

    @Test
    fun savingGoesToTheDefaultListAndSurvivesReopeningTheDatabase() {
        val r1 = repo()
        val saved = service(r1).save(sol)
        assertEquals("Favorites", saved.listName)
        assertTrue(saved.created)
        r1.close()

        val s2 = service() // a new connection on the same file, as after a restart
        val lists = s2.lists()
        assertEquals(listOf("Favorites"), lists.map { it.name })
        assertEquals(1, lists.single().placeCount)
        assertNotNull(s2.savedId(sol))
        assertNull(s2.savedId(atocha))
    }

    @Test
    fun savingTheSamePlaceTwiceDoesNotDuplicateAndUnsaveRemovesIt() {
        val s = service()
        val first = s.save(sol)
        val again = s.save(sol)
        assertFalse(again.created)
        assertEquals(first.placeId, again.placeId)
        assertEquals(1, s.rows(null, null, null, false).size)
        assertTrue(s.unsave(first.placeId))
        assertNull(s.savedId(sol))
    }

    @Test
    fun theDefaultListIsCreatedAgainIfTheUserDeletedIt() {
        val s = service()
        val firstId = s.lists().single().id
        s.deleteList(firstId)
        val saved = s.save(sol)
        assertEquals("Favorites", saved.listName)
        assertEquals(1, s.lists().single().placeCount)
    }

    @Test
    fun placesSortByDistanceFromAReferencePointOrByName() {
        val s = service()
        listOf(retiro, sol, atocha).forEach { s.save(it) }
        val me = LatLon(40.4168, -3.7038) // next to Sol
        val byDistance = s.rows(null, null, me, byDistance = true)
        assertEquals(listOf(sol.name, retiro.name, atocha.name), byDistance.map { it.place.name })
        assertTrue(byDistance.zipWithNext().all { (a, b) -> a.distanceMeters!! <= b.distanceMeters!! })
        assertEquals(
            listOf(atocha.name, retiro.name, sol.name),
            s.rows(null, null, me, byDistance = false).map { it.place.name },
        )
    }

    @Test
    fun searchingInsideAListIsAccentAndCaseInsensitiveAndRestrictedToThatList() {
        val s = service()
        s.save(atocha)
        val other = s.createList("Parques")!!
        val parkId = s.save(retiro).placeId
        s.removeFromList(s.lists().first { it.name == "Favorites" }.id, parkId)
        assertEquals(listOf(atocha.name), s.rows(null, "ESTACION", null, false).map { it.place.name })
        assertEquals(emptyList(), s.rows(other, "estacion", null, false))
        assertEquals(listOf(retiro.name), s.rows(null, "retiro", null, false).map { it.place.name })
    }

    @Test
    fun importsGpxIntoTheDefaultListAndReportsDuplicates() {
        val s = service()
        val gpx = """<?xml version="1.0"?><gpx version="1.1" creator="t" xmlns="http://www.topografix.com/GPX/1/1">
            <wpt lat="40.4168" lon="-3.7038"><name>Sol</name></wpt>
            <wpt lat="41.3874" lon="2.1686"><name>Barcelona</name></wpt></gpx>"""
        val r = assertNotNull(s.import(gpx.byteInputStream(), "ruta.gpx", null))
        assertEquals(2, r.placesAdded)
        val again = assertNotNull(s.import(gpx.byteInputStream(), "ruta.gpx", null))
        assertEquals(0, again.placesAdded)
        assertEquals(2, again.placesDuplicate)
        assertEquals(2, s.lists().single().placeCount)
    }

    @Test
    fun exportedGpxAndKmlImportBackIntoAnotherList() {
        val s = service()
        s.save(sol); s.save(atocha)
        for (format in listOf(GeoFormat.GPX, GeoFormat.KML)) {
            val out = ByteArrayOutputStream()
            val listId = s.lists().single().id
            assertEquals(2, s.export(format, listId, out))
            val target = service(repo().also { it.clearAll() })
            val r = assertNotNull(target.import(out.toByteArray().inputStream(), "x.${format.extension}", null))
            assertEquals(2, r.placesAdded, "format $format")
        }
    }

    @Test
    fun anUnreadableOrOversizedFileIsRejected() {
        val s = service()
        assertNull(s.import("hello".byteInputStream(), "notes.txt", null))
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { o ->
        java.util.zip.ZipOutputStream(o).use { z ->
            for ((name, text) in entries) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
    }.toByteArray()

    @Test
    fun importAnyRoutesTakeoutZipAndCsvAwayFromTheKmzPath() {
        val s = service()
        val csv = "Title,Note,URL\nA,,\"https://maps.google.com/?q=40.4,-3.7\"\nB,,https://maps.google.com/?cid=1\n"
        val takeout = assertIs<ImportOutcome.Takeout>(
            s.importAny(zipOf("Takeout/Saved/Trip.csv" to csv).inputStream(), "takeout-0001.zip", null),
        )
        assertEquals(1, takeout.summary.placesAdded)
        assertEquals(1, takeout.summary.noCoordinates)
        val single = assertIs<ImportOutcome.Takeout>(s.importAny(csv.byteInputStream(), "Trip.csv", null))
        assertEquals(1, single.summary.placesDuplicate)
        assertEquals(1, s.lists().count { it.name == "Trip" })

        val kml = "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Placemark><name>K</name><Point><coordinates>-3.7,40.4,0</coordinates></Point></Placemark></kml>"
        val geo = assertIs<ImportOutcome.Geo>(s.importAny(zipOf("doc.kml" to kml).inputStream(), "x.zip", null))
        assertEquals(1, geo.result.placesAdded)
        assertNull(s.importAny("hello".byteInputStream(), "notes.txt", null))
    }

    @Test
    fun formatIsDetectedFromTheNameThenFromTheContent() {
        assertEquals(GeoFormat.GPX, GeoFormat.detect("a.GPX", ByteArray(0)))
        assertEquals(GeoFormat.KMZ, GeoFormat.detect("a.kmz", ByteArray(0)))
        assertEquals(GeoFormat.KML, GeoFormat.detect(null, "<?xml?><kml>".toByteArray()))
        assertEquals(GeoFormat.GPX, GeoFormat.detect("download", "<gpx version='1.1'>".toByteArray()))
        assertEquals(GeoFormat.KMZ, GeoFormat.detect("download", byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)))
        assertNull(GeoFormat.detect("download", "plain".toByteArray()))
    }

    @Test
    fun sharedGeoUriIsReadBackByTheLinkHandler() {
        val uri = GeoShare.uri(sol.point, "Café (Sol) & más")
        assertTrue(uri.startsWith("geo:0,0?q=40.416890,-3.703510("), uri)
        val outcome = assertIs<LinkOutcome.ShowPlace>(LinkHandler.handle(uri))
        assertEquals(40.41689, outcome.point.lat, 1e-6)
        assertEquals(-3.70351, outcome.point.lon, 1e-6)
        assertEquals("Café [Sol] & más", outcome.label)
        assertEquals("geo:40.416890,-3.703510", GeoShare.uri(sol.point, null))
    }

    @Test
    fun distancesAreHumanReadable() {
        assertEquals("0 m", formatDistance(-3.0))
        assertEquals("847 m", formatDistance(847.4))
        assertEquals("1.2 km", formatDistance(1234.0))
        assertEquals("1,2 km", formatDistance(1234.0, decimalComma = true))
        assertEquals("12 km", formatDistance(12_400.0))
    }

    @Test
    fun subtitleSkipsMissingParts() {
        assertEquals("Cafe · Calle Mayor 1", subtitleOf("Cafe", "Calle Mayor 1"))
        assertEquals("Cafe", subtitleOf("Cafe", null))
        assertNull(subtitleOf(" ", ""))
    }
}
