package com.qtekfun.ultimatemaps.core.transit.rt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Small hand-written fixtures in the exact shape of Renfe's JSON feeds (checked against the live files on 2026-10-08). */
class GtfsRtJsonTest {
    private val tripUpdates = """
        {"header": {"gtfsRealtimeVersion": "2.0", "timestamp": "1791456307"},
         "entity": [
          {"id": "TUUPDATE_A", "tripUpdate": {
             "trip": {"tripId": "3079J23717C5", "scheduleRelationship": "SCHEDULED"},
             "stopTimeUpdate": [{"arrival": {"delay": -120, "time": "1791456560"}, "stopId": "43002"}],
             "vehicle": {"wheelchairAccessible": "WHEELCHAIR_INACCESSIBLE"}, "delay": -120}},
          {"id": "TUUPDATE_B", "tripUpdate": {
             "trip": {"tripId": "5179J28426R2N", "scheduleRelationship": "SCHEDULED"},
             "stopTimeUpdate": [{"arrival": {"delay": 2040, "time": "1791456539"}, "stopId": "71708"},
                                {"stopId": "72400", "scheduleRelationship": "SKIPPED"}], "delay": 2040}},
          {"id": "TUUPDATE_C", "tripUpdate": {"trip": {"tripId": "5179J77732R4", "scheduleRelationship": "CANCELED"}}},
          {"id": "BROKEN", "tripUpdate": {"stopTimeUpdate": []}},
          "not an object"
         ]}
    """.trimIndent()

    @Test
    fun `trip updates give delay, stop times, skipped stops and cancellations`() {
        val p = GtfsRtJson.parseTripUpdates(tripUpdates)
        assertEquals(1791456307L, p.timestampSec)
        assertEquals(3, p.entities.size) // the broken ones are skipped
        val a = p.entities[0]
        assertEquals("3079J23717C5", a.tripId)
        assertFalse(a.cancelled)
        assertEquals(-120, a.delaySec)
        assertEquals(RtStopUpdate("43002", arrivalTime = 1791456560L, arrivalDelaySec = -120), a.stops.single())
        assertEquals(1791456560L + 120, a.stops.single().scheduledAt())
        val b = p.entities[1]
        assertEquals(2040, b.delaySec)
        assertEquals(listOf(false, true), b.stops.map { it.skipped })
        assertNull(b.stops[1].scheduledAt())
        val c = p.entities[2]
        assertTrue(c.cancelled)
        assertNull(c.delaySec)
        assertTrue(c.stops.isEmpty())
    }

    @Test
    fun `vehicle positions give trip, stop and coordinates`() {
        val body = """
            {"header": {"timestamp": "1791456300"}, "entity": [{"id": "VP_C1-23532", "vehicle": {
              "trip": {"tripId": "3079J23532C1"}, "position": {"latitude": 37.377792, "longitude": -5.9795485},
              "currentStatus": "INCOMING_AT", "timestamp": "1791456303", "stopId": "51003",
              "vehicle": {"id": "23532", "label": "C1-23532"}}}]}
        """.trimIndent()
        val v = GtfsRtJson.parseVehicles(body).entities.single()
        assertEquals(RtVehicle("3079J23532C1", "51003", 37.377792, -5.9795485, 1791456303L), v)
    }

    @Test
    fun `alerts give the informed routes and stops, the period and the Spanish text`() {
        val body = """
            {"header": {"timestamp": "1791456300"}, "entity": [
              {"id": "AVISO_1", "alert": {
                "activePeriod": [{"start": "1791356520"}],
                "informedEntity": [{"routeId": "10T0011C4"}, {"routeId": "10T0068C4"}, {"stopId": "35100"}],
                "descriptionText": {"translation": [{"text": "Hello", "language": "en"}, {"text": "  Por reajuste de servicio  ", "language": "es"}]}}},
              {"id": "AVISO_2", "alert": {"informedEntity": [{"routeId": "x"}]}}
            ]}
        """.trimIndent()
        val alerts = GtfsRtJson.parseAlerts(body).entities
        assertEquals(1, alerts.size) // the one without text is dropped
        val a = alerts.single()
        assertEquals("AVISO_1", a.id)
        assertEquals(listOf("10T0011C4", "10T0068C4"), a.routeIds)
        assertEquals(listOf("35100"), a.stopIds)
        assertEquals(1791356520L, a.startSec)
        assertNull(a.endSec)
        assertEquals("Por reajuste de servicio", a.text)
        assertTrue(a.activeAt(1791456300L))
        assertFalse(a.activeAt(1791356519L))
    }

    @Test
    fun `a body that is not a feed is an error and unknown fields are ignored`() {
        assertFailsWith<RtParseException> { GtfsRtJson.parseTripUpdates("<html>maintenance</html>") }
        assertFailsWith<RtParseException> { GtfsRtJson.parseTripUpdates("[1,2]") }
        assertFailsWith<RtParseException> { GtfsRtJson.parseAlerts("{}") }
        assertTrue(GtfsRtJson.parseTripUpdates("""{"header": {"x": 1}, "entity": [], "future": true}""").entities.isEmpty())
    }
}
