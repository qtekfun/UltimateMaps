package com.qtekfun.ultimatemaps.core.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.io.GpxExporter
import com.qtekfun.ultimatemaps.core.geo.io.KmlExporter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Home / Work / parked car, recent searches, the v1 to v2 migration and list customisation round trips. */
class PersonalDataTest {
    private val repo = SqlitePlacesRepository(BundledSQLiteDriver(), ":memory:")

    @AfterTest fun tearDown() = repo.close()

    private val madrid = LatLon(40.4168, -3.7038)
    private val toledo = LatLon(39.8628, -4.0273)

    @Test fun specialSlotsAreIndependentAndReplaceable() {
        assertNull(repo.special(SpecialSlot.HOME))
        repo.setSpecial(SpecialSlot.HOME, "Casa", madrid, savedAt = 10)
        repo.setSpecial(SpecialSlot.PARKING, "Parked", toledo, savedAt = 20)
        assertEquals(SpecialPlace(SpecialSlot.HOME, "Casa", madrid, 10), repo.special(SpecialSlot.HOME))
        assertNull(repo.special(SpecialSlot.WORK))
        repo.setSpecial(SpecialSlot.HOME, "Casa 2", toledo, savedAt = 30)
        assertEquals("Casa 2", repo.special(SpecialSlot.HOME)!!.name)
        assertEquals(toledo, repo.special(SpecialSlot.HOME)!!.point)
        assertTrue(repo.clearSpecial(SpecialSlot.PARKING))
        assertFalse(repo.clearSpecial(SpecialSlot.PARKING))
        assertNull(repo.special(SpecialSlot.PARKING))
        assertEquals("Casa 2", repo.special(SpecialSlot.HOME)!!.name)
    }

    @Test fun specialPlacesAreNotListedAsPlaces() {
        repo.setSpecial(SpecialSlot.HOME, "Casa", madrid)
        assertTrue(repo.places().isEmpty())
        repo.clearAll() // a "replace" restore must not wipe Home
        assertEquals("Casa", repo.special(SpecialSlot.HOME)!!.name)
    }

    @Test fun recentSearchesAreNewestFirstDeduplicatedAndBounded() {
        repo.addSearch("  ", at = 1)
        repo.addSearch("café", at = 1)
        repo.addSearch("farmacia", at = 2)
        repo.addSearch("CAFE", at = 3) // same text ignoring case and accents: moves to the top, keeps the new spelling
        assertEquals(listOf("CAFE", "farmacia"), repo.recentSearches())
        for (i in 0 until 30) repo.addSearch("q$i", at = 100L + i, keep = 5)
        assertEquals(listOf("q29", "q28", "q27", "q26", "q25"), repo.recentSearches())
        assertEquals(listOf("q29", "q28"), repo.recentSearches(limit = 2))
        repo.clearSearches()
        assertTrue(repo.recentSearches().isEmpty())
    }

    @Test fun sameTimestampKeepsInsertionOrder() {
        repo.addSearch("a", at = 5); repo.addSearch("b", at = 5); repo.addSearch("c", at = 5)
        assertEquals(listOf("c", "b", "a"), repo.recentSearches())
    }

    @Test fun migrationFromV1KeepsTheDataAndAddsTheNewTables() {
        val file = File.createTempFile("places-v1", ".db").also { it.deleteOnExit() }
        val old = BundledSQLiteDriver().open(file.path)
        SqlitePlacesRepository.SCHEMA_V1.forEach { old.execSQL(it) }
        old.execSQL("PRAGMA user_version = 1")
        old.execSQL("INSERT INTO places(name,lat,lon,created_at,updated_at,dedup_key,search_text) VALUES('Sol',40.4,-3.7,1,1,'k','sol')")
        old.execSQL("INSERT INTO lists(name,color,icon,notes,created_at) VALUES('Viaje',255,'star','verano',1)")
        old.close()

        SqlitePlacesRepository(BundledSQLiteDriver(), file.path).use { migrated ->
            assertEquals(listOf("Sol"), migrated.places().map { it.name })
            assertEquals("verano", migrated.lists().single().notes)
            migrated.setSpecial(SpecialSlot.WORK, "Oficina", madrid)
            migrated.addSearch("gasolinera")
            assertEquals("Oficina", migrated.special(SpecialSlot.WORK)!!.name)
        }
        // Opening it again is a no-op migration and keeps what v2 stored.
        SqlitePlacesRepository(BundledSQLiteDriver(), file.path).use {
            assertEquals(listOf("gasolinera"), it.recentSearches())
            assertEquals("Oficina", it.special(SpecialSlot.WORK)!!.name)
        }
    }

