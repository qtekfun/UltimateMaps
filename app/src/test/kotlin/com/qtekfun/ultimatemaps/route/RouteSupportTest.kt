package com.qtekfun.ultimatemaps.route

import android.util.Log
import com.qtekfun.ultimatemaps.core.routing.RoutingProfile
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteSupportTest {
    @Test
    fun formatsDistanceAndDuration() {
        assertEquals("340 m", RouteFormat.distance(342.0, Locale.ENGLISH))
        assertEquals("12.3 km", RouteFormat.distance(12_345.0, Locale.ENGLISH))
        assertEquals("12,3 km", RouteFormat.distance(12_345.0, Locale("es")))
        assertEquals("620 km", RouteFormat.distance(620_400.0, Locale.ENGLISH))
        assertEquals("1 min", RouteFormat.duration(10.0))
        assertEquals("45 min", RouteFormat.duration(2_700.0))
        assertEquals("2 h", RouteFormat.duration(7_200.0))
        assertEquals("6 h 5 min", RouteFormat.duration(21_900.0))
    }

    @Test
    fun logcatLineHasProfileMillisAndResultOnly() {
        ShadowLog.clear()
        LogcatRouteLog.computed(RoutingProfile.BIKE, 1234, "ok")
        val entry = ShadowLog.getLogs().single { it.tag == "UMROUTE" }
        assertEquals(Log.INFO, entry.type)
        assertEquals("route profile=bike ms=1234 result=ok", entry.msg)
        assertTrue(Regex("""\d+\.\d+""").find(entry.msg) == null)
    }
}
