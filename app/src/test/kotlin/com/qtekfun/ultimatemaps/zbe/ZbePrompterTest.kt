package com.qtekfun.ultimatemaps.zbe

import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundPlayer
import com.qtekfun.ultimatemaps.core.cameras.ChimeKind
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.nav.NavState
import com.qtekfun.ultimatemaps.core.nav.NavStatus
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
import com.qtekfun.ultimatemaps.core.voice.InMemoryNavSettingsStore
import com.qtekfun.ultimatemaps.core.voice.Utterance
import com.qtekfun.ultimatemaps.core.voice.VoiceGuide
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguage
import com.qtekfun.ultimatemaps.core.voice.VoiceStatus
import com.qtekfun.ultimatemaps.core.zbe.InMemoryZbeSettingsStore
import com.qtekfun.ultimatemaps.core.zbe.ZbeAheadSpeaker
import com.qtekfun.ultimatemaps.core.zbe.ZbeDataset
import com.qtekfun.ultimatemaps.core.zbe.ZbePolygon
import com.qtekfun.ultimatemaps.core.zbe.ZbeRepository
import com.qtekfun.ultimatemaps.core.zbe.ZbeRing
import com.qtekfun.ultimatemaps.core.zbe.ZbeSettings
import com.qtekfun.ultimatemaps.core.zbe.ZbeZone
import com.qtekfun.ultimatemaps.nav.navState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one-time "ahead" prompt over a simulated navigation: no real time, no threads of its own. */
@OptIn(ExperimentalCoroutinesApi::class)
class ZbePrompterTest {
    // A square zone, 0.02 degrees a side, with its south edge at latitude 40.05. The route runs due north along lon -3.0.
    private val zone = ZbeZone(
        "z0", "Centro", "Madrid", "",
        listOf(ZbePolygon(listOf(ZbeRing(doubleArrayOf(40.05, -3.01, 40.05, -2.99, 40.07, -2.99, 40.07, -3.01))))),
    )
    private val route = RoutePlan(List(201) { LatLon(40.0 + it * 0.0005, -3.0) }, 11_000.0, 900.0) // 40.0 .. 40.1
    private val entryMeters = 0.05 * 111_195.0

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

    private class Rig(settings: ZbeSettings, zones: List<ZbeZone>, profile: RoutingProfile, scope: kotlinx.coroutines.CoroutineScope, dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        val guide = FakeGuide()
        val chimes = ArrayList<ChimeKind>()
        val store = InMemoryZbeSettingsStore(settings)
        val repo = ZbeRepository().also { it.install(ZbeDataset(1L, 1, zones)) }
        val state = MutableStateFlow<NavState?>(null)
        val route = MutableStateFlow<RoutePlan?>(null)
        var now = 0L
        val prompter = ZbePrompter(
            scope, store.settings, repo, state, route, { profile },
            ZbeAheadSpeaker(guide, InMemoryNavSettingsStore().settings, { store.settings.value.promptMode }, { false }, AlertSoundPlayer { k, _ -> chimes += k }) { Locale.ENGLISH },
            clock = { now }, compute = dispatcher,
        ).also { it.start() }
    }

    private fun TestScope.rig(settings: ZbeSettings = ZbeSettings(enabled = true), profile: RoutingProfile = RoutingProfile.CAR, zones: List<ZbeZone> = listOf(zone)) =
        Rig(settings, zones, profile, backgroundScope, UnconfinedTestDispatcher(testScheduler))

    private fun Rig.at(meters: Double, speed: Double = 14.0) {
        state.value = navState(speedMps = speed).copy(traveledMeters = meters)
    }

