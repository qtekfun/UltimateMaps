package com.qtekfun.ultimatemaps.nav

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.nav.ManeuverInfo
import com.qtekfun.ultimatemaps.core.nav.NavProblem
import com.qtekfun.ultimatemaps.core.nav.NavState
import com.qtekfun.ultimatemaps.core.nav.NavStatus
import com.qtekfun.ultimatemaps.core.routing.Maneuver
import com.qtekfun.ultimatemaps.core.routing.TurnType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavServiceTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun state(status: NavStatus, next: ManeuverInfo? = null) = NavState(
        status, LatLon(40.0, -3.0), 0f, 100.0, 12_400.0, 1_100.0, next, null, null, false, emptyList(), false, 10.0, 0.0, 0,
    )

    @Test fun theNotificationShowsTheNextManeuverAndTheRemainingTrip() {
        val next = ManeuverInfo(Maneuver(5, TurnType.RIGHT, "Calle Mayor"), 300.0)
        val c = NavNotificationTexts.of(context, state(NavStatus.ON_ROUTE, next), null, Locale.ENGLISH)
        assertTrue(c.title.contains("300 m") && c.title.contains("Turn right") && c.title.contains("Calle Mayor"), c.title)
        assertEquals("12.4 km · 18 min", c.text)
    }

    @Test fun theNotificationNamesTheExitNumberAndTheRoadItLeadsTo() {
        val next = ManeuverInfo(Maneuver(5, TurnType.EXIT_RIGHT, "Calle Mayor", exitRef = "23", towardRef = "A-2"), 800.0)
        val c = NavNotificationTexts.of(context, state(NavStatus.ON_ROUTE, next), null, Locale.ENGLISH)
        assertEquals("800 m: Exit 23 · Take the exit on the right toward A-2", c.title)
    }

    @Test fun theNotificationFollowsTheImperialUnitsSetting() {
        val next = ManeuverInfo(Maneuver(5, TurnType.RIGHT, "Calle Mayor"), 300.0)
        val c = NavNotificationTexts.of(
            context, state(NavStatus.ON_ROUTE, next), null, Locale.ENGLISH, com.qtekfun.ultimatemaps.core.voice.DistanceUnits.IMPERIAL,
        )
        assertTrue(c.title.contains("980 ft") && !c.title.contains(" m "), c.title) // 300 m = 984 ft
        assertEquals("7.7 mi · 18 min", c.text) // 12.4 km = 7.7 mi
    }

    @Test fun statusAndProblemsHaveTheirOwnTexts() {
        assertEquals("You have arrived", NavNotificationTexts.of(context, state(NavStatus.ARRIVED), null).title)
        assertEquals("Waiting for GPS signal…", NavNotificationTexts.of(context, state(NavStatus.NO_SIGNAL), null).title)
        assertEquals("Recalculating the route…", NavNotificationTexts.of(context, state(NavStatus.REROUTING), null).title)
        assertTrue(NavNotificationTexts.of(context, state(NavStatus.ON_ROUTE), NavProblem.LOCATION_PERMISSION).text.contains("permission"))
        assertTrue(NavNotificationTexts.of(context, state(NavStatus.ON_ROUTE), NavProblem.LOCATION_DISABLED).text.contains("off"))
        assertTrue(NavNotificationTexts.of(context, null, null).text.isNotBlank())
    }

    @Test fun everyTurnTypeHasAString() {
        for (t in TurnType.entries) assertTrue(context.getString(NavNotificationTexts.turnRes(t)).isNotBlank(), t.name)
    }

    @Test fun navStringsExistInBothLanguages() {
        fun keys(f: String) = Regex("<string name=\"([^\"]+)\"").findAll(File(f).readText()).map { it.groupValues[1] }.toSet()
        assertEquals(keys("src/main/res/values/strings_nav.xml"), keys("src/main/res/values-es/strings_nav.xml"))
    }

    @Test fun theServiceIsALocationForegroundServiceAndNothingNewIsExported() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = Regex("<service[^>]*NavigationService[^>]*/>", RegexOption.DOT_MATCHES_ALL).find(manifest)?.value
        assertTrue(service != null)
        assertTrue(service.contains("android:foregroundServiceType=\"location\""))
        assertTrue(service.contains("android:exported=\"false\""))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_LOCATION\""))
        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS\""))
        assertFalse(service.contains("android:process")) // the follower lives in the main process; only the core is isolated
    }
}
