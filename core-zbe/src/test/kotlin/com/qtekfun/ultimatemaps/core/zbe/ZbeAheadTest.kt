package com.qtekfun.ultimatemaps.core.zbe

import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundPlayer
import com.qtekfun.ultimatemaps.core.cameras.ChimeKind
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.voice.DistanceUnits
import com.qtekfun.ultimatemaps.core.voice.InMemoryNavSettingsStore
import com.qtekfun.ultimatemaps.core.voice.NavSettings
import com.qtekfun.ultimatemaps.core.voice.Utterance
import com.qtekfun.ultimatemaps.core.voice.VoiceGuide
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguage
import com.qtekfun.ultimatemaps.core.voice.VoicePriority
import com.qtekfun.ultimatemaps.core.voice.VoiceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZbeAheadTest {
    private val zone = ZbeZone("z1", "Centro", "Madrid", "", listOf(ZbePolygon(listOf(ZbeRing(doubleArrayOf(40.0, -3.0, 40.0, -2.9, 40.1, -2.9, 40.1, -3.0))))))
    private val other = ZbeZone("z2", "Otra", "Madrid", "", zone.polygons)

    // The entry point is on a meridian: 1 m along the route is 1 m of latitude, whatever the distance from the start.
    private fun crossing(entry: Double, exit: Double = entry + 2000, startsInside: Boolean = false, z: ZbeZone = zone, at: LatLon = LatLon(40.0 + entry / 111_195.0, -3.0)) =
        ZbeCrossing(z, at, entry, exit, startsInside, endsInside = false)

    @Test fun promptsOnceWhenTheZoneIsWithinTheLookAhead() {
        val m = ZbeAheadMachine()
        m.onRoute(listOf(crossing(5_000.0)))
        assertNull(m.onProgress(0.0, 10.0), "5 km away")
        assertNull(m.onProgress(4_000.0, 10.0), "1 km away: 400 m is the minimum look-ahead")
        val a = assertNotNull(m.onProgress(4_650.0, 10.0))
        assertEquals(350, a.distanceMeters)
        assertEquals("z1", a.crossing.zone.id)
        assertNull(m.onProgress(4_700.0, 10.0), "said once")
        assertNull(m.onProgress(4_990.0, 10.0))
    }

    @Test fun theLookAheadGrowsWithSpeedButIsBounded() {
        assertEquals(400.0, ZbeAheadMachine.lookAheadMeters(0.0))
        assertEquals(400.0, ZbeAheadMachine.lookAheadMeters(Double.NaN))
        assertEquals(750.0, ZbeAheadMachine.lookAheadMeters(30.0))
        assertEquals(1000.0, ZbeAheadMachine.lookAheadMeters(100.0))
    }

    @Test fun noPromptWhenTheTripStartsInsideOrWhenTheZoneIsBehind() {
        val m = ZbeAheadMachine()
        m.onRoute(listOf(crossing(0.0, 800.0, startsInside = true)))
        assertNull(m.onProgress(0.0, 10.0))
        m.onRoute(listOf(crossing(1_000.0)), newTrip = true)
        assertNull(m.onProgress(1_200.0, 10.0), "a jump past the entry is silent")
        assertNull(m.onProgress(900.0, 10.0), "and not announced afterwards either")
    }

    @Test fun eachCrossingIsAnnouncedAndTheNearestComesFirst() {
        val m = ZbeAheadMachine()
        m.onRoute(listOf(crossing(1_000.0), crossing(1_200.0, z = other)))
        assertEquals("z1", m.onProgress(800.0, 10.0)!!.crossing.zone.id)
        assertEquals("z2", m.onProgress(850.0, 10.0)!!.crossing.zone.id)
        assertNull(m.onProgress(900.0, 10.0))
    }

    @Test fun aRecalculatedRouteDoesNotRepeatTheSamePrompt() {
        val m = ZbeAheadMachine()
        m.onRoute(listOf(crossing(1_000.0)), newTrip = true)
        assertNotNull(m.onProgress(700.0, 10.0))
        // the same zone, entered at the same place, but 400 m from the start of the new route (the route starts elsewhere)
        m.onRoute(listOf(crossing(400.0, at = LatLon(40.0 + 1_050.0 / 111_195.0, -3.0))))
        assertNull(m.onProgress(100.0, 10.0))
        m.onRoute(listOf(crossing(9_000.0)), newTrip = false) // entered again much later and far away: a new crossing
        assertNotNull(m.onProgress(8_700.0, 10.0))
    }

    @Test fun aNewTripForgetsEverything() {
        val m = ZbeAheadMachine()
        m.onRoute(listOf(crossing(1_000.0)), newTrip = true)
        assertNotNull(m.onProgress(700.0, 10.0))
        m.onRoute(listOf(crossing(1_000.0)), newTrip = true)
        assertNotNull(m.onProgress(700.0, 10.0))
        m.reset()
        assertNull(m.onProgress(700.0, 10.0))
    }

    @Test fun theDistanceIsRoundedToTensAndNeverZero() {
        val m = ZbeAheadMachine()
        m.onRoute(listOf(crossing(1_000.0)))
        assertEquals(10, m.onProgress(999.0, 10.0)!!.distanceMeters)
    }

    // ------------------------------------------------------------------------------------------------ delivery

    private class FakeGuide : VoiceGuide {
        val spoken = ArrayList<Utterance>()
        override val status: StateFlow<VoiceStatus> = MutableStateFlow(VoiceStatus.Idle)
        override fun prepare(language: VoiceLanguage) {}
        override fun speak(utterance: Utterance) { spoken += utterance }
        override fun stop() {}
        override fun setVolume(percent: Int) {}
        override fun retry(language: VoiceLanguage) {}
        override fun shutdown() {}
    }

    private class Chimes : AlertSoundPlayer {
        val played = ArrayList<ChimeKind>()
        override fun play(kind: ChimeKind, volumePercent: Int) { played += kind }
    }

    private fun speaker(mode: AlertSoundMode, nav: NavSettings = NavSettings(), imminent: Boolean = false): Triple<ZbeAheadSpeaker, FakeGuide, Chimes> {
        val guide = FakeGuide()
        val chimes = Chimes()
        val s = ZbeAheadSpeaker(guide, InMemoryNavSettingsStore(nav).settings, { mode }, { imminent }, chimes) { Locale.ENGLISH }
        return Triple(s, guide, chimes)
    }

    private val event get() = ZbeAhead(crossing(1_000.0), 500)

    @Test fun voiceModeSpeaksAnAdvisoryWithoutSayingWhoIsAffected() {
        val (s, guide, chimes) = speaker(AlertSoundMode.VOICE)
        s.onAhead(event)
        val u = guide.spoken.single()
        assertEquals(VoicePriority.ADVISORY, u.priority)
        assertTrue(u.text.contains("low-emission zone"), u.text)
        assertTrue(u.text.contains("Check the access rules"), u.text)
        assertFalse(Regex("allowed|banned|forbidden|prohibited|permitted", RegexOption.IGNORE_CASE).containsMatchIn(u.text))
        assertTrue(chimes.played.isEmpty())
    }

    @Test fun soundModeChimesAndSilentModeDoesNothing() {
        val (s, guide, chimes) = speaker(AlertSoundMode.SOUND)
        s.onAhead(event)
        assertEquals(1, chimes.played.size)
        assertTrue(guide.spoken.isEmpty())
        val (q, g2, c2) = speaker(AlertSoundMode.SILENT)
        q.onAhead(event)
        assertTrue(g2.spoken.isEmpty() && c2.played.isEmpty())
    }

    @Test fun theNavigationMuteAndAnImminentManeuverWin() {
        val (muted, g1, c1) = speaker(AlertSoundMode.VOICE, NavSettings(voiceEnabled = false))
        muted.onAhead(event)
        assertTrue(g1.spoken.isEmpty() && c1.played.isEmpty())
        val (busy, g2, c2) = speaker(AlertSoundMode.SOUND, imminent = true)
        busy.onAhead(event)
        assertTrue(g2.spoken.isEmpty() && c2.played.isEmpty())
    }

    @Test fun phrasesInBothLanguagesAndUnits() {
        val en = ZbePhrases.ahead(event, DistanceUnits.METRIC, VoiceLanguage.EN)
        val es = ZbePhrases.ahead(event, DistanceUnits.METRIC, VoiceLanguage.ES)
        assertTrue(en.contains("low-emission zone"), en)
        assertTrue(es.contains("zona de bajas emisiones"), es)
        assertTrue(es.contains("normas de acceso"), es)
    }
}
