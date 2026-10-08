package com.qtekfun.ultimatemaps.core.weather

import java.io.IOException
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapParserTest {
    private val now = OffsetDateTime.parse("2026-10-08T10:00:00+02:00").toInstant().toEpochMilli()
    private fun at(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test fun `reads the alert, both languages, level, times, area, polygon and zone`() {
        val alert = CapParser.parse(CapFixtures.doc())
        assertEquals("2.49.0.1.724.0.2026100809000.774601", alert.identifier)
        assertEquals("Actual", alert.status)
        assertEquals("Alert", alert.msgType)
        assertEquals(listOf("es-ES", "en-GB"), alert.infos.map { it.language })
        val es = alert.infos[0]
        assertEquals("Vientos", es.event)
        assertEquals(AlertLevel.ORANGE, es.level)
        assertEquals(at("2026-10-08T12:00:00+02:00"), es.onsetMillis)
        assertEquals(at("2026-10-08T18:00:00+02:00"), es.expiresMillis)
        assertEquals("Rachas de viento de 80 km/h & mar gruesa.", es.description)
        val area = es.areas.single()
        assertEquals("Litoral norte de Valencia", area.description)
        assertEquals("774601", area.zoneCode)
        assertEquals(4, area.polygons.single().size, "the closing vertex is dropped")
    }

    @Test fun `levels from the AEMET parameter and from the severity`() {
        assertEquals(AlertLevel.YELLOW, CapParser.level("amarillo", null))
        assertEquals(AlertLevel.YELLOW, CapParser.level("2;amarillo", null))
        assertEquals(AlertLevel.ORANGE, CapParser.level("Naranja", "Moderate"))
        assertEquals(AlertLevel.RED, CapParser.level("3;rojo", null))
        assertNull(CapParser.level("verde", "Extreme"), "green means no warning, whatever the severity")
        assertEquals(AlertLevel.ORANGE, CapParser.level(null, "Severe"))
        assertEquals(AlertLevel.RED, CapParser.level(null, "Extreme"))
        assertNull(CapParser.level(null, "Minor"))
        assertNull(CapParser.level("???", null))
    }

    @Test fun `warnings use the device language, else Spanish, else the first info`() {
        val alert = CapParser.parse(CapFixtures.doc())
        assertEquals("Wind", CapParser.toWarnings(listOf(alert), "en", now).single().event)
        assertEquals("Vientos", CapParser.toWarnings(listOf(alert), "es", now).single().event)
        assertEquals("Vientos", CapParser.toWarnings(listOf(alert), "fr", now).single().event)
        val onlyEnglish = CapParser.parse(CapFixtures.doc(infos = listOf(CapFixtures.info(language = "en-GB", event = "Wind"))))
        assertEquals("Wind", CapParser.toWarnings(listOf(onlyEnglish), "es", now).single().event)
    }

    @Test fun `cancelled, test, expired and green messages are left out`() {
        fun w(doc: String) = CapParser.toWarnings(listOf(CapParser.parse(doc)), "es", now)
        assertTrue(w(CapFixtures.doc(msgType = "Cancel")).isEmpty())
        assertTrue(w(CapFixtures.doc(status = "Test")).isEmpty())
        assertTrue(w(CapFixtures.doc(infos = listOf(CapFixtures.info(expires = "2026-10-08T09:00:00+02:00")))).isEmpty())
        assertTrue(w(CapFixtures.doc(infos = listOf(CapFixtures.info(level = "verde", severity = "Minor")))).isEmpty())
        assertEquals(1, w(CapFixtures.doc()).size)
    }

    @Test fun `an area without a polygon is kept but covers nothing`() {
        val w = CapParser.toWarnings(listOf(CapParser.parse(CapFixtures.doc(infos = listOf(CapFixtures.info(polygon = null))))), "es", now).single()
        assertTrue(w.polygons.isEmpty())
        assertFalse(w.covers(39.5, -0.4))
        assertEquals("Litoral norte de Valencia", w.areaDesc)
    }

    @Test fun `several polygons and several areas become several warnings`() {
        val two = """
  <info><language>es-ES</language><event>Lluvia</event><severity>Severe</severity><onset>2026-10-08T09:00:00+02:00</onset><expires>2026-10-08T20:00:00+02:00</expires>
    <parameter><valueName>AEMET-Meteoalerta nivel</valueName><value>naranja</value></parameter>
    <area><areaDesc>A</areaDesc><polygon>1,1 1,2 2,2 1,1</polygon><polygon>5,5 5,6 6,6 5,5</polygon></area>
    <area><areaDesc>B</areaDesc><polygon>8,8 8,9 9,9 8,8</polygon></area>
  </info>"""
        val list = CapParser.toWarnings(listOf(CapParser.parse(CapFixtures.doc(infos = listOf(two)))), "es", now)
        assertEquals(listOf("A", "B"), list.map { it.areaDesc })
        assertEquals(2, list[0].polygons.size)
    }

    @Test fun `a document type declaration is refused and garbage is an error`() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE alert [<!ENTITY x SYSTEM "file:///etc/passwd">]><alert><identifier>&x;</identifier></alert>"""
        assertFailsWith<CapParseException> { CapParser.parse(xxe) }
        assertFailsWith<CapParseException> { CapParser.parse("not xml at all") }
        assertFailsWith<CapParseException> { CapParser.parse("<feed><entry/></feed>") }
        assertFailsWith<CapParseException> { CapParser.parse("<alert><identifier>x</identifier>") }
    }

    @Test fun `bad polygons are skipped, not fatal`() {
        assertNull(CapParser.polygon("1,1 2,2"))
        assertNull(CapParser.polygon("a,b c,d e,f"))
        assertNull(CapParser.polygon("95,0 1,1 2,2"))
        assertEquals(3, CapParser.polygon("1,1 1,2 2,2")!!.size)
    }

    @Test fun `bundle reads a tar, a gzipped tar and a bare document, ignoring other files`() {
        val a = CapFixtures.doc().toByteArray()
        val b = CapFixtures.doc(id = "second").toByteArray()
        val tar = CapFixtures.tar(listOf("a.xml" to a, "readme.txt" to "hi".toByteArray(), "dir/b.xml" to b))
        assertEquals(2, CapBundle.documents(tar).size)
        assertEquals(2, CapBundle.documents(CapFixtures.gzip(tar)).size)
        assertEquals(1, CapBundle.documents(a).size)
        assertEquals(1, CapBundle.documents(CapFixtures.gzip(a)).size)
        assertEquals(0, CapBundle.documents(CapFixtures.tar(emptyList())).size)
        assertFailsWith<IOException> { CapBundle.documents(ByteArray(2000) { 7 }) }
        assertFailsWith<IOException> { CapBundle.documents(tar.copyOf(700)) }
    }
}
