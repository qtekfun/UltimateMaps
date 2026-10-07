package com.qtekfun.ultimatemaps.core.chargers

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargerFileTest {
    /** Produced by `scripts/build-chargers.py` from the fixtures of `scripts/test_build_chargers.py` (cross-language check). */
    private fun sample() = javaClass.getResourceAsStream("/chargers/sample.bin")!!.readBytes()

    @Test fun readsTheFileWrittenByThePythonConverter() {
        val d = ChargerFile.parse(sample())
        assertEquals(1_791_374_400L, d.generatedAtEpochSeconds)
        assertEquals(ChargerSources.OSM, d.sourceFlags)
        assertEquals(6, d.chargers.size)
        val a = d.chargers[0]
        assertEquals(40.40, a.location.lat, 1e-6)
        assertEquals(-3.70, a.location.lon, 1e-6)
        assertEquals("Iberdrola", a.operator)
        assertEquals("Plaza Mayor", a.name)
        assertEquals(4, a.capacity)
        assertEquals(ChargerFee.PAID, a.fee)
        assertEquals(ChargerAccess.PUBLIC, a.access)
        assertEquals("24/7", a.openingHours)
        assertEquals(ChargerAuth.APP or ChargerAuth.NFC, a.authMask)
        assertEquals(listOf(ChargerSocket(SocketType.TYPE2, 2, 22.0), ChargerSocket(SocketType.CCS, 1, 150.0)), a.sockets)
        assertEquals(150.0, a.maxPowerKw)
        assertTrue(a.isFast)
        val b = d.chargers[1]
        assertEquals("Lidl", b.network)
        assertEquals("Lidl", b.title, "no operator: the network is the title")
        assertEquals(ChargerAccess.CUSTOMERS, b.access)
        assertEquals(ChargerFee.FREE, b.fee)
        assertEquals(ChargerSocket(SocketType.SCHUKO, 0, null), b.sockets.last())
        assertEquals(50.0, b.maxPowerKw)
        // the way became its bounding-box centre
        assertEquals(40.51, d.chargers[2].location.lat, 1e-6)
        val unknown = d.chargers[4]
        assertTrue(unknown.sockets.isEmpty())
        assertNull(unknown.maxPowerKw)
        assertEquals(ChargerFee.UNKNOWN, unknown.fee)
        assertEquals("c0", a.id)
        assertEquals("c5", d.chargers[5].id)
    }

    /**
     * Optional: point `UM_REAL_CHARGERS_FILE` at a `chargers-es.bin` built by `scripts/build-chargers.py` from real data.
     * Does nothing when it is not set, so the suite never depends on a file.
     */
    @Test fun readsTheRealConverterOutputWhenGiven() {
        val path = System.getenv("UM_REAL_CHARGERS_FILE") ?: return
        val d = ChargerFile.parse(java.io.File(path).readBytes())
        println("REAL_CHARGERS n=${d.chargers.size} withSockets=${d.chargers.count { it.sockets.isNotEmpty() }} withPower=${d.chargers.count { it.maxPowerKw != null }}")
        assertTrue(d.chargers.isNotEmpty())
    }

    @Test fun anyDamageIsRejectedAsAWhole() {
        val good = sample()
        assertFailsWith<ChargerFileException> { ChargerFile.parse(good.copyOf().also { it[20] = (it[20] + 1).toByte() }) }
        assertFailsWith<ChargerFileException> { ChargerFile.parse(good.copyOf(good.size - 7)) }
        assertFailsWith<ChargerFileException> { ChargerFile.parse(ByteArray(10)) }
        assertFailsWith<ChargerFileException> { ChargerFile.parse(ByteArray(0)) }
    }

    private fun build(magic: Int = ChargerFile.MAGIC, version: Int = 1, n: Int = 0, extra: (DataOutputStream) -> Unit = {}): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            writeInt(magic); writeShort(version); writeLong(1L); writeByte(1); writeInt(n)
            extra(this)
        }
        val crc = CRC32().apply { update(body.toByteArray()) }.value
        DataOutputStream(body).writeInt(crc.toInt())
        return body.toByteArray()
    }

    private fun record(o: DataOutputStream, lat: Int = 40_000_000, nSockets: Int = 0, fee: Int = 0, access: Int = 0, sockets: (DataOutputStream) -> Unit = {}) {
        o.writeInt(lat); o.writeInt(-3_000_000); o.writeByte(fee); o.writeByte(access); o.writeByte(0); o.writeShort(0); o.writeByte(nSockets)
        sockets(o)
        o.writeUTF(""); o.writeUTF(""); o.writeUTF(""); o.writeUTF("")
    }

    @Test fun wrongMagicVersionAndImpossibleCountsAreRejected() {
        assertEquals(0, ChargerFile.parse(build()).chargers.size)
        assertFailsWith<ChargerFileException> { ChargerFile.parse(build(magic = 1)) }
        assertFailsWith<ChargerFileException> { ChargerFile.parse(build(version = 2)) }
        assertFailsWith<ChargerFileException> { ChargerFile.parse(build(n = 1_000_000)) }
        assertFailsWith<ChargerFileException> { ChargerFile.parse(build(n = -1)) }
    }

    @Test fun anOutOfRangePositionOrTooManySocketsAreRejected() {
        assertFailsWith<ChargerFileException> { ChargerFile.parse(build(n = 1) { record(it, lat = 95_000_000) }) }
        assertFailsWith<ChargerFileException> { ChargerFile.parse(build(n = 1) { record(it, nSockets = 200) }) }
    }

    @Test fun trailingBytesAreRejected() {
        assertFailsWith<ChargerFileException> { ChargerFile.parse(build(n = 0) { it.writeByte(7) }) }
    }

    @Test fun unknownCodesDegradeInsteadOfFailing() {
        val bytes = build(n = 1) { o ->
            record(o, nSockets = 1, fee = 9, access = 9) { s -> s.writeByte(99); s.writeByte(3); s.writeShort(0) }
        }
        val c = ChargerFile.parse(bytes).chargers.single()
        assertEquals(ChargerFee.UNKNOWN, c.fee)
        assertEquals(ChargerAccess.UNKNOWN, c.access)
        assertEquals(SocketType.OTHER, c.sockets.single().type)
        assertNull(c.sockets.single().powerKw, "0 means unknown")
    }
}
