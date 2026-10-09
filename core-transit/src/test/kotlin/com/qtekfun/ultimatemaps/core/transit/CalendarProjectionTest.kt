package com.qtekfun.ultimatemaps.core.transit

import com.qtekfun.ultimatemaps.core.transit.build.CityManifest
import com.qtekfun.ultimatemaps.core.transit.build.PROJECTION_DAYS
import com.qtekfun.ultimatemaps.core.transit.build.TransitBuild
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An expired calendar is projected forward on request (the Madrid Metro case); a valid one never is. */
class CalendarProjectionTest {
    @TempDir lateinit var dir: File

    private val today = day("2026-10-09")

    private val oldCalendar = """
        service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
        WK,1,1,1,1,1,0,0,20250527,20260527
        WE,0,0,0,0,0,1,1,20250527,20260527
        XMAS,1,1,1,1,1,1,1,20251220,20251231
    """.trimIndent() + "\n"

    private val oldDates = """
        service_id,date,exception_type
        WK,20251225,2
        WE,20251225,1
        WK,20260101,2
    """.trimIndent() + "\n"

    private fun feed(calendar: String = oldCalendar, dates: String = oldDates) =
        GtfsReader.read(MapGtfsSource(Fixtures.files(extra = mapOf("calendar.txt" to calendar, "calendar_dates.txt" to dates))))

    private fun svc(f: GtfsFeed, id: String) = f.services[f.serviceIds.indexOf(id)]
    private fun active(f: GtfsFeed, id: String, iso: String) = svc(f, id).isActive(day(iso))

    @Test
    fun `an expired feed keeps its weekday pattern up to the horizon and loses its past exceptions`() {
        val f = feed()
        assertTrue(!active(f, "WK", "2026-10-12"))
        val p = assertNotNull(projectCalendar(f, today, today + 60))
        assertEquals(day("2026-05-27"), p.originalLastDay)
        assertEquals(2, p.extendedServices)
        assertEquals(3, p.droppedExceptions)
        // next week: Monday 12th, Saturday 17th and Sunday 18th follow the weekday pattern
        assertTrue(active(f, "WK", "2026-10-12") && !active(f, "WK", "2026-10-17") && !active(f, "WK", "2026-10-18"))
        assertTrue(active(f, "WE", "2026-10-17") && active(f, "WE", "2026-10-18") && !active(f, "WE", "2026-10-12"))
        assertTrue(active(f, "WK", "2026-12-08"))
        // beyond the horizon (2026-12-08) nothing runs
        assertTrue(!active(f, "WK", "2026-12-09"))
        // last year's exceptions are gone
        assertTrue(svc(f, "WK").removed.isEmpty() && svc(f, "WE").added.isEmpty())
    }

    @Test
    fun `a special service that ended long ago stays ended`() {
        val f = feed()
        projectCalendar(f, today, today + 60)
        assertTrue(!active(f, "XMAS", "2026-10-12"))
        assertEquals(day("2025-12-31"), svc(f, "XMAS").endDay)
    }

    @Test
    fun `a feed that is still valid is untouched`() {
        val f = feed(oldCalendar.replace("20260527", "20261231"), "service_id,date,exception_type\nWK,20261225,2\n")
        assertNull(projectCalendar(f, today, today + 60))
        assertEquals(day("2026-12-31"), svc(f, "WK").endDay)
        assertEquals(1, svc(f, "WK").removed.size)
    }

    @Test
    fun `projecting twice changes nothing the second time`() {
        val f = feed()
        assertNotNull(projectCalendar(f, today, today + 60))
        fun snapshot() = f.services.map { listOf(it.mask, it.startDay, it.endDay, it.added.toList(), it.removed.toList()) }
        val before = snapshot()
        assertNull(projectCalendar(f, today, today + 60))
        assertEquals(before, snapshot())
    }

    private fun zip(name: String, files: Map<String, String>) {
        ZipOutputStream(File(dir, name).outputStream()).use { z -> files.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }

    private fun manifest(flag: Boolean) = CityManifest.parse(
        """{"id":"testville","city":"Testville","timezone":"Europe/Madrid","bounds":[39.9,-3.1,40.1,-2.9],"feeds":[
          {"label":"Old metro","file":"old.zip","namespace":"old","attribution":"a"${if (flag) ""","projectCalendar":true""" else ""}}]}""",
    )

    @Test
    fun `the builder projects only a feed that asks for it and says so in the metadata and the index`() {
        zip("old.zip", Fixtures.files(extra = mapOf("calendar.txt" to oldCalendar, "calendar_dates.txt" to oldDates)))
        val build = LocalDate.parse("2026-10-09")
        // without the flag the expired feed is skipped, as before
        assertTrue(runCatching { TransitBuild.build(manifest(false), dir, build, false, "t") }.isFailure)
        val r = TransitBuild.build(manifest(true), dir, build, false, "t")
        assertEquals("projected", r.feeds.single().reason)
        assertEquals(build.plusDays(PROJECTION_DAYS.toLong()).toString(), r.meta["validTo"].toString().trim('"'))
        assertEquals("true", r.meta["projected"].toString())
        assertEquals("[\"Old metro\"]", r.meta["projectedFeeds"].toString())
        assertTrue(r.index.sources.single().calendarProjected)
        // the flag survives the .umti round trip, and so does the extended calendar
        val bytes = java.io.ByteArrayOutputStream().also { TransitIndexIo.write(r.index, it) }.toByteArray()
        val back = TransitIndexIo.read(bytes.inputStream())
        assertTrue(back.sources.single().calendarProjected)
        assertEquals(day("2026-12-08"), back.validity()!!.lastDay)
    }
}
