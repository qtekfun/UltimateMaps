package com.qtekfun.ultimatemaps.core.zbe

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZbeFileTest {
    /** `sample.bin` is written by `scripts/build-zbe.py` from two zones (see docs/phase2/zbe-data.md): this checks the contract between the two languages. */
    private fun sample() = javaClass.getResourceAsStream("/zbe/sample.bin")!!.readBytes()

    @Test fun readsTheFileTheBuildScriptWrites() {
        val d = ZbeFile.parse(sample())
        assertEquals(1_791_460_800L, d.generatedAtEpochSeconds)
        assertEquals(1, d.flags)
        assertEquals(2, d.zones.size)
        val centro = d.zones.first { it.name == "Distrito Centro" }
        assertEquals("Madrid", centro.city)
        assertEquals("Zona de bajas emisiones de especial protección", centro.restriction)
        assertEquals(listOf(2), centro.polygons.map { it.rings.size }, "outer ring and one hole")
        assertTrue(centro.contains(40.402, -3.698))
        assertTrue(!centro.contains(40.4125, -3.6875), "inside the hole")
        val rondes = d.zones.first { it.city == "Barcelona" }
        assertEquals(2, rondes.polygons.size)
        assertEquals("", rondes.restriction)
        assertTrue(rondes.contains(41.395, 2.115))
        assertTrue(rondes.contains(41.405, 2.205))
        assertTrue(!rondes.contains(41.395, 2.17))
    }

    private fun build(
        zones: Int = 1, points: Int = 4, magic: Int = ZbeFile.MAGIC, version: Int = ZbeFile.VERSION, trailing: Int = 0,
        lat: Int = 40_400_000, polygons: Int = 1, rings: Int = 1,
    ): ByteArray {
        val bo = ByteArrayOutputStream()
        val o = DataOutputStream(bo)
        o.writeInt(magic); o.writeShort(version); o.writeLong(1L); o.writeByte(1); o.writeInt(zones)
        repeat(zones) {
            o.writeUTF("Z"); o.writeUTF("C"); o.writeUTF("")
            o.writeByte(polygons)
            repeat(polygons) {
                o.writeByte(rings)
                repeat(rings) {
                    o.writeShort(points)
                    repeat(points) { i -> o.writeInt(lat + i * 1000); o.writeInt(-3_700_000 + (i % 2) * 5000) }
                }
            }
        }
        repeat(trailing) { o.writeByte(0) }
        val body = bo.toByteArray()
        val crc = CRC32().apply { update(body) }.value
        return body + ByteArray(4) { i -> (crc shr (24 - 8 * i)).toByte() }
    }

    @Test fun aHandBuiltFileParses() {
        val d = ZbeFile.parse(build())
        assertEquals(1, d.zones.size)
        assertEquals(4, d.zones[0].polygons[0].rings[0].size)
    }

    @Test fun damagedFilesAreRejectedAsAWhole() {
        assertFailsWith<ZbeFileException> { ZbeFile.parse(ByteArray(10)) }
        assertFailsWith<ZbeFileException> { ZbeFile.parse(build(magic = 0x12345678)) }
        assertFailsWith<ZbeFileException> { ZbeFile.parse(build(version = 2)) }
        assertFailsWith<ZbeFileException> { ZbeFile.parse(build(trailing = 3)) }
        assertFailsWith<ZbeFileException> { ZbeFile.parse(build(points = 2)) }
        assertFailsWith<ZbeFileException> { ZbeFile.parse(build(lat = 95_000_000)) }
        assertFailsWith<ZbeFileException> { ZbeFile.parse(build(polygons = 0)) }
        assertFailsWith<ZbeFileException> { ZbeFile.parse(build(rings = 0)) }
        val ok = build()
        val flipped = ok.copyOf().also { it[20] = (it[20] + 1).toByte() }
        assertFailsWith<ZbeFileException> { ZbeFile.parse(flipped) }
        assertFailsWith<ZbeFileException> { ZbeFile.parse(ok.copyOf(ok.size - 9)) }
        val s = sample()
        assertFailsWith<ZbeFileException> { ZbeFile.parse(s.copyOf(s.size - 1)) }
    }

    @Test fun anEmptyDatasetIsValid() {
        assertTrue(ZbeFile.parse(build(zones = 0)).zones.isEmpty())
    }
}
