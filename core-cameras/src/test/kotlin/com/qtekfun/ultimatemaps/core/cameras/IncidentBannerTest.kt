package com.qtekfun.ultimatemaps.core.cameras

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.nav.NavState
import com.qtekfun.ultimatemaps.core.nav.NavStatus
import com.qtekfun.ultimatemaps.core.nav.RouteGeometry
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Time is a variable the tests move by hand; nothing here sleeps. */
class IncidentBannerTest {
    private val perMeter = 1.0 / TargetGrid.METERS_PER_DEGREE
    private val lat0 = 40.0
    private val lon0 = -3.7

    /** A straight road going north, 10 km long. */
    private val route = RouteGeometry(listOf(LatLon(lat0, lon0), LatLon(lat0 + 10_000 * perMeter, lon0)))

    private class Repo(var items: List<TrafficIncident>) : IncidentRepository {
        override fun incidentsIn(bounds: LatLonBounds, kinds: Set<IncidentKind>, limit: Int) =
            items.filter { it.kind in kinds && bounds.contains(it.location) }
        override fun incident(id: String) = items.firstOrNull { it.id == id }
        override val lastUpdateMillis = MutableStateFlow<Long?>(null)
    }

    private fun incident(id: String, alongMeters: Double, kind: IncidentKind = IncidentKind.CONGESTION, lateral: Double = 0.0, dir: Int? = null, endAlong: Double? = null) =
        TrafficIncident(
            id, kind, "A-1", LatLon(lat0 + alongMeters * perMeter, lon0 + lateral * perMeter), endAlong?.let { LatLon(lat0 + it * perMeter, lon0) },
            dir, null, null, null, null, null,
        )

    private var now = 1_000_000L
    private var settings = CameraSettings(incidentsEnabled = true)

    private fun machine(vararg items: TrafficIncident): Pair<IncidentBannerMachine, Repo> {
        val repo = Repo(items.toList())
        val m = IncidentBannerMachine({ repo }, { settings }, { now })
        m.onRoute(route)
        return m to repo
    }

    private fun IncidentBannerMachine.at(along: Double, speed: Double = 25.0) =
        onProgress(along, lat0 + along * perMeter, lon0, speed)

    @Test
    fun anIncidentAheadOnTheRouteShowsForFiveSecondsThenGoesByItself() {
        val (m, _) = machine(incident("a", 600.0))
        assertNull(m.state.value)
        m.at(0.0)
        val s = assertNotNull(m.state.value)
        assertEquals(IncidentKind.CONGESTION, s.kind)
        assertEquals(600, s.distanceMeters)
        assertEquals(5, s.remainingSeconds)
        assertEquals(1f, s.fraction)
        for ((elapsed, seconds) in listOf(1L to 4, 2L to 3, 3L to 2, 4L to 1)) {
            now = 1_000_000L + elapsed * 1000
            m.tick()
            assertEquals(seconds, m.state.value?.remainingSeconds, "after $elapsed s")
        }
        now = 1_000_000L + 4_999
        m.tick()
        assertEquals(1, m.state.value?.remainingSeconds)
        now = 1_000_000L + 5_000
        m.tick()
        assertNull(m.state.value)
    }

    @Test
    fun theDistanceFallsAsTheDriverApproaches() {
        val (m, _) = machine(incident("a", 600.0))
        m.at(0.0)
        m.at(200.0)
        assertEquals(400, m.state.value?.distanceMeters)
    }

    @Test
    fun tappingTheBannerDismissesItEarlyAndItDoesNotComeBack() {
        val (m, _) = machine(incident("a", 600.0))
        m.at(0.0)
        m.dismiss()
        assertNull(m.state.value)
        m.at(10.0)
        m.at(20.0)
        assertNull(m.state.value)
    }

    @Test
    fun theSameIncidentIsNotShownAgainAfterItsTimeRunsOut() {
        val (m, _) = machine(incident("a", 600.0))
        m.at(0.0)
        now += 5_000
        m.tick()
        assertNull(m.state.value)
        m.at(30.0)
        assertNull(m.state.value)
    }

    @Test
    fun severalQueueNearestFirstFiveSecondsEachAndAtMostThreeWait() {
        val (m, _) = machine(
            incident("far", 900.0), incident("mid", 700.0), incident("near", 500.0),
            incident("q1", 800.0), incident("q2", 850.0),
        )
        m.at(0.0, speed = 40.0)
        assertEquals("near", m.state.value?.id)
        // One on screen and three waiting at most; the ones left out come back, in order, when there is room.
        val shown = mutableListOf("near")
        repeat(5) {
            now += 5_000
            m.tick()
            m.at(0.0, speed = 40.0)
            m.state.value?.id?.let { if (it !in shown) shown += it }
        }
        assertEquals(listOf("near", "mid", "q1", "q2", "far"), shown)
    }

