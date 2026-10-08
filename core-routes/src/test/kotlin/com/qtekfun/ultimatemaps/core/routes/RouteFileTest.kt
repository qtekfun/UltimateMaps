package com.qtekfun.ultimatemaps.core.routes

import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RouteFileTest {
    private fun sample() = javaClass.getResourceAsStream("/routes/sample.bin")!!.readBytes()

    /** Rewrites the trailing CRC after a deliberate edit, so the test reaches the check it is about. */
    private fun fixCrc(b: ByteArray): ByteArray {
        val crc = CRC32().apply { update(b, 0, b.size - 4) }.value.toInt()
        ByteBuffer.wrap(b, b.size - 4, 4).putInt(crc)
        return b
    }

    @Test fun readsTheSampleFile() {
        val d = RouteFile.parse(sample())
        assertEquals(1_790_000_000L, d.generatedAtEpochSeconds)
        assertEquals(4, d.trails.size)
        val gr = d.trails[0]
        assertEquals(0, gr.id)
        assertEquals(TrailKind.HIKING, gr.kind)
        assertEquals(TrailLevel.INTERNATIONAL, gr.level)
        assertEquals("Camino del Norte", gr.name)
        assertEquals("GR 1", gr.ref)
        assertEquals("FEDME", gr.operator)
        assertEquals(24_500, gr.lengthMeters)
        assertTrue(gr.lengthFromTag)
        assertEquals(2, gr.segments.size)
        assertEquals(12 * 2, gr.segments[0].size)
        assertEquals(43_400_000, gr.segments[0][0])
        assertEquals(-4_000_000, gr.segments[0][1])
        val start = assertNotNull(gr.start)
        assertEquals(43.4, start.lat, 1e-9)
        assertEquals(-4.0, start.lon, 1e-9)
        val cv = d.trails[1]
        assertEquals(TrailKind.CYCLING, cv.kind)
        assertTrue(cv.kind.isBike)
        assertEquals("CV-1", cv.title, "no name: the ref is the title")
        assertFalse(cv.lengthFromTag)
        assertEquals(TrailKind.MTB, d.trails[3].kind)
        assertTrue(gr.minLat <= 43_400_000 && gr.maxLat >= 43_400_000 && gr.minLon <= -4_000_000)
    }

    @Test fun rejectsDamagedFiles() {
        val ok = sample()
        assertFailsWith<RouteFileException> { RouteFile.parse(ByteArray(10)) }
        assertFailsWith<RouteFileException> { RouteFile.parse(ok.copyOf(ok.size - 1)) }
        val flipped = ok.copyOf().also { it[40] = (it[40].toInt() xor 0x55).toByte() }
        assertFailsWith<RouteFileException> { RouteFile.parse(flipped) }
        assertFailsWith<RouteFileException>("wrong magic") { RouteFile.parse(fixCrc(ok.copyOf().also { it[0] = 0 })) }
        assertFailsWith<RouteFileException>("newer version") { RouteFile.parse(fixCrc(ok.copyOf().also { it[5] = 9 })) }
        assertFailsWith<RouteFileException>("claims more routes than it holds") {
            RouteFile.parse(fixCrc(ok.copyOf().also { it[17] = (it[17] + 1).toByte() }))
        }
        assertFailsWith<RouteFileException>("claims a different raw length") {
            RouteFile.parse(fixCrc(ok.copyOf().also { it[21] = (it[21] + 1).toByte() }))
        }
    }
}
