package com.qtekfun.ultimatemaps.core.cameras

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatexIncidentParserTest {
    private val header = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<d2:payload xmlns:d2="http://levelC/schema/3/d2Payload" xmlns:sit="http://levelC/schema/3/situation" xmlns:com="http://levelC/schema/3/common" xmlns:loc="http://levelC/schema/3/locationReferencing" xmlns:lse="http://levelC/schema/3/locationReferencingSpanishExtension" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<com:publicationTime>2026-10-07T15:28:08.921+02:00</com:publicationTime>"""

    private fun rec(
        id: String, type: String = "sit:GenericSituationRecord", ref: String? = null, cause: String, detail: String,
        mgmt: String? = null, status: String = "active", end: String? = null, direction: String = "unknown",
        point: String = """<loc:pointCoordinates><loc:latitude>42.981407</loc:latitude><loc:longitude>-7.586229</loc:longitude></loc:pointCoordinates>""",
        road: String = "N-540",
    ) = """
<sit:situation id="s$id"><sit:situationRecord xsi:type="$type" id="$id" version="1">
 ${ref?.let { "<sit:situationRecordCreationReference>$it</sit:situationRecordCreationReference>" }.orEmpty()}
 <sit:validity><com:validityStatus>$status</com:validityStatus><com:validityTimeSpecification><com:overallStartTime>2026-10-07T14:25:30.000+02:00</com:overallStartTime>${end?.let { "<com:overallEndTime>$it</com:overallEndTime>" }.orEmpty()}</com:validityTimeSpecification></sit:validity>
 <sit:cause><sit:causeType>$cause</sit:causeType><sit:detailedCauseType><sit:$detail</sit:detailedCauseType></sit:cause>
 ${mgmt?.let { "<sit:roadOrCarriagewayOrLaneManagementType>$it</sit:roadOrCarriagewayOrLaneManagementType>" }.orEmpty()}
 <sit:locationReference xsi:type="loc:PointLocation"><loc:supplementaryPositionalDescription><loc:roadInformation><loc:roadName>$road</loc:roadName></loc:roadInformation></loc:supplementaryPositionalDescription>
  <loc:tpegPointLocation><loc:tpegDirection>$direction</loc:tpegDirection><loc:point>$point
   <loc:_tpegNonJunctionPointExtension><loc:extendedTpegNonJunctionPoint><lse:kilometerPoint>3.76</lse:kilometerPoint><lse:municipality>Lugo</lse:municipality><lse:province>Lugo</lse:province></loc:extendedTpegNonJunctionPoint></loc:_tpegNonJunctionPointExtension>
  </loc:point></loc:tpegPointLocation></sit:locationReference>
</sit:situationRecord></sit:situation>"""

    private fun feed(vararg records: String) = (header + records.joinToString("") + "</d2:payload>").byteInputStream()

    @Test fun v16BeaconRecordIsRecognisedByItsReferenceAndKeepsPlaceNames() {
        val f = DatexIncidentParser.parse(feed(rec("1", ref = "V16_CLugIBg1JQkt-1791375830786_1", cause = "vehicleObstruction", detail = "vehicleObstructionType>vehicleStuck</sit:vehicleObstructionType>", direction = "northWestBound")))
        val i = f.incidents.single()
        assertEquals(IncidentKind.V16, i.kind)
        assertEquals("N-540", i.road)
        assertEquals(42.981407, i.location.lat, 1e-9)
        assertNull(i.end)
        assertEquals(315, i.directionDeg)
        assertEquals("Lugo", i.municipality)
        assertEquals(3.76, i.kmPoint)
        assertEquals(OffsetDateTime.parse("2026-10-07T15:28:08.921+02:00").toInstant().toEpochMilli(), f.publishedMillis)
    }

    @Test fun kindsAreClassifiedFromCauseAndDetail() {
        fun k(cause: String, detail: String, mgmt: String? = null, ref: String? = null) =
            DatexIncidentParser.parse(feed(rec("9", ref = ref, cause = cause, detail = detail, mgmt = mgmt))).incidents.singleOrNull()?.kind
        assertEquals(IncidentKind.ACCIDENT, k("accident", "accidentType>accident</sit:accidentType>"))
        assertEquals(IncidentKind.CONGESTION, k("abnormalTraffic", "abnormalTrafficType>slowTraffic</sit:abnormalTrafficType>"))
        assertEquals(IncidentKind.OBSTACLE, k("vehicleObstruction", "vehicleObstructionType>vehicleStuck</sit:vehicleObstructionType>"))
        assertEquals(IncidentKind.OBSTACLE, k("environmentalObstruction", "environmentalObstructionType>rockfalls</sit:environmentalObstructionType>"))
        assertEquals(IncidentKind.WEATHER, k("poorEnvironment", "poorEnvironmentType>rain</sit:poorEnvironmentType>"))
        assertEquals(IncidentKind.ROADWORKS, k("roadMaintenance", "roadMaintenanceType>roadworks</sit:roadMaintenanceType>", mgmt = "laneClosures"))
        assertEquals(IncidentKind.CLOSURE, k("roadMaintenance", "roadMaintenanceType>roadworks</sit:roadMaintenanceType>", mgmt = "carriagewayClosures"))
        assertEquals(IncidentKind.CLOSURE, k("roadMaintenance", "roadMaintenanceType>roadworks</sit:roadMaintenanceType>", mgmt = "roadClosed"))
        assertNull(k("somethingNew", "otherType>x</sit:otherType>"), "an unknown cause is not shown")
    }

    @Test fun segmentHasBothEndsAndTheFirstIsTheLocation() {
        val seg = """<loc:pointCoordinates><loc:latitude>39.3971</loc:latitude><loc:longitude>-0.7814</loc:longitude></loc:pointCoordinates></loc:to>
            <loc:from><loc:pointCoordinates><loc:latitude>39.4116</loc:latitude><loc:longitude>-0.7864</loc:longitude></loc:pointCoordinates>"""
        // Same shape as the real feed: <to> then <from>, each with coordinates.
        val xml = header + """
<sit:situation id="a"><sit:situationRecord id="5"><sit:validity><com:validityStatus>active</com:validityStatus></sit:validity>
<sit:cause><sit:causeType>accident</sit:causeType></sit:cause>
<sit:locationReference><loc:supplementaryPositionalDescription><loc:roadInformation><loc:roadName>CV-425</loc:roadName></loc:roadInformation></loc:supplementaryPositionalDescription>
<loc:tpegLinearLocation><loc:tpegDirection>southBound</loc:tpegDirection>
 <loc:to>${seg}</loc:from></loc:tpegLinearLocation></sit:locationReference></sit:situationRecord></sit:situation></d2:payload>"""
        val i = DatexIncidentParser.parse(xml.byteInputStream()).incidents.single()
        assertEquals(39.3971, i.location.lat, 1e-9)
        assertEquals(39.4116, i.end!!.lat, 1e-9)
        assertEquals(180, i.directionDeg)
    }

    @Test fun recordsWithoutAUsablePositionOrNotActiveOrExpiredAreSkipped() {
        val now = OffsetDateTime.parse("2026-10-07T16:00:00+02:00").toInstant().toEpochMilli()
        val f = DatexIncidentParser.parse(
            feed(
                rec("1", cause = "accident", detail = "accidentType>accident</sit:accidentType>", point = "<loc:pointCoordinates><loc:latitude>0.0</loc:latitude><loc:longitude>0.0</loc:longitude></loc:pointCoordinates>"),
                rec("2", cause = "accident", detail = "accidentType>accident</sit:accidentType>", point = "<loc:pointCoordinates><loc:latitude>95.0</loc:latitude><loc:longitude>1.0</loc:longitude></loc:pointCoordinates>"),
                rec("3", cause = "accident", detail = "accidentType>accident</sit:accidentType>", status = "suspended"),
                rec("4", cause = "accident", detail = "accidentType>accident</sit:accidentType>", end = "2026-10-07T15:00:00.000+02:00"),
                rec("5", cause = "accident", detail = "accidentType>accident</sit:accidentType>", end = "2026-10-07T18:00:00.000+02:00"),
            ),
            nowMillis = now,
        )
        assertEquals(listOf("5"), f.incidents.map { it.id })
        assertEquals(4, f.skipped)
    }

    @Test fun truncatedOrMalformedDocumentsAreRejectedWhole() {
        val whole = header + rec("1", cause = "accident", detail = "accidentType>accident</sit:accidentType>") + "</d2:payload>"
        assertFailsWith<IncidentParseException> { DatexIncidentParser.parse(whole.dropLast(200).byteInputStream()) }
        assertFailsWith<IncidentParseException> { DatexIncidentParser.parse("not xml at all".byteInputStream()) }
        assertFailsWith<IncidentParseException> { DatexIncidentParser.parse("".byteInputStream()) }
    }

    @Test fun anEmptyPublicationIsValidAndEmpty() {
        val f = DatexIncidentParser.parse(feed())
        assertTrue(f.incidents.isEmpty())
    }

    // ---- real data ----

    /** Four situations copied verbatim from the DGT feed downloaded on 2026-10-07 (seven records). */
    @Test fun parsesAnExcerptOfTheRealFeed() {
        val f = DatexIncidentParser.parse(javaClass.getResourceAsStream("/cameras/datex-real-excerpt.xml")!!)
        assertEquals(0, f.skipped)
        val byKind = f.incidents.groupBy { it.kind }
        assertEquals(1, byKind[IncidentKind.V16]?.size)
        assertEquals(1, byKind[IncidentKind.ACCIDENT]?.size)
        assertEquals(1, byKind[IncidentKind.CONGESTION]?.size)
        assertEquals(4, (byKind[IncidentKind.CLOSURE]?.size ?: 0) + (byKind[IncidentKind.ROADWORKS]?.size ?: 0))
        val v16 = byKind.getValue(IncidentKind.V16).single()
        assertEquals("N-540", v16.road)
        assertEquals(315, v16.directionDeg)
        assertNull(v16.end, "a V16 beacon is a point")
        val acc = byKind.getValue(IncidentKind.ACCIDENT).single()
        assertNotNull(acc.end, "the accident is a stretch")
        assertEquals("A Coruña", acc.province)
        assertTrue(f.incidents.all { it.location.lat in 35.0..44.5 && it.location.lon in -19.0..5.0 }, "all inside Spain")
    }

    /**
     * Optional: point `UM_REAL_DGT_FEED` at a full copy of `datex2_v37.xml` to check the parser against everything the DGT
     * publishes right now. Does nothing when it is not set, so the suite never depends on a file or the network.
     */
    @Test fun parsesTheWholeRealFeedWhenGiven() {
        val path = System.getenv("UM_REAL_DGT_FEED") ?: return
        val t0 = System.nanoTime()
        val f = java.io.File(path).inputStream().buffered().use { DatexIncidentParser.parse(it) }
        println("REAL_FEED incidents=${f.incidents.size} skipped=${f.skipped} kinds=${f.incidents.groupingBy { it.kind }.eachCount()} ms=${(System.nanoTime() - t0) / 1_000_000}")
        assertTrue(f.incidents.isNotEmpty())
    }

    @Test fun directionNamesMapToBearings() {
        assertEquals(0, DatexIncidentParser.bearingOf("northBound"))
        assertEquals(225, DatexIncidentParser.bearingOf("southWestBound"))
        assertNull(DatexIncidentParser.bearingOf("unknown"))
        assertNull(DatexIncidentParser.bearingOf("inbound"))
        assertNull(DatexIncidentParser.bearingOf(null))
    }
}
