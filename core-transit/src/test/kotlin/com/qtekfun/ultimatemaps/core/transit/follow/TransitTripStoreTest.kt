package com.qtekfun.ultimatemaps.core.transit.follow

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransitTripStoreTest {
    private val dir = java.nio.file.Files.createTempDirectory("transit-trip-test").toFile()
    private val file = File(dir, "trip/state.bin")
    private var now = 1_000_000_000L
    private fun store(maxAge: Long = TransitTripStore.DEFAULT_MAX_AGE_MILLIS) = TransitTripStore(file, { now }, maxAge)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val snapshot = FollowerSnapshot(1, true, 2.5, -30)

    @Test
    fun `an itinerary and the progress survive a round trip`() {
        val it = FollowFixtures.itinerary()
        assertTrue(store().save(it, snapshot, "Europe/Madrid"))
        val loaded = assertNotNull(store().load())
        assertEquals(it, loaded.itinerary)
        assertEquals(snapshot, loaded.snapshot)
        assertEquals("Europe/Madrid", loaded.zoneId)
    }

    @Test
    fun `a missing delay is kept missing`() {
        store().save(FollowFixtures.itinerary(), FollowerSnapshot(0, false, 0.0, null), "UTC")
        assertNull(store().load()!!.snapshot.delaySec)
    }

    @Test
    fun `nothing saved means nothing to resume`() {
        assertNull(store().load())
    }

    @Test
    fun `a trip older than three hours expires and its file is deleted`() {
        store().save(FollowFixtures.itinerary(), snapshot, "UTC")
        now += 3 * 60 * 60 * 1000L - 1
        assertNotNull(store().load())
        now += 2
        assertNull(store().load())
        assertFalse(file.exists())
    }

    @Test
    fun `a corrupt or truncated file is discarded`() {
        store().save(FollowFixtures.itinerary(), snapshot, "UTC")
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))
        assertNull(store().load())
        assertFalse(file.exists())
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(40) { 7 })
        assertNull(store().load())
    }

    @Test
    fun `a leg index outside the itinerary is rejected`() {
        store().save(FollowFixtures.itinerary(), FollowerSnapshot(99, false, 0.0, null), "UTC")
        assertNull(store().load())
    }

    @Test
    fun `clear removes the file`() {
        val s = store()
        s.save(FollowFixtures.itinerary(), snapshot, "UTC")
        s.clear()
        assertFalse(file.exists())
    }
}
