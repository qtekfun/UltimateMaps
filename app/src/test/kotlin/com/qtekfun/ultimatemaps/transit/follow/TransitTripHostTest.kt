package com.qtekfun.ultimatemaps.transit.follow

import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.map.SimulatedLocationSource
import com.qtekfun.ultimatemaps.core.transit.follow.FollowPhase
import com.qtekfun.ultimatemaps.core.transit.follow.TransitTripController
import com.qtekfun.ultimatemaps.core.transit.follow.TransitTripStore
import com.qtekfun.ultimatemaps.core.voice.InMemoryNavSettingsStore
import com.qtekfun.ultimatemaps.core.voice.NavSettings
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguagePref
import com.qtekfun.ultimatemaps.nav.InMemoryNavUiPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The host (start, stop, resume, mute, glove, prompts to the voice) over a simulated location and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransitTripHostTest {
    private val dir = java.nio.file.Files.createTempDirectory("transit-host-test").toFile()
    private val file = File(dir, "trip.bin")

    @AfterTest fun cleanup() {
        dir.deleteRecursively()
    }

    private class FakeService : TripServiceControl {
        val calls = mutableListOf<String>()
        override fun start() { calls += "start" }
        override fun resume() { calls += "resume" }
        override fun stop() { calls += "stop" }
    }

    private class Rig(test: TestScope, file: File) {
        // The host and the controller live forever (like in the application): they run in the background scope of the test.
        val scope = test
        val bg = test.backgroundScope
        val location = SimulatedLocationSource()
        val clock = { TripTestSupport.T0 * 1000 + scope.testScheduler.currentTime }
        val service = FakeService()
        val guide = FakeGuide()
        val prefs = InMemoryNavUiPrefs()
        val navSettings = InMemoryNavSettingsStore(NavSettings(voiceLanguage = VoiceLanguagePref.EN))
        val controller = TransitTripController(bg, location, TransitTripStore(file, clock), clock = clock)
        val host = TransitTripHost(
            bg, controller, service, prefs, navSettings,
            TransitTripSpeaker(guide, navSettings.settings, { AlertSoundMode.VOICE }, locale = { Locale.ENGLISH }),
            io = StandardTestDispatcher(scope.testScheduler),
        )
    }

    private fun TestScope.at(sec: Long) {
        val delta = sec * 1000 - testScheduler.currentTime
        if (delta > 0) advanceTimeBy(delta)
        runCurrent()
    }

    private fun fix(p: LatLon, speed: Float? = null) = LocationFix(p, accuracyMeters = 10f, speedMps = speed)

    @Test fun startingStartsTheControllerTheServiceAndTheVoice() = runTest {
        val rig = Rig(this, file)
        assertTrue(rig.host.start(TripTestSupport.itinerary(), java.time.ZoneId.of(TripTestSupport.ZONE)))
        runCurrent()
        assertTrue(rig.host.active)
        assertEquals(listOf("start"), rig.service.calls)
        assertNotNull(rig.host.ui.value.trip)
        assertEquals(listOf(com.qtekfun.ultimatemaps.core.voice.VoiceLanguage.EN), rig.guide.prepared)
        rig.host.stop()
    }

    @Test fun aWalkOnlyItineraryIsRefusedAndStartsNothing() = runTest {
        val rig = Rig(this, file)
        val walk = com.qtekfun.ultimatemaps.core.transit.Itinerary(listOf(TripTestSupport.itinerary().legs[0]))
        assertFalse(rig.host.start(walk, java.time.ZoneId.of(TripTestSupport.ZONE)))
        assertTrue(rig.service.calls.isEmpty())
    }

    @Test fun aBoardNowPromptIsSpokenWhileTheTripRuns() = runTest {
        val rig = Rig(this, file)
        rig.host.start(TripTestSupport.itinerary(), java.time.ZoneId.of(TripTestSupport.ZONE))
        runCurrent()
        at(400)
        rig.location.emit(fix(TripTestSupport.metroStops[0].point))
        at(560)
        assertEquals(listOf("Board line L5 towards Westbound now"), rig.guide.spoken.map { it.text })
        assertEquals(FollowPhase.WAITING, rig.host.ui.value.trip!!.follow.phase)
        rig.host.stop()
    }

    @Test fun muteAndGloveGoThroughTheNavigationSettings() = runTest {
        val rig = Rig(this, file)
        runCurrent()
        assertTrue(rig.host.ui.value.voiceOn)
        rig.host.setVoice(false)
        runCurrent()
        assertFalse(rig.host.ui.value.voiceOn)
        assertFalse(rig.navSettings.settings.value.voiceEnabled)
        rig.host.setGlove(true)
        runCurrent()
        assertTrue(rig.host.ui.value.glove)
        assertTrue(rig.prefs.glove)
    }

    @Test fun stoppingEndsEverythingAndForgetsTheSavedTrip() = runTest {
        val rig = Rig(this, file)
        rig.host.start(TripTestSupport.itinerary(), java.time.ZoneId.of(TripTestSupport.ZONE))
        runCurrent()
        assertTrue(file.exists())
        rig.host.stop()
        runCurrent()
        assertFalse(rig.host.active)
        assertFalse(file.exists())
        assertEquals(listOf("start", "stop"), rig.service.calls)
        assertEquals(null, rig.host.ui.value.trip)
    }

    @Test fun aTripLeftOnDiskIsOfferedAndResumed() = runTest {
        val first = Rig(this, file)
        first.host.start(TripTestSupport.itinerary(), java.time.ZoneId.of(TripTestSupport.ZONE))
        runCurrent()
        // The process dies: a second host (a new process) finds the file.
        val second = Rig(this, file)
        second.host.refreshResumable()
        runCurrent()
        assertTrue(second.host.ui.value.resumable)
        second.host.resume()
        runCurrent()
        assertTrue(second.host.active)
        assertFalse(second.host.ui.value.resumable)
        assertEquals(listOf("resume"), second.service.calls)
        first.host.stop()
        second.host.stop()
    }

    @Test fun discardingForgetsTheTrip() = runTest {
        val first = Rig(this, file)
        first.host.start(TripTestSupport.itinerary(), java.time.ZoneId.of(TripTestSupport.ZONE))
        runCurrent()
        val second = Rig(this, file)
        second.host.refreshResumable()
        runCurrent()
        second.host.discard()
        runCurrent()
        assertFalse(second.host.ui.value.resumable)
        assertFalse(file.exists())
        first.host.stop()
    }

    @Test fun nothingToResumeMeansNoOffer() = runTest {
        val rig = Rig(this, file)
        rig.host.refreshResumable()
        runCurrent()
        assertFalse(rig.host.ui.value.resumable)
    }
}