    @Test fun promptsOnceBannerAndChimeWhenTheZoneIsAhead() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.route.value = route
        r.at(0.0)
        assertNull(r.prompter.banner.value, "5.5 km away")
        r.at(entryMeters - 600)
        assertNull(r.prompter.banner.value, "600 m is beyond the look-ahead at 14 m/s (400 m minimum, 350 m by speed)")
        r.at(entryMeters - 380)
        val b = assertNotNull(r.prompter.banner.value)
        assertEquals("Centro (Madrid)", b.zoneLabel)
        assertEquals(380, b.distanceMeters)
        assertEquals(listOf(ChimeKind.INCIDENT), r.chimes)
        r.at(entryMeters - 300)
        assertEquals(300, r.prompter.banner.value!!.distanceMeters, "the distance counts down")
        assertEquals(1, r.chimes.size, "once")
        r.at(entryMeters + 10)
        assertNull(r.prompter.banner.value, "gone when the zone is reached")
        assertEquals(1, r.chimes.size)
    }

    @Test fun voiceModeSpeaksTheSentence() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(ZbeSettings(enabled = true, promptMode = AlertSoundMode.VOICE))
        r.route.value = route
        r.at(entryMeters - 350)
        val said = r.guide.spoken.single().text
        assertTrue(said.contains("low-emission zone"), said)
        assertTrue(r.chimes.isEmpty())
    }

    @Test fun silentModeStillShowsTheBanner() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(ZbeSettings(enabled = true, promptMode = AlertSoundMode.SILENT))
        r.route.value = route
        r.at(entryMeters - 350)
        assertNotNull(r.prompter.banner.value)
        assertTrue(r.guide.spoken.isEmpty() && r.chimes.isEmpty())
    }

    @Test fun nothingHappensWithTheSwitchOffOrOnFootOrWithoutData() = runTest(UnconfinedTestDispatcher()) {
        val off = rig(ZbeSettings(enabled = false))
        off.route.value = route
        off.at(entryMeters - 350)
        assertNull(off.prompter.banner.value)
        val foot = rig(profile = RoutingProfile.FOOT)
        foot.route.value = route
        foot.at(entryMeters - 350)
        assertNull(foot.prompter.banner.value)
        val none = rig(zones = emptyList())
        none.route.value = route
        none.at(entryMeters - 350)
        assertNull(none.prompter.banner.value)
        assertTrue(off.chimes.isEmpty() && foot.chimes.isEmpty() && none.chimes.isEmpty())
    }

    @Test fun estimatedPositionsAndOffRouteFixesAreIgnored() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.route.value = route
        r.state.value = navState(NavStatus.NO_SIGNAL).copy(traveledMeters = entryMeters - 350)
        assertNull(r.prompter.banner.value)
        r.state.value = navState(NavStatus.OFF_ROUTE).copy(traveledMeters = entryMeters - 350)
        assertNull(r.prompter.banner.value)
    }

    @Test fun theBannerClearsWhenTheNavigationEndsOrTheRouteChanges() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.route.value = route
        r.at(entryMeters - 350)
        assertNotNull(r.prompter.banner.value)
        r.state.value = null
        assertNull(r.prompter.banner.value)
        r.at(entryMeters - 350)
        assertNull(r.prompter.banner.value, "already announced on this trip")
        r.route.value = null
        r.route.value = route
        r.at(entryMeters - 345) // a different position: an equal state would not be emitted again
        assertNotNull(r.prompter.banner.value, "a new trip announces again")
    }

    @Test fun aStaleBannerExpires() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.route.value = route
        r.at(entryMeters - 350)
        assertNotNull(r.prompter.banner.value)
        r.now = ZbePrompter.BANNER_MILLIS + 1
        r.at(entryMeters - 340)
        assertNull(r.prompter.banner.value)
    }

    @Test fun aTripThatStartsInsideTheZoneGetsNoPrompt() = runTest(UnconfinedTestDispatcher()) {
        val inside = RoutePlan(List(21) { LatLon(40.055 + it * 0.0005, -3.0) }, 1_100.0, 100.0)
        val r = rig()
        r.route.value = inside
        r.at(0.0)
        r.at(300.0)
        assertNull(r.prompter.banner.value)
        assertTrue(r.chimes.isEmpty())
    }
}