    @Test
    fun aQueuedIncidentWaitsItsTurnWithAFreshFiveSeconds() {
        val (m, _) = machine(incident("a", 500.0), incident("b", 700.0))
        m.at(0.0)
        assertEquals("a", m.state.value?.id)
        now += 3_000
        m.dismiss()
        assertEquals("b", m.state.value?.id)
        assertEquals(5, m.state.value?.remainingSeconds)
        now += 5_000
        m.tick()
        assertNull(m.state.value)
    }

    @Test
    fun aRecalculatedRouteDropsTheBannerButRemembersWhatWasShownAndAllowsADifferentIncident() {
        val (m, repo) = machine(incident("a", 600.0), incident("b", 650.0))
        m.at(0.0)
        assertEquals("a", m.state.value?.id)
        m.onRoute(route) // reroute
        assertNull(m.state.value)
        m.at(0.0)
        // "a" was shown on this trip: it is not repeated; "b" (waiting, never shown) is a different incident.
        assertEquals("b", m.state.value?.id)
        repo.items = repo.items + incident("c", 700.0)
        now += 5_000
        m.tick()
        m.at(0.0)
        assertEquals("c", m.state.value?.id)
    }

    @Test
    fun aNewTripShowsTheSameIncidentAgain() {
        val (m, _) = machine(incident("a", 600.0))
        m.at(0.0)
        m.reset()
        m.onRoute(route)
        m.at(0.0)
        assertEquals("a", m.state.value?.id)
    }

    @Test
    fun incidentsOffTheRouteBehindOrTooFarAreIgnored() {
        val (m, _) = machine(
            incident("parallel", 600.0, lateral = 80.0),
            incident("behind", 100.0),
            incident("far", 3_000.0),
        )
        m.at(300.0)
        assertNull(m.state.value)
    }

    @Test
    fun anIncidentJustAheadOrOneWeAreInsideIsMarkedSeenWithoutABanner() {
        val (m, _) = machine(incident("close", 330.0), incident("inside", 100.0, endAlong = 900.0))
        m.at(300.0)
        assertNull(m.state.value)
    }

    @Test
    fun theDirectionNamedByTheFeedMustAgreeWithTheRoute() {
        val (m, _) = machine(incident("south", 600.0, dir = 180), incident("north", 700.0, dir = 0))
        m.at(0.0)
        assertEquals("north", m.state.value?.id)
    }

    @Test
    fun onlyEnabledKindsCountAndEverythingOffClearsTheBanner() {
        val (m, _) = machine(incident("w", 600.0, IncidentKind.ROADWORKS), incident("a", 700.0, IncidentKind.ACCIDENT))
        m.at(0.0)
        assertEquals("a", m.state.value?.id) // roadworks are off
        settings = CameraSettings(incidentsEnabled = true, roadworksEnabled = true)
        m.at(1.0)
        settings = CameraSettings()
        m.at(2.0)
        assertNull(m.state.value)
    }

    @Test
    fun v16CountsWhenItsOwnSwitchIsOn() {
        settings = CameraSettings(v16Enabled = true)
        val (m, _) = machine(incident("v", 600.0, IncidentKind.V16))
        m.at(0.0)
        assertEquals(IncidentKind.V16, m.state.value?.kind)
    }

    @Test
    fun withoutARouteNothingShows() {
        val (m, _) = machine(incident("a", 600.0))
        m.onRoute(null)
        m.at(0.0)
        assertNull(m.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theFeedDrivesTheCountdownFromItsTickerOnlyWhileABannerIsShown() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val navState = MutableStateFlow<NavState?>(null)
        val plan = MutableStateFlow<RoutePlan?>(RoutePlan(listOf(LatLon(lat0, lon0), LatLon(lat0 + 10_000 * perMeter, lon0)), 10_000.0, 600.0))
        val repo = Repo(listOf(incident("a", 600.0)))
        val machine = IncidentBannerMachine({ repo }, { settings }, { now })
        val ticks = MutableSharedFlow<Unit>()
        val feed = IncidentBannerFeed(scope, navState, plan, machine, ticks)
        feed.start()
        assertEquals(0, ticks.subscriptionCount.value) // nothing on screen: the ticker is not collected
        navState.value = NavState(
            NavStatus.ON_ROUTE, LatLon(lat0, lon0), 0f, 0.0, 10_000.0, 600.0, null, null, null, false, emptyList(), false,
            25.0, 0.0, 0,
        )
        assertEquals("a", machine.state.value?.id)
        assertEquals(1, ticks.subscriptionCount.value)
        now += 2_000
        scope.testScheduler.advanceUntilIdle()
        kotlinx.coroutines.runBlocking { ticks.emit(Unit) }
        assertEquals(3, machine.state.value?.remainingSeconds)
        now += 3_000
        kotlinx.coroutines.runBlocking { ticks.emit(Unit) }
        assertNull(machine.state.value)
        assertEquals(0, ticks.subscriptionCount.value)
        feed.close()
    }
}
