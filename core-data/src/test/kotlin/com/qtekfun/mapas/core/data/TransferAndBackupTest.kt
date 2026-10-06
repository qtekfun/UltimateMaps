package com.qtekfun.mapas.core.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.geo.io.GpxExporter
import com.qtekfun.mapas.core.geo.io.KmlExporter
import com.qtekfun.mapas.core.geo.io.PathKind
import com.qtekfun.mapas.core.geo.io.TrackPoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransferAndBackupTest {
    private val repo = SqlitePlacesRepository(BundledSQLiteDriver(), ":memory:")
    private val transfer = GeoDataTransfer(repo)

    @AfterTest fun tearDown() = repo.close()

    private fun gpx() = """<?xml version="1.0"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
        <wpt lat="40.4168" lon="-3.7038"><name>Sol</name><desc>km 0</desc></wpt>
        <wpt lat="41.3851" lon="2.1734"><name>Barcelona</name></wpt>
        <wpt lat="99" lon="2"><name>mal</name></wpt>
        <trk><name>Paseo</name><trkseg><trkpt lat="40.0" lon="-3.0"/><trkpt lat="40.001" lon="-3.001"/></trkseg></trk>
        </gpx>""".byteInputStream()

    @Test fun importGpxIntoListAndDeduplicate() {
        val list = repo.addList("Importados")
        val r1 = transfer.importGpx(gpx(), list)
        assertEquals(ImportResult(2, 0, 1, 0, 1), r1)
        val r2 = transfer.importGpx(gpx(), list)
        assertEquals(ImportResult(0, 2, 0, 1, 1), r2)
        assertEquals(2, repo.places().size); assertEquals(1, repo.tracks().size)
        assertEquals(2, repo.getList(list)!!.placeCount)
    }

    @Test fun importKmlKmzTakeoutAndCsv() {
        val kml = """<kml xmlns="http://www.opengis.net/kml/2.2"><Document>
            <Placemark><name>K</name><Point><coordinates>-3.7,40.4,0</coordinates></Point></Placemark></Document></kml>"""
        assertEquals(1, transfer.importKml(kml.byteInputStream()).placesAdded)
        val kmz = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("doc.kml")); z.write(kml.replace("K<", "Z<").toByteArray()); z.closeEntry() }
        }.toByteArray()
        assertEquals(1, transfer.importKmz(ByteArrayInputStream(kmz)).placesAdded)
        val geo = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[2.17,41.38]},
            "properties":{"Title":"T","Location":{"Business Name":"T"}}}]}"""
        val tk = transfer.importTakeoutGeoJson(geo.byteInputStream())
        assertEquals(1, tk.placesAdded)
        // CSV rows carry no coordinates: counted as skipped, nothing stored.
        val csv = "Title,Note,URL,Comment\nSin coords,,https://www.google.com/maps/place/x,\n"
        val c = transfer.importTakeoutCsv(csv.byteInputStream())
        assertEquals(0, c.placesAdded); assertEquals(1, c.skipped)
        assertEquals(3, repo.places().size)
    }

    @Test fun exportThenReimportIsStableInBothFormats() {
        transfer.importGpx(gpx())
        for (exporter in listOf(GpxExporter, KmlExporter)) {
            val out = ByteArrayOutputStream(); transfer.export(exporter, out)
            val fresh = SqlitePlacesRepository(BundledSQLiteDriver(), ":memory:")
            fresh.use {
                val bytes = out.toByteArray()
                val r = if (exporter === GpxExporter) GeoDataTransfer(it).importGpx(bytes.inputStream())
                else GeoDataTransfer(it).importKml(bytes.inputStream())
                assertEquals(ImportResult(2, 0, 1, 0, 0), r)
                assertEquals(repo.places().map { p -> p.name to p.point }, it.places().map { p -> p.name to p.point })
            }
        }
    }

    @Test fun exportSingleList() {
        val l = repo.addList("L")
        repo.addToList(l, repo.addPlace("Dentro", LatLon(1.0, 1.0))); repo.addPlace("Fuera", LatLon(2.0, 2.0))
        val doc = transfer.toDocument(l)
        assertEquals(listOf("Dentro"), doc.places.map { it.name }); assertTrue(doc.paths.isEmpty())
    }

    private fun populate() {
        val a = repo.addPlace("Casa", LatLon(40.4, -3.7), "notas", "home", 0xFF112233.toInt())
        val b = repo.addPlace("Trabajo", LatLon(40.5, -3.6))
        val l = repo.addList("Favoritos", 5, "star", "mis sitios")
        repo.addList("Vacía")
        repo.addToList(l, a); repo.addToList(l, b)
        repo.addTrack("Ruta", PathKind.TRACK, listOf(listOf(TrackPoint(LatLon(1.0, 2.0), 3.0, 4), TrackPoint(LatLon(1.1, 2.1))), listOf(TrackPoint(LatLon(5.0, 6.0)))), "n", 9)
        repo.addTrack("Camino", PathKind.ROUTE, listOf(listOf(TrackPoint(LatLon(7.0, 8.0)))))
    }

    private fun state(r: PlacesRepository) = listOf(
        r.places().map { listOf(it.name, it.point, it.notes, it.icon, it.color) },
        r.lists().map { l -> listOf(l.name, l.color, l.icon, l.notes, r.places(listId = l.id).map { it.name }) },
        r.tracks().map { t -> r.track(t.id)!!.let { listOf(t.name, t.kind, t.notes, t.color, it.segments) } },
    )

    @Test fun backupRestoreReplaceRebuildsEverything() {
        populate()
        val out = ByteArrayOutputStream(); BackupService(repo).write(out)
        val expected = state(repo)
        SqlitePlacesRepository(BundledSQLiteDriver(), ":memory:").use { other ->
            other.addPlace("Basura", LatLon(0.0, 0.0))
            val r = BackupService(other).restore(out.toByteArray().inputStream(), RestoreMode.REPLACE)
            assertEquals(ImportResult(2, 0, 2, 0, 0), r)
            assertEquals(expected, state(other))
        }
    }

    @Test fun backupRestoreMergeIsIdempotent() {
        populate()
        val out = ByteArrayOutputStream(); BackupService(repo).write(out)
        val expected = state(repo)
        val r = BackupService(repo).restore(out.toByteArray().inputStream(), RestoreMode.MERGE)
        assertEquals(ImportResult(0, 2, 0, 2, 0), r)
        assertEquals(expected, state(repo))
    }

    @Test fun invalidBackupsAreRejectedAndLeaveDataIntact() {
        populate()
        val expected = state(repo)
        val svc = BackupService(repo)
        assertFailsWith<BackupException> { svc.restore("not a zip".byteInputStream(), RestoreMode.REPLACE) }
        fun zipOf(json: String) = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("mapas-backup.json")); z.write(json.toByteArray()); z.closeEntry() }
        }.toByteArray().inputStream()
        assertFailsWith<BackupException> { svc.restore(zipOf("{broken"), RestoreMode.REPLACE) }
        assertFailsWith<BackupException> { svc.restore(zipOf("""{"format":99,"places":[],"lists":[],"tracks":[]}"""), RestoreMode.REPLACE) }
        // Fails halfway (bad coordinates in the 2nd place): the REPLACE wipe is rolled back.
        val bad = """{"format":1,"places":[{"id":1,"name":"a","lat":1,"lon":1},{"id":2,"name":"b","lat":999,"lon":1}],"lists":[],"tracks":[]}"""
        assertFailsWith<BackupException> { svc.restore(zipOf(bad), RestoreMode.REPLACE) }
        assertEquals(expected, state(repo))
    }

    @Test fun largeBackupRoundTrips() {
        val pts = (0 until 10_000).map { TrackPoint(LatLon(40 + it * 1e-5, -3 + it * 1e-5), 600.0, 1_700_000_000_000 + it) }
        repo.addTrack("grande", PathKind.TRACK, listOf(pts))
        val out = ByteArrayOutputStream(); BackupService(repo).write(out)
        SqlitePlacesRepository(BundledSQLiteDriver(), ":memory:").use { o ->
            BackupService(o).restore(out.toByteArray().inputStream())
            assertEquals(pts, o.track(o.tracks().single().id)!!.segments.single())
        }
    }
}
