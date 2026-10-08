package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.transit.build.CityManifest
import com.qtekfun.ultimatemaps.core.transit.build.TransitBuild
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The shipped city configs parse, and the builder handles missing and expired feeds with small synthetic GTFS zips. */
class TransitBuildManifestsTest {
    @TempDir lateinit var dir: File

    private fun zip(name: String, files: Map<String, String>): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { z -> files.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return f
    }

    private val expiredCalendar = Fixtures.calendarTxt
        .replace("20261001", "20260501").replace("20261031", "20260601")

    private fun manifest(vararg feeds: Pair<String, String>) = CityManifest.parse(
        """{"id":"testville","city":"Testville","timezone":"Europe/Madrid","bounds":[39.9,-3.1,40.1,-2.9],"feeds":[""" +
            feeds.joinToString(",") { (file, ns) ->
                """{"label":"$ns","file":"$file","namespace":"$ns","nap":1,"attribution":"Powered by MITRAMS (https://www.transportes.gob.es/)"}"""
            } + "]}",
    )

    @Test
    fun `the shipped NAP configs parse and have sane boxes`() {
        val root = File("../scripts/transit")
        val names = listOf("madrid", "barcelona", "valencia", "sevilla", "bilbao")
        val ids = names.map { n ->
            val m = CityManifest.parse(File(root, "$n.json").readText())
            assertEquals(n, m.id)
            assertTrue(m.feeds.isNotEmpty())
            assertTrue(m.bounds[0] < m.bounds[2] && m.bounds[1] < m.bounds[3], "box of $n")
            assertEquals("Europe/Madrid", m.timezone)
            // Madrid's CRTM feeds share one stop-id space on purpose; the NAP operators each have their own
            if (n != "madrid") {
                assertTrue(m.feeds.map { it.namespace }.toSet().size == m.feeds.size, "namespaces of $n are distinct")
                assertTrue(m.feeds.all { it.attribution.startsWith("Powered by MITRAMS") })
            }
            m.id
        }
        assertEquals(names, ids)
    }

    @Test
    fun `an expired and a missing feed are skipped and reported while the good one builds an index`() {
        zip("good.zip", Fixtures.files())
        zip("old.zip", Fixtures.files(extra = mapOf("calendar.txt" to expiredCalendar)))
        val m = manifest("good.zip" to "good", "old.zip" to "old", "absent.zip" to "absent")
        val log = ArrayList<String>()
        val r = TransitBuild.build(m, dir, LocalDate.parse("2026-10-14"), allowExpired = false, generated = "t", log = { log += it })
        assertEquals(listOf("ok", "expired", "missing file"), r.feeds.map { it.reason })
        assertEquals(listOf(true, false, false), r.feeds.map { it.included })
        assertEquals("2026-10-01", r.meta["validFrom"].toString().trim('"'))
        assertEquals("2026-10-31", r.meta["validTo"].toString().trim('"'))
        assertTrue(log.any { it.startsWith("SKIP old") })
        val (umti, json) = TransitBuild.write(r, "testville", File(dir, "out"))
        assertTrue(umti.length() > 0 && json.isFile)
    }

    @Test
    fun `when every feed is expired or missing the build refuses instead of publishing nothing`() {
        zip("old.zip", Fixtures.files(extra = mapOf("calendar.txt" to expiredCalendar)))
        assertFailsWith<IllegalArgumentException> {
            TransitBuild.build(manifest("old.zip" to "old", "absent.zip" to "absent"), dir, LocalDate.parse("2026-10-14"), false, "t")
        }
    }

    @Test
    fun `a feed that is still valid on the build date is kept up to its last day`() {
        zip("good.zip", Fixtures.files())
        val r = TransitBuild.build(manifest("good.zip" to "good"), dir, LocalDate.parse("2026-10-31"), false, "t")
        assertEquals(listOf("ok"), r.feeds.map { it.reason })
        assertFailsWith<IllegalArgumentException> {
            TransitBuild.build(manifest("good.zip" to "good"), dir, LocalDate.parse("2026-11-01"), false, "t")
        }
    }
}
