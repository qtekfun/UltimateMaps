package com.qtekfun.ultimatemaps.core.cameras

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.LocationFix
import com.qtekfun.ultimatemaps.core.map.SimulatedLocationSource
import com.qtekfun.ultimatemaps.core.nav.NavEnvironment
import com.qtekfun.ultimatemaps.core.nav.NavStateStore
import com.qtekfun.ultimatemaps.core.nav.NavigationController
import com.qtekfun.ultimatemaps.core.nav.RouteSimulator
import com.qtekfun.ultimatemaps.core.routing.RouteGuidance
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.voice.InMemoryNavSettingsStore
import com.qtekfun.ultimatemaps.core.voice.NavSettings
import com.qtekfun.ultimatemaps.core.voice.Utterance
import com.qtekfun.ultimatemaps.core.voice.VoiceGuide
import com.qtekfun.ultimatemaps.core.voice.VoiceLanguage
import com.qtekfun.ultimatemaps.core.voice.VoicePriority
import com.qtekfun.ultimatemaps.core.voice.VoiceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole chain with fake data and a simulated location: a real [NavigationController] following a route, the
 * route-based feed, the warner, the visual alert and the voice. Time is the test scheduler's virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraAlertsEndToEndTest {
    private val perMeter = 1.0 / TargetGrid.METERS_PER_DEGREE
    private val lat0 = 40.0
    private val lon0 = -3.7
    private val on = CameraSettings(fixedEnabled = true, acknowledged = true)

    private class Env : NavEnvironment {
        override fun hasLocationPermission() = true
        override fun isLocationEnabled() = true
        override fun isPowerSaveMode() = false
    }

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

    private fun camera(id: String, metersNorth: Double, limit: Int? = 120) =
        AlertTarget(id, id, AlertCategory.FIXED_CAMERA, lat0 + metersNorth * perMeter, lon0, 0, AxisSense.BOTH, 50, limit)

    /** A straight 6 km route north with a vertex every 100 m (no maneuvers: the alert logic does not need them). */
    private fun plan(): RoutePlan {
        val points = (0..60).map { LatLon(lat0 + it * 100.0 * perMeter, lon0) }
        return RoutePlan(points, 6000.0, 600.0, RouteGuidance.EMPTY)
    }

    private class Rig(scope: TestScope, val settings: CameraSettings, targets: List<AlertTarget>, nav: NavSettings, val maneuverImminent: () -> Boolean = { false }) {
        val source = SimulatedLocationSource()
        val store = InMemoryCameraSettingsStore(settings)
        val navSettings = InMemoryNavSettingsStore(nav)
        val guide = FakeGuide()
        val banner = AlertBannerTracker()
        val voice = AlertVoice(guide, navSettings.settings, { maneuverImminent() }) { Locale.ENGLISH }
        val alerts = ArrayList<AlertEvent>()
        val warner = AlertWarner(listOf(TargetGrid(targets)), { store.settings.value }) { banner.onAlert(it); alerts += it; voice.onAlert(it) }
        val controller = NavigationController(
            scope.backgroundScope, source, NavStateStore(File.createTempFile("navc", ".bin").also { it.delete(); it.deleteOnExit() }, { scope.testScheduler.currentTime + 1_000L }),
            Env(), null, clock = { scope.testScheduler.currentTime + 1_000L }, io = UnconfinedTestDispatcher(scope.testScheduler),
            persistEveryMillis = 5_000L, watchEveryMillis = 1_000L,
        )
        val feed = NavAlertFeed(scope.backgroundScope, controller.state, controller.route, warner, banner) { scope.testScheduler.currentTime }
    }

    /** Rides the route from [startAlong] at [speed] m/s, one fix per second, sampling the visual alert after each fix. */
    private suspend fun TestScope.ride(r: Rig, seconds: Int, startAlong: Double, speed: Double, shown: MutableList<AlertBannerState?>) {
        val sim = RouteSimulator(plan().geometry, speed, startMillis = testScheduler.currentTime + 2_000L, startAlongMeters = startAlong)
        for (f in sim.fixes().take(seconds)) {
            val wait = f.timeMillis - 1_000L - testScheduler.currentTime
            if (wait > 0) delay(wait)
            r.source.emit(f)
            testScheduler.runCurrent()
            shown += r.banner.state.value
        }
    }

    @Test fun drivingARouteWarnsOnceShowsTheCountdownAndSpeaksAdvisory() = runTest {
        val r = Rig(this, on, listOf(camera("c", 3000.0)), NavSettings())
        assertTrue(r.controller.start(plan()))
        r.feed.start()
        testScheduler.runCurrent()
        val shown = ArrayList<AlertBannerState?>()
        ride(r, 120, 1500.0, 25.0, shown) // 1500 m .. 4500 m: passes the camera at 3000 m
        assertEquals(1, r.alerts.size, "one warning for the camera, none for the rest of the trip")
        assertEquals(AlertStage.FAR, r.alerts[0].stage)
        assertTrue(r.alerts[0].distanceMeters in 700..760, "30 s at 25 m/s, was ${r.alerts[0].distanceMeters}")
        val spoken = r.guide.spoken.single()
        assertTrue(spoken.text.startsWith("In 7") && spoken.text.contains("possible fixed speed camera. Limit 120"), "spoken: ${spoken.text}")
        assertEquals(VoicePriority.ADVISORY, spoken.priority, "never competes with a driving instruction")
        val visible = shown.filterNotNull()
        assertTrue(visible.isNotEmpty(), "the visual alert appeared")
        assertEquals(AlertCategory.FIXED_CAMERA, visible.first().category)
        assertEquals(120, visible.first().limitKmh)
        assertTrue(visible.first().distanceMeters in 700..760)
        assertTrue(visible.map { it.distanceMeters }.zipWithNext().all { (a, b) -> b <= a }, "the distance only goes down")
        assertTrue(visible.last().distanceMeters < 100, "it keeps counting down until the camera, was ${visible.last().distanceMeters}")
        assertNull(shown.last(), "and it goes away once the camera is behind")
        r.controller.stop()
    }

    @Test fun speedingOverTheLimitGetsASecondNearWarningThatIsSpokenAndShown() = runTest {
        val r = Rig(this, on, listOf(camera("c", 3000.0, limit = 70)), NavSettings()) // 25 m/s = 90 km/h
        r.controller.start(plan())
        r.feed.start()
        testScheduler.runCurrent()
        ride(r, 120, 1500.0, 25.0, ArrayList())
        assertEquals(listOf(AlertStage.FAR, AlertStage.NEAR), r.alerts.map { it.stage })
        assertTrue(r.alerts[1].speeding)
        assertTrue(r.guide.spoken.last().text.endsWith("Slow down"), r.guide.spoken.last().text)
        r.controller.stop()
    }

    @Test fun mutedVoiceStillShowsTheVisualAlertAndImportantOnlyDoesNotSilenceIt() = runTest {
        val muted = Rig(this, on, listOf(camera("c", 3000.0)), NavSettings(voiceEnabled = false))
        muted.controller.start(plan())
        muted.feed.start()
        testScheduler.runCurrent()
        val shown = ArrayList<AlertBannerState?>()
        ride(muted, 60, 1500.0, 25.0, shown)
        assertEquals(1, muted.alerts.size)
        assertTrue(muted.guide.spoken.isEmpty(), "Mute is the navigation voice switch: nothing is spoken")
        assertTrue(shown.any { it != null }, "but the alert is on screen")
        muted.controller.stop()

        val important = Rig(this, on, listOf(camera("c", 3000.0)), NavSettings(importantOnly = true))
        important.controller.start(plan())
        important.feed.start()
        testScheduler.runCurrent()
        ride(important, 60, 1500.0, 25.0, ArrayList())
        assertEquals(1, important.guide.spoken.size, "'important prompts only' is about maneuvers: camera alerts are still spoken")
        important.controller.stop()
    }

    @Test fun anImminentManeuverKeepsTheAlertSilentButVisible() = runTest {
        val r = Rig(this, on, listOf(camera("c", 3000.0)), NavSettings(), maneuverImminent = { true })
        r.controller.start(plan())
        r.feed.start()
        testScheduler.runCurrent()
        val shown = ArrayList<AlertBannerState?>()
        ride(r, 60, 1500.0, 25.0, shown)
        assertEquals(1, r.alerts.size)
        assertTrue(r.guide.spoken.isEmpty(), "a turn is about to be announced: do not talk over it")
        assertTrue(shown.any { it != null })
        r.controller.stop()
    }

    @Test fun nothingHappensWhileTheSwitchesAreOffAndEndingTheTripClearsTheAlert() = runTest {
        val off = Rig(this, CameraSettings(), listOf(camera("c", 3000.0)), NavSettings())
        off.controller.start(plan())
        off.feed.start()
        testScheduler.runCurrent()
        val shown = ArrayList<AlertBannerState?>()
        ride(off, 60, 1500.0, 25.0, shown)
        assertTrue(off.alerts.isEmpty() && off.guide.spoken.isEmpty() && shown.all { it == null })
        off.controller.stop()

        val r = Rig(this, on, listOf(camera("c", 3000.0)), NavSettings())
        r.controller.start(plan())
        r.feed.start()
        testScheduler.runCurrent()
        ride(r, 40, 1500.0, 25.0, ArrayList())
        assertNotNull(r.banner.state.value)
        r.feed.close()
        assertNull(r.banner.state.value, "feed closed (navigation ended): the alert is gone")
        r.controller.stop()
    }

    // ---- the tracker on its own ----

    private fun event(meters: Int, limit: Int? = null, target: AlertTarget = camera("c", 1000.0, limit)) =
        AlertEvent(target, AlertStage.FAR, meters, limit, 90, false)

    @Test fun routeModeCountsDownByTheProgressAndDisappearsAtTheCamera() {
        val t = AlertBannerTracker()
        t.onRouteProgress(1000.0)
        t.onAlert(event(500, limit = 90))
        assertEquals(AlertBannerState(AlertCategory.FIXED_CAMERA, 500, 90), t.state.value)
        t.onRouteProgress(1200.0)
        assertEquals(300, t.state.value!!.distanceMeters)
        t.onRouteProgress(1499.0)
        assertEquals(0, t.state.value!!.distanceMeters, "1 m ahead rounds to the nearest 10 m")
        t.onRouteProgress(1500.0)
        assertNull(t.state.value)
    }

    @Test fun freeModeUsesTheStraightDistanceAndDisappearsWhenTheDriverMovesAway() {
        val t = AlertBannerTracker()
        val target = camera("c", 1000.0)
        fun at(m: Double) = t.onFreePosition(lat0 + m * perMeter, lon0)
        at(500.0)
        t.onAlert(event(500, target = target))
        at(700.0)
        assertEquals(300, t.state.value!!.distanceMeters)
        at(1010.0) // GPS jitter just past the camera: still shown
        assertNotNull(t.state.value)
        at(1100.0)
        assertNull(t.state.value, "clearly past it")
    }

    @Test fun aNewerAlertReplacesTheOneOnScreenAndClearIsFinal() {
        val t = AlertBannerTracker()
        t.onAlert(event(800))
        t.onAlert(AlertEvent(camera("v", 0.0), AlertStage.NEAR, 250, 50, 80, true))
        assertEquals(250, t.state.value!!.distanceMeters)
        assertEquals(50, t.state.value!!.limitKmh)
        t.clear()
        assertNull(t.state.value)
        t.onRouteProgress(10_000.0)
        assertNull(t.state.value, "progress without an alert shows nothing")
    }

    @Test fun theManeuverGuardUsesTheNavigationNearBand() {
        // near band: 10 s of travel, between 60 m and 400 m
        assertTrue(ManeuverGuard.blocksVoice(200.0, 25.0), "250 m at 90 km/h")
        assertTrue(!ManeuverGuard.blocksVoice(300.0, 25.0))
        assertTrue(ManeuverGuard.blocksVoice(60.0, 1.0), "never less than 60 m")
        assertTrue(!ManeuverGuard.blocksVoice(null, 25.0), "no maneuver ahead")
        assertTrue(!ManeuverGuard.blocksVoice(1500.0, 33.0))
    }

    @Test fun freeDrivingFeedTracksTheVisualAlert() {
        val source = SimulatedLocationSource()
        val banner = AlertBannerTracker()
        val warner = AlertWarner(listOf(TargetGrid(listOf(camera("c", 2000.0)))), { on }) { banner.onAlert(it) }
        val feed = FreeDrivingFeed(source, warner, banner) { 0L }
        feed.start()
        var second = 0
        var m = 1000.0
        while (m <= 1950.0) {
            source.emit(LocationFix(LatLon(lat0 + m * perMeter, lon0), bearingDegrees = 0f, speedMps = 25f, timeMillis = second * 1000L))
            second++; m += 25.0
        }
        val s = banner.state.value
        assertNotNull(s, "approaching the camera in free driving shows the alert")
        assertTrue(s.distanceMeters <= 100)
        feed.stop()
        assertNull(banner.state.value)
    }
}
