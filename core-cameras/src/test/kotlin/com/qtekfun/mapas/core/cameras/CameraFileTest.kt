package com.qtekfun.mapas.core.cameras

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraFileTest {
    /** Produced by `scripts/build-cameras.py` from the fixtures of `scripts/test_build_cameras.py` (cross-language check). */
    private fun sample() = javaClass.getResourceAsStream("/cameras/sample.bin")!!.readBytes()

    @Test fun readsTheFileWrittenByThePythonConverter() {
        val d = CameraFile.parse(sample())
        assertEquals(1_791_374_400L, d.generatedAtEpochSeconds)
        assertEquals(CameraSources.DGT, d.sourceFlags)
        assertEquals(2, d.fixed.size)
        assertEquals(1, d.sections.size)
        assertEquals(3, d.zones.size)
        val a = d.fixed[0]
        assertEquals(41.3, a.location.lat, 1e-6)
        assertEquals(-1.9, a.location.lon, 1e-6)
        assertEquals("A-2", a.road)
        assertNull(a.maxSpeedKmh, "the DGT publishes no limit")
        assertNotNull(a.axisDeg)
        assertEquals(AxisSense.ALONG, a.sense)
        assertEquals(AxisSense.AGAINST, d.fixed[1].sense)
        val s = d.sections.single()
        assertEquals("Z-40", s.road)
        assertEquals(41.4, s.endLocation!!.lat, 1e-6)
        val withLine = d.zones.filter { it.hasGeometry }
        assertEquals(1, withLine.size, "only one zone could be placed on the map; the others are text only")
        assertEquals(202_200, withLine.single().kmFromMeters)
        assertEquals("Zaragoza", withLine.single().province)
    }

    @Test fun anyDamageIsRejectedAsAWhole() {
        val good = sample()
        val flipped = good.copyOf().also { it[20] = (it[20] + 1).toByte() }
        assertFailsWith<CameraFileException> { CameraFile.parse(flipped) }
        assertFailsWith<CameraFileException> { CameraFile.parse(good.copyOf(good.size - 7)) }
        assertFailsWith<CameraFileException> { CameraFile.parse(ByteArray(10)) }
        assertFailsWith<CameraFileException> { CameraFile.parse(ByteArray(0)) }
    }

    private fun build(magic: Int = CameraFile.MAGIC, version: Int = 1, nf: Int = 0, extra: (DataOutputStream) -> Unit = {}): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            writeInt(magic); writeShort(version); writeLong(1L); writeByte(1); writeInt(nf); writeInt(0); writeInt(0)
            extra(this)
        }
        val crc = CRC32().apply { update(body.toByteArray()) }.value
        DataOutputStream(body).writeInt(crc.toInt())
        return body.toByteArray()
    }

    @Test fun wrongMagicVersionAndImpossibleCountsAreRejected() {
        assertEquals(0, CameraFile.parse(build()).fixed.size)
        assertFailsWith<CameraFileException> { CameraFile.parse(build(magic = 1)) }
        assertFailsWith<CameraFileException> { CameraFile.parse(build(version = 2)) }
        assertFailsWith<CameraFileException> { CameraFile.parse(build(nf = 1_000_000)) }
        assertFailsWith<CameraFileException> { CameraFile.parse(build(nf = -1)) }
    }

    @Test fun anOutOfRangePositionIsRejected() {
        val bytes = build(nf = 1) { o ->
            o.writeInt(95_000_000); o.writeInt(0); o.writeByte(1); o.writeByte(0); o.writeShort(-1); o.writeByte(0); o.writeUTF("A-1")
        }
        assertFailsWith<CameraFileException> { CameraFile.parse(bytes) }
    }

    @Test fun anUnknownLimitOrAxisBecomesNullAndASenseWithoutAxisIsBoth() {
        val bytes = build(nf = 1) { o ->
            o.writeInt(40_000_000); o.writeInt(-3_000_000); o.writeByte(2); o.writeByte(5); o.writeShort(-1); o.writeByte(1); o.writeUTF("")
        }
        val c = CameraFile.parse(bytes).fixed.single()
        assertNull(c.maxSpeedKmh)
        assertNull(c.axisDeg)
        assertEquals(AxisSense.BOTH, c.sense)
        assertEquals(CameraSources.OSM, c.sources)
        assertFalse(c.road.isNotEmpty())
        assertTrue(CameraFile.parse(bytes).zones.isEmpty())
    }
}
