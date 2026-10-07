package com.qtekfun.ultimatemaps.core.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.io.PathKind
import com.qtekfun.ultimatemaps.core.geo.io.TrackPoint
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacesRepositoryTest {
    private val repo: PlacesRepository = SqlitePlacesRepository(BundledSQLiteDriver(), ":memory:")

    @AfterTest fun tearDown() = repo.close()

    private val madrid = LatLon(40.4168, -3.7038)
    private val barcelona = LatLon(41.3851, 2.1734)
    private val toledo = LatLon(39.8628, -4.0273)

    @Test fun createEditDeletePlace() {
        val id = repo.addPlace("Casa", madrid, notes = "llave en el buzón", icon = "home", color = 0xFFFF0000.toInt())
        val p = repo.getPlace(id)!!
        assertEquals("Casa", p.name); assertEquals(madrid, p.point); assertEquals("home", p.icon)
        assertEquals(0xFFFF0000.toInt(), p.color)
        assertTrue(repo.updatePlace(p.copy(name = "Casa 2", notes = null, color = null, point = toledo)))
        val q = repo.getPlace(id)!!
        assertEquals("Casa 2", q.name); assertNull(q.notes); assertNull(q.color); assertEquals(toledo, q.point)
        assertTrue(repo.deletePlace(id))
        assertNull(repo.getPlace(id))
        assertFalse(repo.deletePlace(id))
    }

    @Test fun listsWithMembershipAndCascade() {
        val a = repo.addPlace("A", madrid); val b = repo.addPlace("B", barcelona)
        val l = repo.addList("Viaje", color = 0xFF00FF00.toInt(), icon = "star", notes = "verano")
        assertTrue(repo.addToList(l, a)); assertTrue(repo.addToList(l, b)); assertFalse(repo.addToList(l, b))
        assertEquals(2, repo.getList(l)!!.placeCount)
        assertEquals(listOf("Viaje"), repo.listsOf(a).map { it.name })
        assertTrue(repo.updateList(repo.getList(l)!!.copy(name = "Viaje 2", notes = null)))
        assertEquals("Viaje 2", repo.lists().single().name)
        repo.deletePlace(a) // membership goes with the place
        assertEquals(listOf("B"), repo.places(listId = l).map { it.name })
        repo.removeFromList(l, b)
        assertEquals(0, repo.getList(l)!!.placeCount)
        repo.deleteList(l)
        assertTrue(repo.lists().isEmpty())
        assertEquals(1, repo.places().size) // deleting a list keeps the places
    }

    @Test fun sortByDistance() {
        repo.addPlace("Barcelona", barcelona); repo.addPlace("Madrid", madrid); repo.addPlace("Toledo", toledo)
        assertEquals(listOf("Toledo", "Madrid", "Barcelona"), repo.places(near = toledo).map { it.name })
        assertEquals(listOf("Barcelona", "Madrid", "Toledo"), repo.places(near = LatLon(41.5, 2.0)).map { it.name })
        assertEquals(listOf("Barcelona", "Madrid", "Toledo"), repo.places().map { it.name }) // by name
    }

    @Test fun textSearchWithinListsIgnoresCaseAndAccents() {
        val l1 = repo.addList("Comida"); val l2 = repo.addList("Otros")
        val a = repo.addPlace("Café Central", madrid, notes = "buen café con leche")
        val b = repo.addPlace("Cafetería 100%", barcelona)
        val c = repo.addPlace("Museo del Prado", madrid.copy(lat = 40.41))
        repo.addToList(l1, a); repo.addToList(l1, b); repo.addToList(l2, c)
        assertEquals(setOf("Café Central", "Cafetería 100%"), repo.places(listId = l1, query = "CAFE").map { it.name }.toSet())
        assertEquals(listOf("Café Central"), repo.places(listId = l1, query = "leche cafe").map { it.name })
        assertEquals(listOf("Cafetería 100%"), repo.places(query = "100%").map { it.name }) // % is literal
        assertTrue(repo.places(query = "_").isEmpty())
        assertTrue(repo.places(listId = l2, query = "cafe").isEmpty())
        assertEquals(listOf("Museo del Prado"), repo.places(listId = l2, query = "prado", near = madrid).map { it.name })
    }

    @Test fun tracksRoundTripThroughDatabase() {
        val segs = listOf(
            listOf(TrackPoint(LatLon(40.0, -3.0), 600.0, 1714554000000), TrackPoint(LatLon(40.001, -3.001))),
            listOf(TrackPoint(LatLon(40.002, -3.002), null, 5)),
        )
        val id = repo.addTrack("Paseo", PathKind.TRACK, segs, notes = "domingo", color = 7)
        val t = repo.track(id)!!
        assertEquals(segs, t.segments)
        assertEquals(3, t.info.pointCount); assertEquals("domingo", t.info.notes); assertEquals(7, t.info.color)
        assertEquals(listOf("Paseo"), repo.tracks(query = "paseo domingo").map { it.name })
        assertTrue(repo.tracks(query = "nada").isEmpty())
        assertTrue(repo.updateTrack(t.info.copy(name = "Paseo 2", notes = null)))
        assertEquals("Paseo 2", repo.tracks().single().name)
        assertTrue(repo.deleteTrack(id))
        assertNull(repo.track(id))
        assertTrue(repo.tracks().isEmpty())
    }

    @Test fun largeTrack() {
        val pts = (0 until 10_000).map { TrackPoint(LatLon(40 + it * 1e-5, -3 + it * 1e-5), 600.0, it.toLong()) }
        val id = repo.addTrack("grande", PathKind.TRACK, listOf(pts))
        assertEquals(pts, repo.track(id)!!.segments.single())
    }

    @Test fun transactionRollsBack() {
        assertFailsWith<IllegalStateException> {
            repo.transaction { repo.addPlace("X", madrid); error("boom") }
        }
        assertTrue(repo.places().isEmpty())
        repo.transaction { repo.addPlace("Y", madrid) }
        assertEquals(1, repo.places().size)
    }

    @Test fun addIfNewDeduplicates() {
        val a = repo.addPlaceIfNew("Sol", madrid)
        val b = repo.addPlaceIfNew("  SOL ", LatLon(40.416801, -3.703799)) // same name folded, within ~1 m
        val c = repo.addPlaceIfNew("Sol", barcelona)
        assertTrue(a.created); assertFalse(b.created); assertEquals(a.id, b.id); assertTrue(c.created)
    }

    @Test fun persistsAndReopensFileDatabase() {
        val f = java.io.File.createTempFile("mapas", ".db")
        try {
            SqlitePlacesRepository(BundledSQLiteDriver(), f.path).use { it.addPlace("Persistente", madrid) }
            SqlitePlacesRepository(BundledSQLiteDriver(), f.path).use { assertEquals("Persistente", it.places().single().name) }
        } finally { f.delete() }
    }
}