    @Test fun listCustomisationRoundTrips() {
        val id = repo.addList("Viaje")
        assertTrue(repo.updateList(repo.getList(id)!!.copy(icon = "🏖️", color = 0xFF34C759.toInt(), notes = "Verano 2027")))
        val l = repo.getList(id)!!
        assertEquals("🏖️", l.icon); assertEquals(0xFF34C759.toInt(), l.color); assertEquals("Verano 2027", l.notes)
        assertTrue(repo.updateList(l.copy(icon = null, color = null, notes = null)))
        val cleared = repo.getList(id)!!
        assertNull(cleared.icon); assertNull(cleared.color); assertNull(cleared.notes)
    }

    @Test fun backupKeepsListStyleAndHomeAndWork() {
        val l = repo.addList("Viaje", color = 0xFFFF9500.toInt(), icon = "🏖️", notes = "Verano")
        repo.addToList(l, repo.addPlace("Playa", toledo))
        repo.setSpecial(SpecialSlot.HOME, "Casa", madrid, savedAt = 7)
        repo.setSpecial(SpecialSlot.WORK, "Oficina", toledo, savedAt = 8)
        repo.setSpecial(SpecialSlot.PARKING, "Coche", madrid, savedAt = 9)
        val out = ByteArrayOutputStream().also { BackupService(repo).write(it) }

        SqlitePlacesRepository(BundledSQLiteDriver(), ":memory:").use { fresh ->
            BackupService(fresh).restore(ByteArrayInputStream(out.toByteArray()))
            val list = fresh.lists().single()
            assertEquals("🏖️", list.icon); assertEquals(0xFFFF9500.toInt(), list.color); assertEquals("Verano", list.notes)
            assertEquals("Casa", fresh.special(SpecialSlot.HOME)!!.name)
            assertEquals("Oficina", fresh.special(SpecialSlot.WORK)!!.name)
            assertNull(fresh.special(SpecialSlot.PARKING)) // the parked car is not part of the backup
        }
    }

    @Test fun mergeKeepsAnExistingHomeButReplaceOverwritesIt() {
        repo.setSpecial(SpecialSlot.HOME, "Casa", madrid)
        val out = ByteArrayOutputStream().also { BackupService(repo).write(it) }
        SqlitePlacesRepository(BundledSQLiteDriver(), ":memory:").use { other ->
            other.setSpecial(SpecialSlot.HOME, "Mi casa", toledo)
            BackupService(other).restore(ByteArrayInputStream(out.toByteArray()))
            assertEquals("Mi casa", other.special(SpecialSlot.HOME)!!.name)
            BackupService(other).restore(ByteArrayInputStream(out.toByteArray()), RestoreMode.REPLACE)
            assertEquals("Casa", other.special(SpecialSlot.HOME)!!.name)
        }
    }

    @Test fun aBackupWithoutTheSpecialFieldStillRestores() {
        val json = """{"format":1,"places":[],"lists":[{"name":"Viejo","icon":"star"}],"tracks":[]}"""
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                z.putNextEntry(ZipEntry("mapas-backup.json")); z.write(json.toByteArray()); z.closeEntry()
            }
        }
        BackupService(repo).restore(ByteArrayInputStream(zip.toByteArray()))
        assertEquals("Viejo", repo.lists().single().name)
        assertNull(repo.special(SpecialSlot.HOME))
    }

    @Test fun gpxAndKmlExportsStillWorkWithStyledListsAndSpecialPlaces() {
        val l = repo.addList("Viaje", color = 1, icon = "🏖️", notes = "n")
        repo.addToList(l, repo.addPlace("Playa", toledo))
        repo.setSpecial(SpecialSlot.HOME, "Casa", madrid)
        val transfer = GeoDataTransfer(repo)
        val gpx = ByteArrayOutputStream().also { transfer.export(GpxExporter, it, l) }.toString(Charsets.UTF_8)
        val kml = ByteArrayOutputStream().also { transfer.export(KmlExporter, it, l) }.toString(Charsets.UTF_8)
        assertTrue("Playa" in gpx && "Casa" !in gpx)
        assertTrue("Playa" in kml && "Casa" !in kml)
    }
}
