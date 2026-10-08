package com.qtekfun.ultimatemaps.core.bikeshare

import com.qtekfun.ultimatemaps.core.cameras.LatLonBounds
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BikeShareFileTest {
    /** `sample.bin` is written by `scripts/build-bikeshare.py` (two systems, 3 + 2 stations): this checks the contract between the two languages. */
    private fun sample() = javaClass.getResourceAsStream("/bikeshare/sample.bin")!!.readBytes()

    @Test fun readsTheFileTheBuildScriptWrites() {
        val d = BikeShareFile.parse(sample())
        assertEquals(1_791_460_800L, d.generatedAtEpochSeconds)
        assertEquals(listOf("bicing", "bicimad"), d.systems.map { it.id })
        assertTrue(d.systems.all { it.attribution.contains("CC BY 4.0") && it.name.isNotBlank() })
        assertEquals(5, d.stations.size)
        val callao = d.stations.first { it.stationId == "1406" }
        assertEquals("2 - Metro Callao", callao.name)
        assertEquals("bicimad", callao.system.id)
        assertEquals(27, callao.capacity)
        assertEquals(40.4204, callao.location.lat, 1e-6)
        assertEquals(-3.7057, callao.location.lon, 1e-6)
        assertEquals("Cañón", d.stations.first { it.stationId == "1407" }.name, "UTF-8 survives")
        assertEquals(0, d.stations.first { it.name == "Pl. Catalunya" }.capacity, "unknown capacity is 0")
        assertEquals(5, d.stations.map { it.id }.toSet().size, "ids are unique across systems")
    }

    @Test fun repositoryAnswersViewportQueriesAndLookups() {
        val repo = BikeShareRepository()
        assertNull(repo.generatedMillis.value)
        repo.install(BikeShareFile.parse(sample()))
        assertEquals(1_791_460_800_000L, repo.generatedMillis.value)
        val barcelona = repo.stationsIn(LatLonBounds(41.0, 2.0, 42.0, 3.0), 10)
        assertEquals(3, barcelona.size)
        assertTrue(barcelona.all { it.system.id == "bicing" })
        assertEquals(1, repo.stationsIn(LatLonBounds(41.395, 2.17, 41.4, 2.19), 10).size, "narrow box")
        assertEquals(emptyList(), repo.stationsIn(LatLonBounds(0.0, 0.0, 1.0, 1.0), 10))
        val capped = repo.stationsIn(LatLonBounds(41.0, 2.0, 42.0, 3.0), 2)
        assertEquals(2, capped.size)
        assertEquals(41.41, capped.first().location.lat, 1e-6, "the northernmost are kept")
        val s = barcelona.first()
        assertEquals(s, repo.station(s.id))
        assertNull(repo.station("nope"))
        repo.install(null)
        assertTrue(repo.data.stations.isEmpty())
        assertNull(repo.generatedMillis.value)
    }

    private fun build(
        systems: Int = 1, stations: Int = 1, magic: Int = BikeShareFile.MAGIC, version: Int = BikeShareFile.VERSION, trailing: Int = 0,
        lat: Int = 41_400_000, systemId: String = "s",
    ): ByteArray {
        val bo = ByteArrayOutputStream()
        val o = DataOutputStream(bo)
        o.writeInt(magic); o.writeShort(version); o.writeLong(1L); o.writeShort(systems)
        repeat(systems) {
            o.writeUTF(systemId); o.writeUTF("Name"); o.writeUTF("Credit CC BY 4.0"); o.writeInt(stations)
            repeat(stations) { i -> o.writeUTF("st$i"); o.writeUTF("Station $i"); o.writeInt(lat); o.writeInt(2_180_000); o.writeShort(20) }
        }
        repeat(trailing) { o.writeByte(0) }
        val body = bo.toByteArray()
        val crc = CRC32().apply { update(body) }.value
        return body + ByteArray(4) { i -> (crc shr (24 - 8 * i)).toByte() }
    }

    @Test fun aHandBuiltFileParses() {
        val d = BikeShareFile.parse(build(systems = 2, stations = 3))
        assertEquals(6, d.stations.size)
        assertEquals(setOf("0:st0", "0:st1", "0:st2", "1:st0", "1:st1", "1:st2"), d.stations.map { it.id }.toSet())
    }

    @Test fun damagedFilesAreRejectedAsAWhole() {
        assertFailsWith<BikeShareFileException> { BikeShareFile.parse(ByteArray(3)) }
        assertFailsWith<BikeShareFileException> { BikeShareFile.parse(build(magic = 0x12345678)) }
        assertFailsWith<BikeShareFileException> { BikeShareFile.parse(build(version = 2)) }
        assertFailsWith<BikeShareFileException> { BikeShareFile.parse(build(trailing = 1)) }
        assertFailsWith<BikeShareFileException> { BikeShareFile.parse(build(lat = 95_000_000)) }
        assertFailsWith<BikeShareFileException> { BikeShareFile.parse(build(systemId = "")) }
        val ok = build()
        assertFailsWith<BikeShareFileException>("flipped byte") { BikeShareFile.parse(ok.copyOf().also { it[20] = (it[20] + 1).toByte() }) }
        assertFailsWith<BikeShareFileException>("truncated") { BikeShareFile.parse(ok.copyOf(ok.size - 9)) }
        assertFailsWith<BikeShareFileException>("too big") { BikeShareFile.parse(ByteArray(BikeShareFile.MAX_BYTES + 1)) }
        assertNotNull(BikeShareFile.parse(ok))
    }
}
