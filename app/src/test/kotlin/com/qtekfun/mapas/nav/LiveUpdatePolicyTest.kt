package com.qtekfun.mapas.nav

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.nav.ManeuverInfo
import com.qtekfun.mapas.core.nav.NavProblem
import com.qtekfun.mapas.core.nav.NavState
import com.qtekfun.mapas.core.nav.NavStatus
import com.qtekfun.mapas.core.routing.Maneuver
import com.qtekfun.mapas.core.routing.TurnType
import com.qtekfun.mapas.core.voice.DistanceUnits
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure JVM tests of the chip text and of the decision to promote the navigation notification. */
class LiveUpdatePolicyTest {
    private val words = ChipWords("Reroute", "No GPS")
    private val en = Locale.ENGLISH
    private val es = Locale.forLanguageTag("es-ES")

    private fun state(
        status: NavStatus = NavStatus.ON_ROUTE,
        nextMeters: Double? = 160.0,
        traveled: Double = 2_500.0,
        remaining: Double = 7_500.0,
    ) = NavState(
        status, LatLon(40.0, -3.0), 0f, traveled, remaining, 600.0,
        nextMeters?.let { ManeuverInfo(Maneuver(5, TurnType.RIGHT, "Calle Mayor"), it) },
        null, null, false, emptyList(), false, 10.0, 0.0, 0,
    )

    private fun plan(
        s: NavState? = state(), problem: NavProblem? = null, enabled: Boolean = true, sdk: Int = 36,
        units: DistanceUnits = DistanceUnits.METRIC, locale: Locale = en,
    ) = LiveUpdatePolicy.plan(enabled, sdk, s, problem, units, locale, words)

    @Test fun `metric distances are compact and keep the locale decimal separator`() {
        assertEquals("160 m", LiveUpdatePolicy.compactDistance(160.0, DistanceUnits.METRIC, en))
        assertEquals("0 m", LiveUpdatePolicy.compactDistance(-3.0, DistanceUnits.METRIC, en))
        assertEquals("1.2 km", LiveUpdatePolicy.compactDistance(1_240.0, DistanceUnits.METRIC, en))
        assertEquals("1,2 km", LiveUpdatePolicy.compactDistance(1_240.0, DistanceUnits.METRIC, es))
        assertEquals("35 km", LiveUpdatePolicy.compactDistance(35_400.0, DistanceUnits.METRIC, en))
        assertEquals("10 km", LiveUpdatePolicy.compactDistance(9_960.0, DistanceUnits.METRIC, en))
    }

    @Test fun `imperial distances use feet below 1000 ft and miles above`() {
        assertEquals("160 ft", LiveUpdatePolicy.compactDistance(49.0, DistanceUnits.IMPERIAL, en))
        assertEquals("0.3 mi", LiveUpdatePolicy.compactDistance(500.0, DistanceUnits.IMPERIAL, en))
        assertEquals("22 mi", LiveUpdatePolicy.compactDistance(35_400.0, DistanceUnits.IMPERIAL, en))
    }

    @Test fun `no distance is longer than the suggested 7 characters`() {
        for (u in DistanceUnits.entries) for (m in listOf(0.0, 4.0, 99.0, 999.0, 1_000.0, 9_949.0, 9_951.0, 99_999.0, 100_000.0, 999_999.0)) {
            val t = LiveUpdatePolicy.compactDistance(m, u, en)
            assertTrue(t.length <= 7, "$m $u -> $t")
        }
    }

    @Test fun `on route the chip shows the distance to the next maneuver and the progress of the trip`() {
        val p = assertNotNull(plan())
        assertEquals("160 m", p.chipText)
        assertEquals(25, p.progressPercent)
    }

    @Test fun `without a next maneuver the chip shows the remaining distance`() {
        assertEquals("7.5 km", plan(state(nextMeters = null))?.chipText)
    }

    @Test fun `off route, rerouting, no signal and problems use a short word, never blank`() {
        assertEquals("Reroute", plan(state(NavStatus.OFF_ROUTE))?.chipText)
        assertEquals("Reroute", plan(state(NavStatus.REROUTING))?.chipText)
        assertEquals("No GPS", plan(state(NavStatus.NO_SIGNAL))?.chipText)
        assertEquals("No GPS", plan(problem = NavProblem.LOCATION_DISABLED)?.chipText)
        assertEquals("No GPS", plan(problem = NavProblem.LOCATION_PERMISSION)?.chipText)
        assertTrue(plan(state(NavStatus.OFF_ROUTE))!!.chipText.isNotBlank())
    }

    @Test fun `it does not promote when off, below Android 16, without a state or after arrival`() {
        assertNull(plan(enabled = false))
        assertNull(plan(sdk = 35))
        assertNull(plan(sdk = 34))
        assertNull(plan(s = null))
        assertNull(plan(state(NavStatus.ARRIVED)))
        assertNotNull(plan(sdk = 37))
    }

    @Test fun `progress is clamped and is zero for an empty route`() {
        assertEquals(0, plan(state(traveled = 0.0, remaining = 0.0))?.progressPercent)
        assertEquals(100, plan(state(traveled = 10_000.0, remaining = 0.0))?.progressPercent)
    }

    @Test fun `an identical update is skipped, a changed chip or a forced one is posted`() {
        val d = NotificationDedupe()
        val a = LiveUpdatePlan("160 m", 25)
        assertTrue(d.shouldPost("t", "x", a))
        assertTrue(!d.shouldPost("t", "x", a))
        assertTrue(d.shouldPost("t", "x", LiveUpdatePlan("150 m", 25)))
        assertTrue(d.shouldPost("t", "x", LiveUpdatePlan("150 m", 26)))
        assertTrue(d.shouldPost("t", "y", LiveUpdatePlan("150 m", 26)))
        assertTrue(d.shouldPost("t", "y", null))
        assertTrue(!d.shouldPost("t", "y", null))
        assertTrue(d.shouldPost("t", "y", null, force = true))
    }
}
